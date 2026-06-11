package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object ImageSearchService {
    private val logger = LoggerFactory.getLogger(ImageSearchService::class.java)
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_DOWNLOAD_ATTEMPTS = 8
    private const val RECENT_URLS_PER_QUERY = 20
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // Прямые ссылки на оригиналы с сайтов (img_url в HTML выдачи Яндекса)
    private val yandexOrigUrlPattern = Regex("""img_url=([^&]+)""")
    // CDN Яндекса для превью в выдаче — не «аватарки пользователей», а их хостинг картинок
    private val yandexCdnImagePattern = Regex("""https://avatars\.mds\.yandex\.net/get-[^"\\&]+""")
    private val bingUrlPattern = Regex("""murl&quot;:&quot;([^&]+)""")

    private val recentUrlsByQuery = ConcurrentHashMap<String, ArrayDeque<String>>()

    fun searchImageBytes(query: String): ByteArray? {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val queryKey = normalizeQuery(query)

        val allCandidates = buildList {
            addAll(findYandexUrls(encoded))
            addAll(findBingUrls(encoded))
        }.distinct()

        val freshCandidates = allCandidates.filterNot { isRecent(queryKey, it) }
        val candidates = freshCandidates.ifEmpty { allCandidates }

        var attempts = 0
        for (url in candidates) {
            if (attempts >= MAX_DOWNLOAD_ATTEMPTS) break
            downloadImage(url)?.let { bytes ->
                rememberUrl(queryKey, url)
                return bytes
            }
            attempts++
        }
        return null
    }

    fun extensionFor(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        else -> "jpg"
    }

    private fun normalizeQuery(query: String): String =
        query.trim().lowercase(Locale("ru", "RU"))

    private fun isRecent(queryKey: String, url: String): Boolean {
        val deque = recentUrlsByQuery[queryKey] ?: return false
        synchronized(deque) { return url in deque }
    }

    private fun rememberUrl(queryKey: String, url: String) {
        val deque = recentUrlsByQuery.computeIfAbsent(queryKey) { ArrayDeque() }
        synchronized(deque) {
            deque.remove(url)
            deque.addLast(url)
            while (deque.size > RECENT_URLS_PER_QUERY) {
                deque.removeFirst()
            }
        }
    }

    private fun findYandexUrls(encodedQuery: String): List<String> {
        val html = get("https://yandex.ru/images/search?text=$encodedQuery") ?: return emptyList()
        val fromOrig = yandexOrigUrlPattern.findAll(html)
            .map { decodeEmbeddedUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }
        val fromCdn = yandexCdnImagePattern.findAll(html).map { it.value }
        return (fromOrig + fromCdn).distinct().toList()
    }

    private fun decodeEmbeddedUrl(raw: String): String =
        URLDecoder.decode(raw.replace("&amp;", "&"), StandardCharsets.UTF_8)

    private fun findBingUrls(encodedQuery: String): List<String> {
        val html = get("https://www.bing.com/images/search?q=$encodedQuery&form=HDRSC2&first=1") ?: return emptyList()
        return bingUrlPattern.findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    private fun get(url: String): String? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) response.body() else null
    } catch (e: Exception) {
        logger.warn("Image search GET failed: {}", url, e)
        null
    }

    private fun downloadImage(url: String): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) return null
            val bytes = response.body()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            if (!looksLikeImage(bytes, response.headers().firstValue("content-type").orElse(""))) return null
            bytes
        } catch (e: Exception) {
            logger.debug("Image download failed: {}", url, e)
            null
        }
    }

    private fun looksLikeImage(bytes: ByteArray, contentType: String): Boolean {
        if (contentType.startsWith("image/")) return true
        return bytes.size >= 4 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) ||
                (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
                (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())
            )
    }
}
