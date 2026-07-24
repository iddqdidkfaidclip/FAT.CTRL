package vc.fatfukkers.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Тонкий клиент к OpenAI-compatible Chat Completions (DeepSeek).
 * При отсутствии ключа / сетевых ошибках / не-2xx возвращает null — вызывающий код уходит в заглушки.
 */
object AiChatService {
    private val logger = LoggerFactory.getLogger(AiChatService::class.java)

    data class Message(val role: String, val content: String)

    private const val DEFAULT_URL = "https://api.deepseek.com/chat/completions"
    private const val DEFAULT_MODEL = "deepseek-v4-flash"
    private const val DEFAULT_TIMEOUT_SEC = 90L
    private const val ERROR_BODY_LOG_MAX = 400

    private val apiUrl = EnvConfig.get("AI_API_URL") ?: DEFAULT_URL
    private val apiKey = EnvConfig.get("AI_API_KEY")
    private val model = EnvConfig.get("AI_MODEL") ?: DEFAULT_MODEL
    private val timeoutSec = EnvConfig.get("AI_TIMEOUT_SEC")?.toLongOrNull()?.coerceIn(5, 300)
        ?: DEFAULT_TIMEOUT_SEC

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = !apiKey.isNullOrBlank()

    init {
        if (isConfigured) {
            logger.info("AI chat ready url={} model={} timeoutSec={}", apiUrl, model, timeoutSec)
        } else {
            logger.warn("AI chat disabled: AI_API_KEY not set — stubs will be used")
        }
    }

    fun complete(messages: List<Message>): String? {
        if (!isConfigured) return null
        if (messages.isEmpty()) return null

        val body = buildRequestBody(messages)
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "AI chat HTTP {} body={}",
                    response.statusCode(),
                    truncate(response.body(), ERROR_BODY_LOG_MAX),
                )
                return null
            }
            extractAssistantContent(response.body())
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: java.net.http.HttpTimeoutException) {
            logger.warn("AI chat timeout after {}s", timeoutSec)
            null
        } catch (e: Exception) {
            logger.warn("AI chat request failed: {}", e.message)
            null
        }
    }

    private fun buildRequestBody(messages: List<Message>): String {
        val messagesJson = JsonArray(
            messages.map { msg ->
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(msg.role),
                        "content" to JsonPrimitive(msg.content),
                    ),
                )
            },
        )
        val root = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "messages" to messagesJson,
                "thinking" to JsonObject(mapOf("type" to JsonPrimitive("disabled"))),
                "reasoning_effort" to JsonPrimitive("high"),
                "stream" to JsonPrimitive(false),
            ),
        )
        return root.toString()
    }

    internal fun extractAssistantContent(rawJson: String): String? {
        return try {
            val root = jsonParser.parseToJsonElement(rawJson).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val first = choices.firstOrNull()?.jsonObject ?: return null
            first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: first["text"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.warn("AI chat parse failed: {}", e.message)
            null
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text.replace('\n', ' ')
        else text.take(max).replace('\n', ' ') + "…(${text.length})"
}
