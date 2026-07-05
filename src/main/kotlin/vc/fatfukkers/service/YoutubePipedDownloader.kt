package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

object YoutubePipedDownloader {
    private val logger = LoggerFactory.getLogger(YoutubePipedDownloader::class.java)

    private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
    private const val MAX_HEIGHT = 720

    private val defaultApiBases = listOf(
        "https://pipedapi.syncpundit.io",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.tokhmi.xyz",
        "https://api-piped.mha.fi",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.leptons.xyz",
        "https://piped-api.lunar.icu",
        "https://ytapi.dc09.ru",
        "https://pipedapi.colinslegacy.com",
        "https://yapi.vyper.me",
        "https://api.looleh.xyz",
        "https://piped-api.cfe.re",
        "https://pipedapi.r4fo.com",
        "https://api.piped.yt",
        "https://pipedapi.kavin.rocks",
    )

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    data class Result(
        val file: Path,
        val title: String,
    )

    fun download(url: String, tempDir: Path, id: String): Result? {
        val videoId = YoutubeUrlNormalizer.extractVideoId(url) ?: return null
        val apiBases = configuredApiBases()

        for (apiBase in apiBases) {
            val json = fetchStreams(apiBase, videoId) ?: continue
            val title = extractJsonString(json, "title") ?: "видео"
            val file = downloadFromPipedJson(json, tempDir, id) ?: continue
            logger.info("Piped download ok via {} for {}", apiBase, videoId)
            return Result(file = file, title = title.take(500))
        }
        return null
    }

    private fun configuredApiBases(): List<String> {
        val configured = EnvConfig.get("PIPED_API_URL")?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            return listOf(configured.trimEnd('/')) + defaultApiBases
        }
        return defaultApiBases
    }

    private fun fetchStreams(apiBase: String, videoId: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${apiBase.trimEnd('/')}/streams/$videoId"))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Mozilla/5.0 (compatible; FATCTRLBOT/1.0)")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.debug("Piped {} returned HTTP {}", apiBase, response.statusCode())
                return null
            }
            val body = response.body()
            if (!body.contains("\"videoStreams\"") && !body.contains("\"audioStreams\"")) {
                logger.debug("Piped {} returned unexpected body for {}", apiBase, videoId)
                return null
            }
            body
        } catch (e: Exception) {
            logger.debug("Piped {} failed for {}: {}", apiBase, videoId, e.message)
            null
        }
    }

    private fun downloadFromPipedJson(json: String, tempDir: Path, id: String): Path? {
        val progressive = parseVideoStreams(json).firstOrNull { !it.videoOnly && it.height <= MAX_HEIGHT }
            ?: parseVideoStreams(json).firstOrNull { !it.videoOnly }
        if (progressive != null) {
            return downloadUrl(progressive.url, tempDir.resolve("dl-$id.mp4"))
        }

        val video = parseVideoStreams(json).firstOrNull { it.videoOnly && it.height <= MAX_HEIGHT }
            ?: return null
        val audio = parseAudioStreams(json).maxByOrNull { it.bitrate } ?: return null
        val videoPath = downloadUrl(video.url, tempDir.resolve("dl-$id-video.mp4")) ?: return null
        val audioPath = downloadUrl(audio.url, tempDir.resolve("dl-$id-audio.m4a")) ?: run {
            Files.deleteIfExists(videoPath)
            return null
        }
        val output = tempDir.resolve("dl-$id.mp4")
        return if (mergeWithFfmpeg(videoPath, audioPath, output)) {
            Files.deleteIfExists(videoPath)
            Files.deleteIfExists(audioPath)
            output
        } else {
            Files.deleteIfExists(videoPath)
            Files.deleteIfExists(audioPath)
            null
        }
    }

    private data class PipedVideoStream(
        val url: String,
        val height: Int,
        val videoOnly: Boolean,
    )

    private data class PipedAudioStream(
        val url: String,
        val bitrate: Int,
    )

    private fun parseVideoStreams(json: String): List<PipedVideoStream> {
        val section = json.substringAfter("\"videoStreams\"", missingDelimiterValue = "")
            .substringBefore("\"audioStreams\"", missingDelimiterValue = "")
        return streamObjectRegex.findAll(section).mapNotNull { match ->
            val chunk = match.value
            val url = extractJsonString(chunk, "url") ?: return@mapNotNull null
            val height = extractJsonInt(chunk, "height") ?: 0
            val videoOnly = chunk.contains("\"videoOnly\":true")
            PipedVideoStream(url, height, videoOnly)
        }.toList()
    }

    private fun parseAudioStreams(json: String): List<PipedAudioStream> {
        val section = json.substringAfter("\"audioStreams\"", missingDelimiterValue = "")
            .substringBefore("\"subtitles\"", missingDelimiterValue = "")
            .ifBlank { json.substringAfter("\"audioStreams\"", missingDelimiterValue = "") }
        return streamObjectRegex.findAll(section).mapNotNull { match ->
            val chunk = match.value
            val url = extractJsonString(chunk, "url") ?: return@mapNotNull null
            val bitrate = extractJsonInt(chunk, "bitrate") ?: 0
            PipedAudioStream(url, bitrate)
        }.toList()
    }

    private val streamObjectRegex = Regex("""\{[^{}]*"url"\s*:\s*"[^"]+"[^{}]*\}""")

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val raw = pattern.find(json)?.groupValues?.get(1) ?: return null
        return decodeJsonString(raw)
    }

    private fun extractJsonInt(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun decodeJsonString(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '"' -> append('"')
                    '\\' -> append('\\')
                    '/' -> append('/')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> if (i + 5 < raw.length) {
                        append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    } else append(raw[i + 1])
                    else -> append(raw[i + 1])
                }
                i += 2
            } else {
                append(raw[i])
                i++
            }
        }
    }

    private fun downloadUrl(url: String, target: Path): Path? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) return null
            val bytes = response.body()
            if (bytes.isEmpty()) return null
            Files.write(target, bytes)
            target
        } catch (e: Exception) {
            logger.debug("Piped stream download failed: {}", e.message)
            null
        }
    }

    private fun mergeWithFfmpeg(video: Path, audio: Path, output: Path): Boolean {
        val ffmpeg = EnvConfig.get("FFMPEG_PATH") ?: "ffmpeg"
        return try {
            val process = ProcessBuilder(
                ffmpeg,
                "-y",
                "-i", video.toString(),
                "-i", audio.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                output.toString(),
            )
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0 && Files.exists(output) && Files.size(output) > 0L
        } catch (e: Exception) {
            false
        }
    }
}
