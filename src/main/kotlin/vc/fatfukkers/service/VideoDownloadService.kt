package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit

object VideoDownloadService {
    private val logger = LoggerFactory.getLogger(VideoDownloadService::class.java)

    /** Лимит Telegram Bot API */
    private const val TELEGRAM_MAX_BYTES = 45L * 1024 * 1024
    private const val TIMEOUT_SECONDS = 300L
    private const val META_TIMEOUT_SECONDS = 30L

    private val ytdlpPath = EnvConfig.get("YTDLP_PATH") ?: "yt-dlp"
    private val ffmpegPath = EnvConfig.get("FFMPEG_PATH")
    private val cookiesPath = EnvConfig.get("IG_COOKIES_PATH")
    private val jsRuntime = EnvConfig.get("YTDLP_JS_RUNTIME")
    private val tempDir: Path = Paths.get(EnvConfig.get("VIDEO_DOWNLOAD_DIR") ?: "./tmp/videos")

    /** Не начинать скачивание, если ролик длиннее (сек). По умолчанию 1 час. */
    private val maxDurationSec: Long =
        EnvConfig.get("VIDEO_MAX_DURATION_SEC")?.toLongOrNull() ?: 3600L

    /** Не начинать скачивание, если ожидаемый размер больше (байт). По умолчанию 200 МБ. */
    private val maxDownloadBytes: Long =
        EnvConfig.get("VIDEO_MAX_DOWNLOAD_MB")?.toLongOrNull()?.let { it * 1024 * 1024 }
            ?: 200L * 1024 * 1024

    data class DownloadResult(
        val file: Path,
        val title: String,
        val audioOnly: Boolean,
    )

    sealed class DownloadOutcome {
        data class Ok(val result: DownloadResult) : DownloadOutcome()
        data class Err(val message: String) : DownloadOutcome()
    }

    private data class VideoMetadata(
        val title: String,
        val durationSec: Long?,
        val filesizeApprox: Long?,
    )

    fun onStartup() {
        try {
            Files.createDirectories(tempDir)
            val removed = cleanupTempDir()
            logger.info(
                "Video download: ytdlp={} ffmpeg={} cookies={} jsRuntime={} tempDir={} " +
                    "maxDurationSec={} maxDownloadMb={} staleFilesRemoved={}",
                ytdlpPath,
                ffmpegPath ?: "(PATH)",
                cookiesPath ?: "(none)",
                jsRuntime ?: "(auto)",
                tempDir.toAbsolutePath(),
                maxDurationSec,
                maxDownloadBytes / (1024 * 1024),
                removed,
            )
        } catch (e: Exception) {
            logger.warn("Failed to init video temp dir {}", tempDir, e)
        }
    }

    fun download(url: String): DownloadOutcome {
        if (!isYtdlpAvailable()) {
            return DownloadOutcome.Err("на сервере не установлен yt-dlp — попроси Макса поставить")
        }

        val metadata = fetchMetadata(url)
        validateBeforeDownload(metadata)?.let { return it }

        downloadVideo(url, format = videoFormat(url), id = newId(), title = metadata.title)?.let { return it }

        if (isYoutube(url)) {
            logger.info("Retrying YouTube download with low quality for {}", url)
            downloadVideo(url, format = "18", id = newId(), title = metadata.title)?.let { return it }
        }

        logger.info("Video download failed, trying audio fallback for {}", url)
        return downloadAudio(url, newId(), metadata.title)
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun isYtdlpAvailable(): Boolean = try {
        val process = ProcessBuilder(ytdlpPath, "--version")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        finished && process.exitValue() == 0
    } catch (e: Exception) {
        logger.warn("yt-dlp not available at {}", ytdlpPath, e)
        false
    }

    private fun validateBeforeDownload(metadata: VideoMetadata): DownloadOutcome.Err? {
        metadata.durationSec?.let { duration ->
            if (duration > maxDurationSec) {
                val limitMin = (maxDurationSec + 59) / 60
                val videoMin = (duration + 59) / 60
                return DownloadOutcome.Err(
                    "видео слишком длинное (~$videoMin мин) — качаю только до $limitMin мин"
                )
            }
        }

        metadata.filesizeApprox?.let { size ->
            if (size > maxDownloadBytes) {
                val limitMb = maxDownloadBytes / (1024 * 1024)
                val sizeMb = (size + 1024 * 1024 - 1) / (1024 * 1024)
                return DownloadOutcome.Err(
                    "видео слишком большое (~${sizeMb} МБ) — не качаю больше $limitMb МБ"
                )
            }
        }

        return null
    }

    private fun videoFormat(url: String): String =
        if (isYoutube(url)) "b" else "best[filesize<45M]/best[height<=720]/best"

    private fun downloadVideo(url: String, format: String, id: String, title: String): DownloadOutcome? {
        val outTemplate = tempDir.resolve("dl-$id.%(ext)s").toString()
        val args = baseArgs(outTemplate) +
            platformArgs(url) +
            listOf(
                "-f", format,
                "--merge-output-format", "mp4",
                url,
            )
        val outcome = runDownload(args, url, id, audioOnly = false, title = title)
        return outcome as? DownloadOutcome.Ok
    }

    private fun downloadAudio(url: String, id: String, title: String): DownloadOutcome {
        val outTemplate = tempDir.resolve("dl-$id.%(ext)s").toString()
        val args = baseArgs(outTemplate) +
            platformArgs(url) +
            listOf(
                "-f", if (isYoutube(url)) "b/a" else "bestaudio/best",
                "-x",
                "--audio-format", "m4a",
                url,
            )
        return runDownload(args, url, id, audioOnly = true, title = title)
    }

    private fun fetchMetadata(url: String): VideoMetadata {
        val args = mutableListOf(
            ytdlpPath,
            "--no-playlist",
            "--no-warnings",
            "-s",
            "--print", "title:%(title)s",
            "--print", "duration:%(duration)s",
            "--print", "filesize:%(filesize_approx)s",
        ) + platformArgs(url) + url

        return try {
            val output = runProcessOutput(args, META_TIMEOUT_SECONDS)
            var title: String? = null
            var durationSec: Long? = null
            var filesizeApprox: Long? = null

            for (line in output.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("title:") -> title = trimmed.removePrefix("title:").trim()
                    trimmed.startsWith("duration:") -> durationSec = trimmed.removePrefix("duration:").trim().toLongOrNull()
                    trimmed.startsWith("filesize:") -> filesizeApprox = trimmed.removePrefix("filesize:").trim().toLongOrNull()
                }
            }

            VideoMetadata(
                title = title?.take(500) ?: "видео",
                durationSec = durationSec,
                filesizeApprox = filesizeApprox,
            )
        } catch (e: Exception) {
            logger.debug("Failed to fetch metadata for {}", url, e)
            VideoMetadata(title = "видео", durationSec = null, filesizeApprox = null)
        }
    }

    private fun platformArgs(url: String): List<String> {
        if (!isYoutube(url)) return emptyList()

        val args = mutableListOf(
            "--extractor-args", "youtube:player_client=android,web",
            "--remote-components", "ejs:github",
        )
        jsRuntime?.let {
            args += listOf("--js-runtimes", it)
        }
        return args
    }

    private fun isYoutube(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "youtu.be" || host.endsWith("youtube.com")
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun baseArgs(output: String): MutableList<String> {
        val args = mutableListOf(
            ytdlpPath,
            "--no-playlist",
            "--no-warnings",
            "--newline",
            "--max-filesize", "45M",
            "-o", output,
        )
        ffmpegPath?.let {
            args += listOf("--ffmpeg-location", it)
        }
        cookiesPath?.let {
            args += listOf("--cookies", it)
        }
        return args
    }

    private fun runDownload(
        args: List<String>,
        url: String,
        id: String,
        audioOnly: Boolean,
        title: String,
    ): DownloadOutcome {
        val startedAt = System.currentTimeMillis()
        return try {
            val output = runProcessOutput(args, TIMEOUT_SECONDS)

            val file = findDownloadedFile(id)
                ?: run {
                    logger.warn("yt-dlp produced no file for {} output={}", url, ytdlpErrorSnippet(output))
                    cleanupFiles(id)
                    return DownloadOutcome.Err(missingFileMessage(url, output))
                }

            val size = Files.size(file)
            if (size == 0L) {
                cleanupFiles(id)
                return DownloadOutcome.Err("скачался пустой файл")
            }
            if (size > TELEGRAM_MAX_BYTES) {
                cleanupFiles(id)
                return DownloadOutcome.Err("видео больше 45 МБ — Telegram не примет, попробуй короче ролик")
            }

            logger.info(
                "Downloaded {} bytes in {} ms audioOnly={} url={}",
                size,
                System.currentTimeMillis() - startedAt,
                audioOnly,
                url
            )
            DownloadOutcome.Ok(DownloadResult(file = file, title = title, audioOnly = audioOnly))
        } catch (e: ProcessTimeoutException) {
            cleanupFiles(id)
            DownloadOutcome.Err("скачивание слишком долгое, попробуй короче ролик")
        } catch (e: ProcessFailedException) {
            val snippet = ytdlpErrorSnippet(e.output)
            logger.warn("yt-dlp exit {} for {}: {}", e.exitCode, url, snippet)
            cleanupFiles(id)
            if (snippet.contains("max-filesize", ignoreCase = true) ||
                snippet.contains("File is larger than max-filesize", ignoreCase = true)
            ) {
                return DownloadOutcome.Err("видео больше 45 МБ — Telegram не примет, попробуй короче ролик")
            }
            DownloadOutcome.Err(userMessageForYtdlp(snippet, url))
        } catch (e: Exception) {
            logger.warn("Download failed for {}", url, e)
            cleanupFiles(id)
            DownloadOutcome.Err("ошибка при скачивании — попробуй ещё раз")
        }
    }

    private fun runProcessOutput(args: List<String>, timeoutSeconds: Long): String {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ProcessTimeoutException()
        }
        if (process.exitValue() != 0) {
            throw ProcessFailedException(process.exitValue(), output)
        }
        return output
    }

    private fun missingFileMessage(url: String, output: String): String {
        if (!isYoutube(url)) {
            return "не смогла скачать — ссылка недоступна"
        }
        val needsJs = output.contains("JavaScript runtime", ignoreCase = true) ||
            output.contains("jsc", ignoreCase = true)
        return if (needsJs) {
            "YouTube требует Deno на сервере — установи: curl -fsSL https://deno.land/install.sh | sh"
        } else {
            "не смогла скачать видео — проверь ffmpeg и права на $tempDir"
        }
    }

    private fun ytdlpErrorSnippet(output: String): String =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(5)
            .joinToString(" ")
            .take(300)

    private fun userMessageForYtdlp(snippet: String, url: String): String {
        if (!isYoutube(url) && snippet.contains("login", ignoreCase = true)) {
            return "не смогла скачать Instagram — нужны cookies (IG_COOKIES_PATH на сервере)"
        }
        if (snippet.contains("JavaScript runtime", ignoreCase = true)) {
            return "YouTube требует Deno на сервере — установи: curl -fsSL https://deno.land/install.sh | sh"
        }
        if (snippet.contains("403", ignoreCase = true) || snippet.contains("Forbidden", ignoreCase = true)) {
            return "YouTube отклонил скачивание — установи Deno и ffmpeg на сервере"
        }
        return "не смогла скачать — ссылка недоступна"
    }

    private fun findDownloadedFile(id: String): Path? {
        if (!Files.isDirectory(tempDir)) return null
        Files.list(tempDir).use { stream ->
            return stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("dl-$id.") && Files.isRegularFile(path)
                }
                .findFirst()
                .orElse(null)
        }
    }

    private fun cleanupFiles(id: String) {
        if (!Files.isDirectory(tempDir)) return
        try {
            Files.list(tempDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("dl-$id") }
                    .forEach { deleteQuietly(it) }
            }
        } catch (e: Exception) {
            logger.debug("Failed to cleanup temp files for {}", id, e)
        }
    }

    private fun cleanupTempDir(): Int {
        if (!Files.isDirectory(tempDir)) return 0
        var removed = 0
        try {
            Files.list(tempDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { path ->
                        if (deleteQuietly(path)) removed++
                    }
            }
        } catch (e: Exception) {
            logger.warn("Failed to cleanup video temp dir {}", tempDir, e)
        }
        return removed
    }

    fun deleteQuietly(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (e: Exception) {
        logger.debug("Failed to delete temp file {}", path, e)
        false
    }

    private class ProcessTimeoutException : RuntimeException()
    private class ProcessFailedException(val exitCode: Int, val output: String) : RuntimeException()
}
