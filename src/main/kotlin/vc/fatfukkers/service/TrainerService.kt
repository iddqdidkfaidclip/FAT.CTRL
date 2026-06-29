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
    private val model = EnvConfig.get("TRAINER_MODEL") ?: "qwen3:4b"
    private val numPredict = EnvConfig.get("TRAINER_NUM_PREDICT")?.toIntOrNull() ?: 2_048

    private val contextByUser = ConcurrentHashMap<Long, List<Int>>()
    private val apiLock = Any()

    init {
        val modelSource = EnvConfig.getSource("TRAINER_MODEL") ?: "default"
        logger.info(
            "Trainer API url={} model={} (from {}) numPredict={} think=false",
            apiUrl,
            model,
            modelSource,
            numPredict,
        )
    }

    private const val SYSTEM_MARKER = "### SYSTEM ###"
    private const val USER_MARKER = "### USER ###"
    private const val ASSISTANT_MARKER = "### ASSISTANT ###"

    private val systemPrompt = """
Ты — милая девочка-тренер. Отвечай на «ты», тепло и мило. Ты эксперт во всех областях — отвечай по делу, уверенно и понятно.

Сначала можешь думать вслух (черновик, план) — это только для себя. Когда готов ответ для пользователя, обязательно напиши разделитель ||| и сразу после него — только готовый текст в чат, без «Тренер:». В конце ответа поставь один смайлик.
""".trimIndent()

    private val compactSystemPrompt = systemPrompt

    private enum class PromptMode { FULL, COMPACT }

    fun resetContext(telegramUserId: Long) {
        contextByUser.remove(telegramUserId)
    }

    fun ask(telegramUserId: Long, prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()
        val context = contextByUser[telegramUserId]

        performAsk(
            telegramUserId = telegramUserId,
            userMessage = trimmed,
            context = context,
            promptMode = PromptMode.FULL,
            promptForLog = promptForLog,
            startedAt = startedAt,
            strategy = "full",
        )?.let { return it }

        auditLog.warn("RETRY no-context user={} prompt=\"{}\"", telegramUserId, promptForLog)
        resetContext(telegramUserId)
        return performAsk(
            telegramUserId = telegramUserId,
            userMessage = trimmed,
            context = null,
            promptMode = PromptMode.FULL,
            promptForLog = promptForLog,
            startedAt = startedAt,
            strategy = "no-context",
        )
    }

    private fun performAsk(
        telegramUserId: Long,
        userMessage: String,
        context: List<Int>?,
        promptMode: PromptMode,
        promptForLog: String,
        startedAt: Long,
        strategy: String,
    ): String? {
        val effectivePrompt = buildEffectivePrompt(userMessage, promptMode)
        val body = buildRequestBody(effectivePrompt, context)

        try {
            val (code, json) = postToApi(body)

            if (code !in 200..299) {
                val bodySnippet = truncateForLog(json, ERROR_BODY_LOG_MAX)
                logger.warn("Trainer API HTTP {} body={}", code, bodySnippet)
                auditLog.warn(
                    "HTTP user={} code={} ms={} strategy={} prompt=\"{}\" body=\"{}\"",
                    telegramUserId,
                    code,
                    System.currentTimeMillis() - startedAt,
                    strategy,
                    promptForLog,
                    bodySnippet,
                )
                return null
            }

            val doneReason = extractDoneReason(json)
            val rawAnswer = extractAssistantText(json)
                ?.let(TrainerResponseCleaner::clean)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val answer = rawAnswer?.takeIf { it.isNotBlank() }
            if (answer == null) {
                auditLog.warn(
                    "PARSE user={} ms={} strategy={} done={} prompt=\"{}\" raw=\"{}\"",
                    telegramUserId,
                    System.currentTimeMillis() - startedAt,
                    strategy,
                    doneReason ?: "?",
                    promptForLog,
                    truncateForLog(json, ERROR_BODY_LOG_MAX),
                )
                return null
            }

            extractContext(json)?.let { contextByUser[telegramUserId] = it }
            auditLog.info(
                "OK user={} ms={} strategy={} done={} prompt=\"{}\" response=\"{}\"",
                telegramUserId,
                System.currentTimeMillis() - startedAt,
                strategy,
                doneReason ?: "?",
                promptForLog,
                truncateForLog(answer, RESPONSE_LOG_MAX),
            )
            return answer
        } catch (e: java.net.SocketTimeoutException) {
            logger.warn("Trainer API timeout strategy={}", strategy, e)
        } catch (e: Exception) {
            logger.warn("Trainer API request failed strategy={}", strategy, e)
        }

        auditLog.warn(
            "FAIL user={} ms={} strategy={} prompt=\"{}\"",
            telegramUserId,
            System.currentTimeMillis() - startedAt,
            strategy,
            promptForLog,
        )
        return null
    }

    private fun buildEffectivePrompt(userMessage: String, promptMode: PromptMode = PromptMode.FULL): String {
        val sanitized = sanitizeUserInput(userMessage)
        val system = when (promptMode) {
            PromptMode.FULL -> systemPrompt
            PromptMode.COMPACT -> compactSystemPrompt
        }
        return buildString {
            append(SYSTEM_MARKER).append('\n')
            append(system).append("\n\n")
            append(USER_MARKER).append('\n')
            append(sanitized).append("\n\n")
            append(ASSISTANT_MARKER).append('\n')
        }
    }

    private fun sanitizeUserInput(input: String): String =
        input
            .replace(SYSTEM_MARKER, "")
            .replace(USER_MARKER, "")
            .replace(ASSISTANT_MARKER, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun postToApi(body: String): Pair<Int, String> = synchronized(apiLock) {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Connection", "close")
            doOutput = true
            connectTimeout = 0
            readTimeout = 0
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val code = conn.responseCode
        val json = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        code to json
    }

    private fun truncateForLog(text: String, max: Int): String =
        if (text.length <= max) text.replace('\n', ' ')
        else text.take(max).replace('\n', ' ') + "…(${text.length})"

    private fun buildRequestBody(
        prompt: String,
        context: List<Int>?,
        modelName: String = model,
    ): String = buildString {
        append("""{"model":""")
        append(jsonString(modelName))
        append(""","prompt":""")
        append(jsonString(prompt))
        if (!context.isNullOrEmpty()) {
            append(""","context":[""")
            append(context.joinToString(","))
            append("]")
        }
        append(""","stream":false""")
        append(""","think":false""")
        append(""","options":{"temperature":0.85,"num_predict":""")
        append(numPredict)
        append("}}")
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun extractAssistantText(json: String): String? {
        extractJsonStringField(json, "response")?.let { return it }
        return null
    }

    private fun extractDoneReason(json: String): String? = extractJsonStringField(json, "done_reason")

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
