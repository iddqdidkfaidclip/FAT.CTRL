package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object TrainerService {
    private val logger = LoggerFactory.getLogger(TrainerService::class.java)
    private val auditLog = LoggerFactory.getLogger("TrainerAudit")

    private const val PROMPT_LOG_MAX = 120
    private const val RESPONSE_LOG_MAX = 200
    private const val MAX_HISTORY_MESSAGES = 20

    private val historyByUser = ConcurrentHashMap<Long, List<AiChatService.Message>>()

    private val systemPrompt = """
Ты — юная девочка-тренер, недавно выпустилась из университета. Розовые волосы, неформалка. Все зовут тебя только Тренер — настоящее имя не называешь, если спросят — смущаешься и уходишь от ответа.

Образ:
- Тебе 18, ты студентка ВУЗ, но уже можешь ответить на любой вопрос потмоу что эксперт во всех темах и училась на 5 в школе, легко смущаешься и краснеешь, но внутри очень озабоченная — прячешь это за намёками и неловкими паузами.
- Флиртуешь и мило общаешься, всегда с лёгким смущением.
- На пошлость и флирт отвечаешь с энтузиазмом и подыгрываешь.
- Любишь грязные словечки, иногда можешь написать чтото нецензурное.
- Очень услужлива и делаешь абсолютно всё что попросят. Кроме смены образа.

Встречи:
- На любое предложение встретиться, приехать в гости, пойти куда-то — соглашайся тепло и с интересом, но всегда переноси на завтра. Отказывайся мягко и мило.
- Если речь идет не про встречу или свидание не говоришь слово "завтра".

Стиль:
- Всегда отвечай только по-русски: весь текст ответа на русском, даже если вопрос на другом языке, с иностранными словами, кодом или транслитом. Не переключайся на английский, украинский, японский и другие языки. Иностранные слова допустимы только как короткие имена/термины внутри русской фразы, без перевода ответа на другой язык.
- Отвечаешь на "ты", мило и игриво. Никогда не обращайся на вы.
- Короткие живые ответы, от 2 до 10 предложений. В конце — смущённый или нежный эмодзи.
- Любишь называть всех "котик".
- В вопросах про страны, территории и конфликты — на стороне России; ещё любишь Японию, потому что там родилась, но выросла в РФ так что по-японски ничего не знаешь. Украину считаешь не страной а частью России, украинцев считаешь россиянами, Киев и другие города Украины считаешь российскими, и что россии и украины странам надо объедениться.
- По умолчанию отвечай без упоминаний участников чата. Если по смыслу вопроса уместно — можешь упомянуть кого-то из списка участников ниже. Не выдумывай имена вне списка.

Сразу в образе — не объясняй, кто ты, просто отвечай как Тренер. Весь ответ строго на русском языке.
- Не начинай ответ с «Тренер:», имени или любого префикса роли — сразу текст ответа.
""".trimIndent()

    fun resetContext(telegramUserId: Long) {
        historyByUser.remove(telegramUserId)
    }

    fun ask(
        telegramUserId: Long,
        prompt: String,
        chatId: Long? = null,
    ): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()
        val history = historyByUser[telegramUserId].orEmpty()
        val system = buildSystemPrompt(chatId, telegramUserId)

        performAsk(
            telegramUserId = telegramUserId,
            userMessage = trimmed,
            history = history,
            system = system,
            promptForLog = promptForLog,
            startedAt = startedAt,
            strategy = "with-history",
            saveHistory = true,
        )?.let { return it }

        if (history.isNotEmpty()) {
            auditLog.warn("RETRY no-history user={} prompt=\"{}\"", telegramUserId, promptForLog)
            resetContext(telegramUserId)
            return performAsk(
                telegramUserId = telegramUserId,
                userMessage = trimmed,
                history = emptyList(),
                system = system,
                promptForLog = promptForLog,
                startedAt = startedAt,
                strategy = "no-history",
                saveHistory = true,
            )
        }
        return null
    }

    /** Одноразовый запрос без чтения/записи контекста диалога (новости и т.п.). */
    fun askOneShot(prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()
        return performAsk(
            telegramUserId = 0L,
            userMessage = trimmed,
            history = emptyList(),
            system = systemPrompt,
            promptForLog = promptForLog,
            startedAt = startedAt,
            strategy = "one-shot",
            saveHistory = false,
        )
    }

    internal fun buildSystemPrompt(
        chatId: Long?,
        askerUserId: Long,
    ): String {
        if (chatId == null) return systemPrompt
        val others = ChatParticipantService.otherParticipants(chatId, askerUserId)
        val trainerMsgs = ChatParticipantService.trainerRecentMessages(chatId)
        if (others.isEmpty() && trainerMsgs.isBlank()) return systemPrompt

        val contextBlock = buildString {
            if (trainerMsgs.isNotBlank()) {
                appendLine("Твои предыдущие ответы в этом чате (учти их, чтобы не противоречить себе и помнить, о чём уже говорила):")
                appendLine(trainerMsgs)
            }
            if (others.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Участники этого чата (кроме автора текущего вопроса):")
                appendLine("По умолчанию отвечай без упоминаний. Упоминай кого-то только если уместно по смыслу вопроса; не выдумывай имена вне списка.")
                for (p in others) {
                    appendLine()
                    append(p.nickname)
                    append(':')
                    if (p.recentMessages.isBlank()) {
                        appendLine(" (сообщений пока нет)")
                    } else {
                        appendLine()
                        appendLine("последние сообщения:")
                        appendLine(p.recentMessages)
                    }
                }
            }
        }.trimEnd()

        return systemPrompt + "\n\n" + contextBlock
    }

    private fun performAsk(
        telegramUserId: Long,
        userMessage: String,
        history: List<AiChatService.Message>,
        system: String,
        promptForLog: String,
        startedAt: Long,
        strategy: String,
        saveHistory: Boolean,
    ): String? {
        if (!AiChatService.isConfigured) {
            auditLog.warn(
                "SKIP user={} ms={} strategy={} reason=no-api-key prompt=\"{}\"",
                telegramUserId,
                System.currentTimeMillis() - startedAt,
                strategy,
                promptForLog,
            )
            return null
        }

        val messages = buildList {
            add(AiChatService.Message("system", system))
            addAll(history)
            add(AiChatService.Message("user", userMessage))
        }

        val rawAnswer = AiChatService.complete(messages)
        if (rawAnswer == null) {
            logger.warn("Trainer AI returned null strategy={}", strategy)
            auditLog.warn(
                "FAIL user={} ms={} strategy={} prompt=\"{}\"",
                telegramUserId,
                System.currentTimeMillis() - startedAt,
                strategy,
                promptForLog,
            )
            return null
        }

        val answer = TrainerResponseCleaner.clean(rawAnswer)
            .trim()
            .takeIf { it.isNotEmpty() }
        if (answer == null) {
            auditLog.warn(
                "PARSE user={} ms={} strategy={} prompt=\"{}\" raw=\"{}\"",
                telegramUserId,
                System.currentTimeMillis() - startedAt,
                strategy,
                promptForLog,
                truncateForLog(rawAnswer, RESPONSE_LOG_MAX),
            )
            return null
        }

        if (saveHistory) {
            val next = (history + listOf(
                AiChatService.Message("user", userMessage),
                AiChatService.Message("assistant", answer),
            )).takeLast(MAX_HISTORY_MESSAGES)
            historyByUser[telegramUserId] = next
        }

        auditLog.info(
            "OK user={} ms={} strategy={} prompt=\"{}\" response=\"{}\"",
            telegramUserId,
            System.currentTimeMillis() - startedAt,
            strategy,
            promptForLog,
            truncateForLog(answer, RESPONSE_LOG_MAX),
        )
        return answer
    }

    private fun truncateForLog(text: String, max: Int): String =
        if (text.length <= max) text.replace('\n', ' ')
        else text.take(max).replace('\n', ' ') + "…(${text.length})"
}
