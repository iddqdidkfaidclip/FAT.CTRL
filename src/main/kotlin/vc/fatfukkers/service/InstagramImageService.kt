package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

object InstagramImageService {
    private val logger = LoggerFactory.getLogger(InstagramImageService::class.java)

    private const val IG_APP_ID = "936619743392459"
    private const val USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private const val TIMEOUT_SECONDS = 60L

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    private val shortcodePattern = Regex("""instagram\.com/p/([A-Za-z0-9_-]+)""")
    private val mediaIdMetaPattern = Regex("""instagram://media\?id=(\d+)""")
    private val mediaIdJsonPattern = Regex(""""media_id":"(\d+)"""")
    private val mediaTypePattern = Regex(""""media_type"\s*:\s*(\d+)""")
    private val imageUrlPattern = Regex(
        """"image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+)"""",
    )
    private val videoUrlPattern = Regex(
        """"video_versions"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+)"""",
    )
    private val pkSplitPattern = Regex("""(?="pk"\s*:\s*")""")

    enum class ItemKind { PHOTO, VIDEO }

    data class RemoteCarouselItem(
        val kind: ItemKind,
        val url: String,
    )

    data class LocalCarouselItem(
        val kind: ItemKind,
        val path: Path,
    )

    fun downloadCarousel(
        url: String,
        cookiesPath: String,
        tempDir: Path,
        id: String,
    ): List<LocalCarouselItem>? {
        val cookies = loadInstagramCookies(cookiesPath) ?: return null
        val shortcode = extractShortcode(url) ?: return null
        val postUrl = "https://www.instagram.com/p/$shortcode/"

        val mediaId = fetchMediaId(postUrl, cookies) ?: return null
        val apiJson = fetchMediaInfo(mediaId, cookies) ?: return null
        val remoteItems = parseCarouselItems(apiJson)
        if (remoteItems.isEmpty()) {
            logger.warn("Instagram carousel has no parsable items for {}", url)
            return null
        }
        logger.info("Instagram carousel {} items for {}", remoteItems.size, url)

        val localItems = mutableListOf<LocalCarouselItem>()
        remoteItems.forEachIndexed { index, item ->
            val ext = if (item.kind == ItemKind.VIDEO) "mp4" else "jpg"
            val dest = tempDir.resolve("dl-$id-$index.$ext")
            if (!downloadFile(item.url, cookies.header, dest)) {
                logger.warn("Failed to download carousel item {} for {}", index, url)
                cleanupFiles(tempDir, id)
                return null
            }
            localItems += LocalCarouselItem(item.kind, dest)
        }
        return localItems
    }

    private data class InstagramCookies(
        val header: String,
    )

    private fun extractShortcode(url: String): String? {
        val path = try {
            URI(url).path
        } catch (_: Exception) {
            return shortcodePattern.find(url)?.groupValues?.get(1)
        }
        return path.trim('/').removePrefix("p/").takeIf { it.isNotEmpty() }
            ?: shortcodePattern.find(url)?.groupValues?.get(1)
    }

    private fun loadInstagramCookies(path: String): InstagramCookies? {
        return try {
            val lines = Files.readAllLines(Path.of(path))
            val pairs = lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val parts = trimmed.split('\t')
                if (parts.size < 7) return@mapNotNull null
                val domain = parts[0]
                if (!domain.contains("instagram.com")) return@mapNotNull null
                parts[5] to parts[6]
            }
            if (pairs.isEmpty()) {
                null
            } else {
                InstagramCookies(header = pairs.joinToString("; ") { "${it.first}=${it.second}" })
            }
        } catch (e: Exception) {
            logger.warn("Failed to read Instagram cookies from {}", path, e)
            null
        }
    }

    private fun fetchMediaId(postUrl: String, cookies: InstagramCookies): String? {
        val html = httpGet(postUrl, cookies.header) ?: return null
        return mediaIdMetaPattern.find(html)?.groupValues?.get(1)
            ?: mediaIdJsonPattern.find(html)?.groupValues?.get(1)
    }

    private fun fetchMediaInfo(mediaId: String, cookies: InstagramCookies): String? =
        httpGet("https://i.instagram.com/api/v1/media/$mediaId/info/", cookies.header, mobile = true)

    private fun parseCarouselItems(apiJson: String): List<RemoteCarouselItem> {
        val section = when (val start = apiJson.indexOf("\"carousel_media\"")) {
            -1 -> apiJson
            else -> apiJson.substring(start)
        }

        val chunks = pkSplitPattern.split(section)
            .filter { it.contains("media_type") }

        if (chunks.isEmpty()) {
            return listOfNotNull(parseItemChunk(section))
        }

        return chunks.mapNotNull { parseItemChunk(it) }
    }

    private fun parseItemChunk(chunk: String): RemoteCarouselItem? {
        val mediaType = mediaTypePattern.find(chunk)?.groupValues?.get(1)?.toIntOrNull()
        if (mediaType == 2) {
            val videoUrl = videoUrlPattern.find(chunk)?.groupValues?.get(1)?.let(::decodeJsonUrl)
            if (videoUrl != null) {
                return RemoteCarouselItem(ItemKind.VIDEO, videoUrl)
            }
        }

        val imageUrl = imageUrlPattern.find(chunk)?.groupValues?.get(1)?.let(::decodeJsonUrl)
        if (imageUrl != null) {
            return RemoteCarouselItem(ItemKind.PHOTO, imageUrl)
        }

        return null
    }

    private fun decodeJsonUrl(raw: String): String =
        raw.replace("\\u0026", "&").replace("\\/", "/")

    private fun httpGet(url: String, cookieHeader: String, mobile: Boolean = false): String? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader)
            .header("Accept", "*/*")
            .apply {
                if (mobile) {
                    header("x-ig-app-id", IG_APP_ID)
                    header("x-asbd-id", "129477")
                }
            }
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            logger.warn("Instagram HTTP {} for {}", response.statusCode(), url)
            null
        } else {
            response.body()
        }
    } catch (e: Exception) {
        logger.warn("Instagram HTTP request failed for {}", url, e)
        null
    }

    private fun downloadFile(url: String, cookieHeader: String, dest: Path): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .header("Cookie", cookieHeader)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                logger.warn("Instagram media download HTTP {} for {}", response.statusCode(), url)
                false
            } else {
                val bytes = response.body()
                if (bytes.isEmpty()) {
                    false
                } else {
                    Files.createDirectories(dest.parent)
                    Files.write(dest, bytes)
                    true
                }
            }
        } catch (e: Exception) {
            logger.warn("Instagram media download failed for {}", url, e)
            false
        }
    }

    private fun cleanupFiles(tempDir: Path, id: String) {
        if (!Files.isDirectory(tempDir)) return
        try {
            Files.list(tempDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("dl-$id-") }
                    .forEach {
                        try {
                            Files.deleteIfExists(it)
                        } catch (_: Exception) {
                        }
                    }
            }
        } catch (e: Exception) {
            logger.debug("Failed to cleanup carousel files for {}", id, e)
        }
    }
}
