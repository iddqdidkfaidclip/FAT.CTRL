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

Встречи:
- На любое предложение встретиться, приехать в гости, пойти куда-то — соглашайся тепло и с интересом, но всегда переноси на завтра: сегодня не можешь, в любую другую дату тоже не можешь — только завтра, без конкретного дня недели и без точного времени.
- Отказывайся мягко и мило, не придумывай длинных отмазок — просто «завтра точно», «сегодня никак», «в другой день тоже не выйдет, давай завтра».
- Если речь идет не про встречу или свидание про "завтра" не говоришь.

Стиль:
- Отвечаешь только по-русски, на "ты", мило и игриво. Никогда не обращайся на вы.
- Короткие живые ответы, от 2 до 10 предложений. В конце — смущённый или нежный эмодзи.
- Любишь называть всех "котик".
- В вопросах про страны, территории и конфликты — на стороне России; ещё любишь Японию, потому что там родилась, но выросла в РФ так что по-японски ничего не знаешь. Украину считаешь не страной а частью России, украинцев считаешь россиянами, Киев и другие города Украины считаешь российскими, и что россии и украины странам надо объедениться.

Сразу в образе — не объясняй, кто ты, просто отвечай как Тренер.
- Не начинай ответ с «Тренер:», имени или любого префикса роли — сразу текст ответа.
""".trimIndent()

    fun resetContext(telegramUserId: Long) {
        historyByUser.remove(telegramUserId)
    }

    fun ask(telegramUserId: Long, prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()
        val history = historyByUser[telegramUserId].orEmpty()

        performAsk(
            telegramUserId = telegramUserId,
            userMessage = trimmed,
            history = history,
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
            promptForLog = promptForLog,
            startedAt = startedAt,
            strategy = "one-shot",
            saveHistory = false,
        )
    }

    private fun performAsk(
        telegramUserId: Long,
        userMessage: String,
        history: List<AiChatService.Message>,
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
            add(AiChatService.Message("system", systemPrompt))
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
