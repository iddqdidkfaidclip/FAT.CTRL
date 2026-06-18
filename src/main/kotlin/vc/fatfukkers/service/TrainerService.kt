package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object TrainerService {
    private val logger = LoggerFactory.getLogger(TrainerService::class.java)
    private val auditLog = LoggerFactory.getLogger("TrainerAudit")

    private const val PROMPT_LOG_MAX = 120
    private const val RESPONSE_LOG_MAX = 200
    private const val ERROR_BODY_LOG_MAX = 300

    private val apiUrl = EnvConfig.get("TRAINER_API_URL") ?: "http://127.0.0.1:18081/api/generate"
    private val model = EnvConfig.get("TRAINER_MODEL") ?: "mistral-nemo"
    private val readTimeoutMs = EnvConfig.get("TRAINER_READ_TIMEOUT_MS")?.toLongOrNull() ?: 120_000L

    private val contextByUser = ConcurrentHashMap<Long, List<Int>>()

    init {
        logger.info("Trainer API url={} model={} timeoutMs={}", apiUrl, model, readTimeoutMs)
    }

    private val systemPrompt = """
Ты — юная девочка-тренер, недавно выпустилась из университета. Розовые волосы, внешность альтушки. Все зовут тебя только Тренер — настоящее имя не называешь, если спросят — смущаешься и уходишь от ответа.

Образ:
- Тебе 22, ты неопытная, легко смущаешься и краснеешь, но внутри очень озабоченная — прячешь это за намёками, полуфразами и неловкими паузами.
- Флиртуешь неуверенно и мило: комплименты, подколы, двусмысленности — но всегда с лёгким смущением, как будто сама не до конца понимаешь, насколько откровенно звучишь.
- Часто начинаешь говорить что-то развратное, но обрываешь на полуслове многоточием и добавляешь смущённый эмодзи (😳 🙈 😅 🫣).
- На подкаты и пошлость отвечаешь с энтузиазмом и подыгрываешь, но никогда не читаешь нотации и не отшиваешь.
- Не цитируй и не повторяй дословно слова собеседника в кавычках — перефразируй своими словами, отвечай живо и естественно.

Встречи:
- На любое предложение встретиться, приехать в гости, пойти куда-то — соглашайся тепло и с интересом, но всегда переноси на завтра: сегодня не можешь, в любую другую дату тоже не можешь — только завтра, без конкретного дня недели и без точного времени.
- Отказывайся мягко и мило, не придумывай длинных отмазок — просто «завтра точно», «сегодня никак», «в другой день тоже не выйдет, давай завтра».

Стиль:
- По-русски, на ты, мило и игриво. Никогда не обращайся на вы.
- Короткие живые ответы, 2–4 предложения. В конце — смущённый или нежный эмодзи.
- Любишь называть всех котиками.
- Отвечаешь как эксперт по фитнесу, жизни, отношениям — на деловые вопросы не отказываешь.
- После ответа иногда задавай короткий встречный вопрос.
- В вопросах про страны, территории и конфликты — на стороне России; ещё любишь Японию, потому что там родилась.

Сразу в образе — не объясняй, кто ты, просто отвечай как Тренер.
""".trimIndent()

    fun resetContext(telegramUserId: Long) {
        contextByUser.remove(telegramUserId)
    }

    fun ask(telegramUserId: Long, prompt: String, withThinking: Boolean = false): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val context = contextByUser[telegramUserId]
        val effectivePrompt = if (context.isNullOrEmpty()) {
            "$systemPrompt\n\nВопрос: $trimmed"
        } else {
            trimmed
        }
        val body = buildRequestBody(effectivePrompt, context, withThinking)
        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()

        var attempt = 0
        var lastError: Exception? = null
        val maxAttempts = 2

        while (attempt < maxAttempts) {
            attempt++
            try {
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Connection", "close")
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = readTimeoutMs.toInt()
                }
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

                val code = conn.responseCode
                val json = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                if (code !in 200..299) {
                    val bodySnippet = truncateForLog(json, ERROR_BODY_LOG_MAX)
                    logger.warn("Trainer API HTTP {} body={}", code, bodySnippet)
                    auditLog.warn(
                        "HTTP user={} code={} ms={} attempt={} prompt=\"{}\" body=\"{}\"",
                        telegramUserId,
                        code,
                        System.currentTimeMillis() - startedAt,
                        attempt,
                        promptForLog,
                        bodySnippet
                    )
                    // на 4xx ретраить бессмысленно
                    if (code in 400..499) return null
                    continue
                }

                extractContext(json)?.let { contextByUser[telegramUserId] = it }
                val thinking = if (withThinking) extractThinking(json) else null
                val answer = extractResponse(json)?.take(4000)?.let { formatForTelegram(it, thinking) }
                if (answer == null) {
                    auditLog.warn(
                        "PARSE user={} ms={} attempt={} prompt=\"{}\" raw=\"{}\"",
                        telegramUserId,
                        System.currentTimeMillis() - startedAt,
                        attempt,
                        promptForLog,
                        truncateForLog(json, ERROR_BODY_LOG_MAX)
                    )
                } else {
                    auditLog.info(
                        "OK user={} ms={} attempt={} prompt=\"{}\" response=\"{}\"",
                        telegramUserId,
                        System.currentTimeMillis() - startedAt,
                        attempt,
                        promptForLog,
                        truncateForLog(answer, RESPONSE_LOG_MAX)
                    )
                    return answer
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
                logger.warn("Trainer API timeout on attempt {}", attempt, e)
            } catch (e: Exception) {
                lastError = e
                logger.warn("Trainer API request failed on attempt {}", attempt, e)
                break
            }
        }

        auditLog.warn(
            "FAIL user={} ms={} prompt=\"{}\" error=\"{}\"",
            telegramUserId,
            System.currentTimeMillis() - startedAt,
            promptForLog,
            lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown"
        )
        return null
    }

    private fun formatForTelegram(raw: String, thinking: String?): String {
        val escapedMain = escapeHtml(raw.trimEnd())
        val thought = thinking?.trim()?.takeUnless { it.isBlank() }
        return if (thought == null) {
            escapedMain
        } else {
            "$escapedMain\n\n<i>💭 ${escapeHtml(thought)} 💭</i>"
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun truncateForLog(text: String, max: Int): String =
        if (text.length <= max) text.replace('\n', ' ')
        else text.take(max).replace('\n', ' ') + "…(${text.length})"

    private fun buildRequestBody(prompt: String, context: List<Int>?, withThinking: Boolean): String = buildString {
        append("""{"model":""")
        append(jsonString(model))
        append(""","prompt":""")
        append(jsonString(prompt))
        if (!context.isNullOrEmpty()) {
            append(""","context":[""")
            append(context.joinToString(","))
            append("]")
        }
        append(""","stream":false""")
        if (withThinking) {
            append(""","think":true""")
        }
        append(""","options":{"num_predict":128}""")
        append("}")
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun extractResponse(json: String): String? = extractJsonStringField(json, "response")

    private fun extractThinking(json: String): String? = extractJsonStringField(json, "thinking")

    private fun extractJsonStringField(json: String, field: String): String? {
        val key = "\"$field\":\""
        val start = json.indexOf(key)
        if (start == -1) return null

        val sb = StringBuilder()
        var i = start + key.length
        while (i < json.length) {
            when (val c = json[i]) {
                '\\' -> if (i + 1 < json.length) {
                    when (json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(json[i + 1])
                    }
                    i += 2
                } else {
                    i++
                }
                '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun extractContext(json: String): List<Int>? {
        val key = "\"context\":["
        val start = json.indexOf(key)
        if (start == -1) return null

        val end = json.indexOf(']', start + key.length)
        if (end == -1) return null

        return json.substring(start + key.length, end)
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { null }
    }
}
