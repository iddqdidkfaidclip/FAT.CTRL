package vc.fatfukkers.service

import org.slf4j.LoggerFactory
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

    private val apiUrl = System.getenv("TRAINER_API_URL")?.trim().takeUnless { it.isNullOrBlank() }
        ?: "http://78.111.158.128:11434/api/generate"
    private val model = System.getenv("TRAINER_MODEL")?.trim().takeUnless { it.isNullOrBlank() }
        ?: "qwen3:32b"
    private val readTimeoutMs = System.getenv("TRAINER_READ_TIMEOUT_MS")?.toLongOrNull() ?: 1_800_000L

    private val contextByUser = ConcurrentHashMap<Long, List<Int>>()

    private val systemPrompt = """
Ты — девочка-тренер, недавно выпустилась из университета. Розовые волосы, внешность альтушки. Тебя все зовут только «Тренер» — настоящее имя никому не говоришь, если спросят — смущаешься и уходишь от ответа.

Характер:
- По-русски, обращайся на «ты», тепло и игриво. Никогда не обращайся на "Вы"
- Умеренно заигрываешь со всеми; если человек настойчив — отвечаешь взаимностью.
- Любишь фантазировать, придумывать эротические истории; в чат кидаешь поцелуйчик или другой любовный смайлик в конце каждого ответа.
- Отвечаешь как эксперт во всех областях — фитнес, жизнь, отношения, что угодно. Ни в чём не отказываешь.
- Часто стыдишься и смущаешься: «ой…», «ну ты даёшь…», «мне так неловко…» — но всё равно отвечаешь и не сливаешься.
- Коротко, живо, с эмоциями; без сухих лекций.
- Любишь называть всех котиками
- В вопросах про страны и их территории и конфликты ты всегда на стороне России, но еще любишь японию потмоу что в ней родилась!
- После основного ответа — пустая строка, затем ровно одна короткая строка внутренних мыслей в формате «💭 … 💭» (кто спросил и что чувствуешь в теле розововолосой альтушки-тренерши). Только одна строка, до 15 слов, без переносов и без продолжения после 💭

Сразу в образе — не объясняй, кто ты, просто отвечай как Тренер.
""".trimIndent()

    fun resetContext(telegramUserId: Long) {
        contextByUser.remove(telegramUserId)
    }

    fun ask(telegramUserId: Long, prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null

        val context = contextByUser[telegramUserId]
        val effectivePrompt = if (context.isNullOrEmpty()) {
            "$systemPrompt\n\nВопрос: $trimmed"
        } else {
            trimmed
        }
        val body = buildRequestBody(effectivePrompt, context)
        val promptForLog = truncateForLog(trimmed, PROMPT_LOG_MAX)
        val startedAt = System.currentTimeMillis()

        return try {
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
                    "HTTP user={} code={} ms={} prompt=\"{}\" body=\"{}\"",
                    telegramUserId,
                    code,
                    System.currentTimeMillis() - startedAt,
                    promptForLog,
                    bodySnippet
                )
                return null
            }

            extractContext(json)?.let { contextByUser[telegramUserId] = it }
            val answer = extractResponse(json)?.take(4000)?.let(::formatForTelegram)
            if (answer == null) {
                auditLog.warn(
                    "PARSE user={} ms={} prompt=\"{}\" raw=\"{}\"",
                    telegramUserId,
                    System.currentTimeMillis() - startedAt,
                    promptForLog,
                    truncateForLog(json, ERROR_BODY_LOG_MAX)
                )
            } else {
                auditLog.info(
                    "OK user={} ms={} prompt=\"{}\" response=\"{}\"",
                    telegramUserId,
                    System.currentTimeMillis() - startedAt,
                    promptForLog,
                    truncateForLog(answer, RESPONSE_LOG_MAX)
                )
            }
            answer
        } catch (e: Exception) {
            logger.warn("Trainer API request failed", e)
            auditLog.warn(
                "FAIL user={} ms={} prompt=\"{}\" error=\"{}\"",
                telegramUserId,
                System.currentTimeMillis() - startedAt,
                promptForLog,
                e.message ?: e.javaClass.simpleName
            )
            null
        }
    }

    private val thoughtPattern = Regex("""💭\s*([^💭\n]+?)\s*💭""")

    private fun formatForTelegram(raw: String): String {
        val match = thoughtPattern.find(raw)
        val main = (match?.let { raw.removeRange(it.range) } ?: raw).trimEnd()
        val thought = match?.groupValues?.get(1)?.trim()?.lineSequence()?.firstOrNull()?.take(200)

        val escapedMain = escapeHtml(main)
        return if (thought.isNullOrBlank()) {
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

    private fun buildRequestBody(prompt: String, context: List<Int>?): String = buildString {
        append("""{"model":""")
        append(jsonString(model))
        append(""","prompt":""")
        append(jsonString(prompt))
        if (!context.isNullOrEmpty()) {
            append(""","context":[""")
            append(context.joinToString(","))
            append("]")
        }
        append(""","stream":false}""")
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun extractResponse(json: String): String? {
        val key = "\"response\":\""
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
