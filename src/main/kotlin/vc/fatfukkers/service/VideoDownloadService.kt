package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

object VideoDownloadService {
    private val logger = LoggerFactory.getLogger(VideoDownloadService::class.java)

    /** Лимит Telegram Bot API для видео/документов */
    private const val TELEGRAM_MAX_BYTES = 45L * 1024 * 1024
    /** Лимит Telegram Bot API для sendPhoto */
    private const val TELEGRAM_PHOTO_MAX_BYTES = 10L * 1024 * 1024
    private const val TIMEOUT_SECONDS = 1800L
    private const val META_TIMEOUT_SECONDS = 30L
    private const val GALLERY_DL_TIMEOUT_SECONDS = 120L
    private const val DOWNLOAD_FAIL_PREFIX = "не смогла скачать, котик :("
    private const val SERVER_ADMIN_MENTION = "@iddqdidkfaidclip"

    private val ytdlpPath = EnvConfig.get("YTDLP_PATH") ?: "yt-dlp"
    private val galleryDlPath = EnvConfig.get("GALLERY_DL_PATH") ?: "gallery-dl"
    private val ffmpegPath = EnvConfig.get("FFMPEG_PATH")
    private val cookiesPath = EnvConfig.get("IG_COOKIES_PATH")
    private val youtubeCookiesPath = EnvConfig.get("YTDLP_YT_COOKIES_PATH")
    private val jsRuntime = EnvConfig.get("YTDLP_JS_RUNTIME")

    private val youtubePlayerClientAttempts = listOf(
        "web,mweb,android",
        "default,-android_sdkless",
        "ios",
    )
    private val tempDir: Path = Paths.get(EnvConfig.get("VIDEO_DOWNLOAD_DIR") ?: "./tmp/videos")

    /** Не начинать скачивание, если ролик длиннее (сек). По умолчанию 1 час. */
    private val maxDurationSec: Long =
        EnvConfig.get("VIDEO_MAX_DURATION_SEC")?.toLongOrNull() ?: 3600L

    /** Не начинать скачивание, если ожидаемый размер больше (байт). По умолчанию 200 МБ. */
    private val maxDownloadBytes: Long =
        EnvConfig.get("VIDEO_MAX_DOWNLOAD_MB")?.toLongOrNull()?.let { it * 1024 * 1024 }
            ?: 200L * 1024 * 1024

    enum class MediaKind { VIDEO, AUDIO, PHOTO }

    data class MediaItem(
        val file: Path,
        val mediaKind: MediaKind,
    )

    data class DownloadResult(
        val title: String,
        val items: List<MediaItem>,
    ) {
        val file: Path get() = items.single().file
        val mediaKind: MediaKind get() = items.single().mediaKind
        val audioOnly: Boolean get() = items.single().mediaKind == MediaKind.AUDIO
    }

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
            YoutubeNewPipeDownloader.ensureInitialized()
            val removed = cleanupTempDir()
            logger.info(
                "Video download: ytdlp={} galleryDl={} ffmpeg={} cookies={} jsRuntime={} tempDir={} " +
                    "maxDurationSec={} maxDownloadMb={} staleFilesRemoved={}",
                ytdlpPath,
                galleryDlPath,
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

    fun userError(reason: String, pingAdmin: Boolean = true): String =
        downloadFail(reason, pingAdmin)

    fun download(url: String): DownloadOutcome {
        if (!isYtdlpAvailable()) {
            return DownloadOutcome.Err(downloadFail("на сервере не установлен yt-dlp"))
        }

        val resolved = resolvedDownloadUrl(url)
        val metadata = fetchMetadata(resolved)
        validateBeforeDownload(metadata)?.let { return it }

        if (isInstagramPost(resolved)) {
            logger.info("Instagram post {}, downloading carousel", resolved)
            downloadInstagramCarousel(resolved, newId(), metadata.title)?.let { return it }
            logger.info("carousel download failed for {}, trying gallery-dl", resolved)
            downloadInstagramGallery(resolved, newId(), metadata.title)?.let { return it }
            return DownloadOutcome.Err(instagramFailureMessage(resolved))
        }

        if (isYoutube(resolved)) {
            logger.info("Trying NewPipe Extractor for {}", resolved)
            downloadYoutubeViaNewPipe(resolved, metadata.title)?.let { return it }

            logger.info("Trying Piped API for {}", resolved)
            downloadYoutubeViaPiped(resolved, metadata.title)?.let { return it }

            for ((index, playerClient) in youtubePlayerClientAttempts.withIndex()) {
                if (index > 0) {
                    logger.info("Retrying YouTube with player_client={} for {}", playerClient, resolved)
                }
                downloadVideo(
                    resolved,
                    format = videoFormat(resolved),
                    id = newId(),
                    title = metadata.title,
                    playerClient = playerClient,
                )?.let { return it }
            }

            logger.info("Retrying YouTube download with low quality for {}", resolved)
            for (playerClient in youtubePlayerClientAttempts) {
                downloadVideo(
                    resolved,
                    format = "18",
                    id = newId(),
                    title = metadata.title,
                    playerClient = playerClient,
                )?.let { return it }
            }
        } else {
            downloadVideo(
                resolved,
                format = videoFormat(resolved),
                id = newId(),
                title = metadata.title,
            )?.let { return it }

            if (isInstagram(resolved)) {
                return DownloadOutcome.Err(instagramFailureMessage(resolved))
            }
        }

        logger.info("Video download failed, trying audio fallback for {}", resolved)
        return downloadAudio(resolved, newId(), metadata.title)
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun isYtdlpAvailable(): Boolean = isCommandAvailable(ytdlpPath)

    private fun validateBeforeDownload(metadata: VideoMetadata): DownloadOutcome.Err? {
        metadata.durationSec?.let { duration ->
            if (duration > maxDurationSec) {
                val limitMin = (maxDurationSec + 59) / 60
                val videoMin = (duration + 59) / 60
                return DownloadOutcome.Err(
                    downloadFail("видео слишком длинное (~$videoMin мин) — качаю только до $limitMin мин", pingAdmin = false),
                )
            }
        }

        metadata.filesizeApprox?.let { size ->
            if (size > maxDownloadBytes) {
                val limitMb = maxDownloadBytes / (1024 * 1024)
                val sizeMb = (size + 1024 * 1024 - 1) / (1024 * 1024)
                return DownloadOutcome.Err(
                    downloadFail("видео слишком большое (~${sizeMb} МБ) — не качаю больше $limitMb МБ", pingAdmin = false),
                )
            }
        }

        return null
    }

    private fun videoFormat(url: String): String =
        if (isYoutube(url)) "b" else "best[filesize<45M]/best[height<=720]/best"

    private fun downloadYoutubeViaNewPipe(url: String, fallbackTitle: String): DownloadOutcome? {
        val id = newId()
        val result = YoutubeNewPipeDownloader.download(url, tempDir, id) ?: return null
        return validateDownloadedFile(result.file, id, result.title.ifBlank { fallbackTitle }, MediaKind.VIDEO)
    }

    private fun downloadYoutubeViaPiped(url: String, fallbackTitle: String): DownloadOutcome? {
        val id = newId()
        val result = YoutubePipedDownloader.download(url, tempDir, id) ?: return null
        return validateDownloadedFile(result.file, id, result.title.ifBlank { fallbackTitle }, MediaKind.VIDEO)
    }

    private fun validateDownloadedFile(
        file: Path,
        id: String,
        title: String,
        mediaKind: MediaKind,
    ): DownloadOutcome? {
        return try {
            val size = Files.size(file)
            if (size == 0L) {
                cleanupFiles(id)
                return DownloadOutcome.Err(downloadFail("скачался пустой файл"))
            }
            if (size > TELEGRAM_MAX_BYTES) {
                cleanupFiles(id)
                return DownloadOutcome.Err(
                    downloadFail("видео больше 45 МБ — Telegram не примет, попробуй короче ролик", pingAdmin = false),
                )
            }
            DownloadOutcome.Ok(
                DownloadResult(
                    title = title,
                    items = listOf(MediaItem(file, mediaKind)),
                ),
            )
        } catch (e: Exception) {
            cleanupFiles(id)
            logger.warn("Downloaded file validation failed for {}", file, e)
            null
        }
    }

    private fun downloadVideo(
        url: String,
        format: String,
        id: String,
        title: String,
        playerClient: String? = null,
    ): DownloadOutcome? {
        val outTemplate = tempDir.resolve("dl-$id.%(ext)s").toString()
        val args = baseArgs(outTemplate, url) +
            playlistArgs(url) +
            platformArgs(url, playerClient) +
            listOf(
                "-f", format,
                "--merge-output-format", "mp4",
                url,
            )
        val outcome = runDownload(args, url, id, mediaKind = MediaKind.VIDEO, title = title)
        return outcome as? DownloadOutcome.Ok
    }

    private fun downloadAudio(url: String, id: String, title: String): DownloadOutcome {
        val outTemplate = tempDir.resolve("dl-$id.%(ext)s").toString()
        val args = baseArgs(outTemplate, url) +
            playlistArgs(url) +
            platformArgs(url) +
            listOf(
                "-f", if (isYoutube(url)) "b/a" else "bestaudio/best",
                "-x",
                "--audio-format", "m4a",
                url,
            )
        return runDownload(args, url, id, mediaKind = MediaKind.AUDIO, title = title)
    }

    private fun downloadInstagramCarousel(url: String, id: String, title: String): DownloadOutcome? {
        val cookies = cookiesPath ?: return null
        val downloaded = InstagramImageService.downloadCarousel(
            url = url,
            cookiesPath = cookies,
            tempDir = tempDir,
            id = id,
        ) ?: return null

        val items = mutableListOf<MediaItem>()
        for (item in downloaded) {
            val size = Files.size(item.path)
            if (size == 0L) {
                cleanupFiles(id)
                return DownloadOutcome.Err(downloadFail("скачался пустой файл"))
            }

            val maxBytes = when (item.kind) {
                InstagramImageService.ItemKind.VIDEO -> TELEGRAM_MAX_BYTES
                InstagramImageService.ItemKind.PHOTO -> TELEGRAM_PHOTO_MAX_BYTES
            }
            if (size > maxBytes) {
                cleanupFiles(id)
                val limitMb = maxBytes / (1024 * 1024)
                return DownloadOutcome.Err(
                    downloadFail("файл больше ${limitMb} МБ — Telegram не примет", pingAdmin = false),
                )
            }

            val mediaKind = when (item.kind) {
                InstagramImageService.ItemKind.VIDEO -> MediaKind.VIDEO
                InstagramImageService.ItemKind.PHOTO -> MediaKind.PHOTO
            }
            items += MediaItem(item.path, mediaKind)
        }

        return DownloadOutcome.Ok(DownloadResult(title = title, items = items))
    }

    private fun downloadInstagramGallery(url: String, id: String, title: String): DownloadOutcome? {
        val command = resolveGalleryDlCommand() ?: run {
            logger.warn("gallery-dl not available (tried {})", galleryDlCandidates().joinToString())
            return null
        }

        val outDir = tempDir.resolve("gdl-$id")
        return try {
            Files.createDirectories(outDir)
            val args = galleryDlBaseArgs(command, outDir) + instagramPostUrl(url)
            val lastOutput = runProcessOutputLenient(args, GALLERY_DL_TIMEOUT_SECONDS)
            val downloaded = listGalleryDlFiles(outDir).sortedBy { it.fileName.toString() }
            if (downloaded.isEmpty()) {
                logger.warn(
                    "gallery-dl produced no file for {} output={}",
                    url,
                    ytdlpErrorSnippet(lastOutput),
                )
                return DownloadOutcome.Err(galleryDlErrorMessage(lastOutput))
            }

            val items = mutableListOf<MediaItem>()
            downloaded.forEachIndexed { index, source ->
                val ext = source.fileName.toString().substringAfterLast('.', "jpg")
                val target = tempDir.resolve("dl-$id-$index.$ext")
                Files.move(source, target)

                val size = Files.size(target)
                if (size == 0L) {
                    cleanupFiles(id)
                    return DownloadOutcome.Err(downloadFail("скачался пустой файл"))
                }

                val isPhoto = ext.lowercase() !in videoExtensions
                val maxBytes = if (isPhoto) TELEGRAM_PHOTO_MAX_BYTES else TELEGRAM_MAX_BYTES
                if (size > maxBytes) {
                    cleanupFiles(id)
                    val limitMb = maxBytes / (1024 * 1024)
                    return DownloadOutcome.Err(
                        downloadFail("файл больше ${limitMb} МБ — Telegram не примет", pingAdmin = false),
                    )
                }

                val mediaKind = if (isPhoto) MediaKind.PHOTO else MediaKind.VIDEO
                items += MediaItem(target, mediaKind)
            }
            deleteDirectoryQuietly(outDir)

            DownloadOutcome.Ok(DownloadResult(title = title, items = items))
        } catch (e: ProcessTimeoutException) {
            cleanupFiles(id)
            deleteDirectoryQuietly(outDir)
            DownloadOutcome.Err(downloadFail("скачивание слишком долгое, попробуй короче ролик", pingAdmin = false))
        } catch (e: ProcessFailedException) {
            val snippet = ytdlpErrorSnippet(e.output)
            logger.warn("gallery-dl exit {} for {}: {}", e.exitCode, url, snippet)
            cleanupFiles(id)
            deleteDirectoryQuietly(outDir)
            DownloadOutcome.Err(galleryDlErrorMessage(e.output))
        } catch (e: Exception) {
            logger.warn("gallery-dl failed for {}", url, e)
            cleanupFiles(id)
            deleteDirectoryQuietly(outDir)
            null
        }
    }

    private fun fetchMetadata(url: String): VideoMetadata {
        if (isInstagramPost(url)) {
            return VideoMetadata(title = "Instagram", durationSec = null, filesizeApprox = null)
        }

        val args = mutableListOf(
            ytdlpPath,
            "--no-warnings",
            "-s",
            "--print", "title:%(title)s",
            "--print", "duration:%(duration)s",
            "--print", "filesize:%(filesize_approx)s",
        ) + playlistArgs(url) + platformArgs(url) + url

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

    private fun platformArgs(url: String, playerClient: String? = null): List<String> {
        if (!isYoutube(url)) return emptyList()

        val client = playerClient ?: youtubePlayerClientAttempts.first()
        val args = mutableListOf(
            "--extractor-args", "youtube:player_client=$client",
            "--remote-components", "ejs:github",
        )
        jsRuntime?.let {
            args += listOf("--js-runtimes", it)
        }
        youtubeCookiesPath?.let {
            args += listOf("--cookies", it)
        }
        return args
    }

    private fun resolvedDownloadUrl(url: String): String = when {
        isYoutube(url) -> YoutubeUrlNormalizer.normalize(url)
        isInstagram(url) -> instagramPostUrl(url)
        else -> url
    }

    private fun isYoutube(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "youtu.be" || host.endsWith("youtube.com")
    }

    private fun isInstagram(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host.endsWith("instagram.com") || host == "instagr.am"
    }

    private fun isInstagramPost(url: String): Boolean {
        if (!isInstagram(url)) return false
        return try {
            URI(url).path.orEmpty().contains("/p/")
        } catch (_: Exception) {
            url.contains("/p/")
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** Instagram: img_index в URL — 1-based номер слайда карусели. */
    private fun instagramImgIndex(url: String): Int? {
        return try {
            val query = URI(url).rawQuery ?: return null
            query.split('&')
                .firstOrNull { it.startsWith("img_index=") }
                ?.substringAfter('=')
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun instagramPostUrl(url: String): String = try {
        val uri = URI(url)
        val path = uri.path.trimEnd('/')
        URI(uri.scheme, uri.authority, path, null, null).toString()
    } catch (_: Exception) {
        url
    }

    private fun playlistArgs(url: String): List<String> =
        if (isInstagram(url)) emptyList() else listOf("--no-playlist")

    private val videoExtensions = setOf("mp4", "mov", "webm", "mkv", "m4v")

    private fun baseArgs(output: String, url: String): MutableList<String> {
        val args = mutableListOf(
            ytdlpPath,
            "--no-warnings",
            "--newline",
            "--max-filesize", "45M",
            "-o", output,
        )
        ffmpegPath?.let {
            args += listOf("--ffmpeg-location", it)
        }
        if (isInstagram(url)) {
            cookiesPath?.let {
                args += listOf("--cookies", it)
            }
        }
        return args
    }

    private fun runDownload(
        args: List<String>,
        url: String,
        id: String,
        mediaKind: MediaKind,
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
                return DownloadOutcome.Err(downloadFail("скачался пустой файл"))
            }
            if (size > TELEGRAM_MAX_BYTES) {
                cleanupFiles(id)
                return DownloadOutcome.Err(downloadFail("видео больше 45 МБ — Telegram не примет, попробуй короче ролик", pingAdmin = false))
            }

            logger.info(
                "Downloaded {} bytes in {} ms mediaKind={} url={}",
                size,
                System.currentTimeMillis() - startedAt,
                mediaKind,
                url
            )
            DownloadOutcome.Ok(DownloadResult(title = title, items = listOf(MediaItem(file, mediaKind))))
        } catch (e: ProcessTimeoutException) {
            cleanupFiles(id)
            DownloadOutcome.Err(downloadFail("скачивание слишком долгое, попробуй короче ролик", pingAdmin = false))
        } catch (e: ProcessFailedException) {
            val snippet = ytdlpErrorSnippet(e.output)
            logger.warn("yt-dlp exit {} for {}: {}", e.exitCode, url, snippet)
            cleanupFiles(id)
            if (snippet.contains("max-filesize", ignoreCase = true) ||
                snippet.contains("File is larger than max-filesize", ignoreCase = true)
            ) {
                return DownloadOutcome.Err(downloadFail("видео больше 45 МБ — Telegram не примет, попробуй короче ролик", pingAdmin = false))
            }
            DownloadOutcome.Err(userMessageForYtdlp(snippet, url))
        } catch (e: Exception) {
            logger.warn("Download failed for {}", url, e)
            cleanupFiles(id)
            DownloadOutcome.Err(downloadFail("ошибка при скачивании — попробуй ещё раз"))
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

    private fun downloadFail(reason: String, pingAdmin: Boolean = true): String =
        if (pingAdmin) {
            "$DOWNLOAD_FAIL_PREFIX $SERVER_ADMIN_MENTION $reason"
        } else {
            "$DOWNLOAD_FAIL_PREFIX $reason"
        }

    private fun missingFileMessage(url: String, output: String): String {
        if (isInstagram(url)) {
            return instagramFailureMessage(url, output)
        }
        if (isYoutube(url)) {
            return youtubeFailureMessage(ytdlpErrorSnippet(output))
        }
        return downloadFail("ссылка недоступна")
    }

    private fun ytdlpErrorSnippet(output: String): String =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(5)
            .joinToString(" ")
            .take(300)

    private fun instagramFailureMessage(url: String, output: String = ""): String {
        if (needsInstagramCookies(output)) {
            return instagramCookiesMessage()
        }
        if (cookiesPath == null) {
            return instagramCookiesMessage()
        }
        if (instagramImgIndex(url) != null) {
            return downloadFail("карусель — обнови cookies (IG_COOKIES_PATH)")
        }
        if (resolveGalleryDlCommand() == null) {
            return downloadFail("для фото из карусели нужен gallery-dl на сервере")
        }
        return downloadFail("обнови cookies (IG_COOKIES_PATH)")
    }

    private fun instagramCookiesMessage(): String =
        downloadFail("нужны cookies (IG_COOKIES_PATH на сервере)")

    private fun galleryDlErrorMessage(output: String): String {
        if (needsInstagramCookies(output)) {
            return instagramCookiesMessage()
        }
        return downloadFail("карусель — обнови cookies (IG_COOKIES_PATH)")
    }

    private fun isCommandAvailable(command: List<String>): Boolean = try {
        val process = ProcessBuilder(command + "--version")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        finished && process.exitValue() == 0
    } catch (e: Exception) {
        logger.debug("Command not available: {}", command.joinToString(" "), e)
        false
    }

    private fun isCommandAvailable(command: String): Boolean = isCommandAvailable(listOf(command))

    private fun needsInstagramCookies(output: String): Boolean =
        output.contains("login", ignoreCase = true) ||
            output.contains("accounts/login", ignoreCase = true) ||
            output.contains("redirect to login", ignoreCase = true)

    private fun userMessageForYtdlp(snippet: String, url: String): String {
        if (isInstagram(url)) {
            return instagramFailureMessage(url, snippet)
        }
        if (!isYoutube(url) && snippet.contains("login", ignoreCase = true)) {
            return instagramCookiesMessage()
        }
        if (isYoutube(url)) {
            return youtubeFailureMessage(snippet)
        }
        return downloadFail("ссылка недоступна")
    }

    private fun youtubeFailureMessage(snippet: String): String {
        if (snippet.contains("JavaScript runtime", ignoreCase = true) ||
            snippet.contains("n challenge solving failed", ignoreCase = true) ||
            snippet.contains("challenge solver", ignoreCase = true)
        ) {
            return downloadFail("YouTube требует Deno — curl -fsSL https://deno.land/install.sh | sh")
        }
        if (snippet.contains("not a bot", ignoreCase = true) ||
            snippet.contains("Sign in to confirm", ignoreCase = true) ||
            snippet.contains("LOGIN_REQUIRED", ignoreCase = true)
        ) {
            return if (youtubeCookiesPath != null) {
                downloadFail("YouTube не принял cookies — экспортируй свежие youtube_cookies.txt")
            } else {
                downloadFail("YouTube заблокировал VPS — добавь YTDLP_YT_COOKIES_PATH (cookies из браузера)")
            }
        }
        if (snippet.contains("403", ignoreCase = true) || snippet.contains("Forbidden", ignoreCase = true)) {
            return downloadFail("YouTube отклонил скачивание — обнови yt-dlp и Deno")
        }
        if (snippet.contains("Video unavailable", ignoreCase = true) ||
            snippet.contains("Private video", ignoreCase = true) ||
            snippet.contains("This video is not available", ignoreCase = true)
        ) {
            return downloadFail("видео недоступно на YouTube", pingAdmin = false)
        }
        return downloadFail("YouTube не отдал видео — обнови yt-dlp на сервере")
    }

    private fun galleryDlCandidates(): List<List<String>> {
        val configured = galleryDlPath.trim()
        val candidates = mutableListOf<List<String>>()
        if (configured.isNotEmpty()) {
            candidates += if (configured.contains(' ')) {
                configured.split(Regex("\\s+"))
            } else {
                listOf(configured)
            }
        }
        candidates += listOf(
            listOf("gallery-dl"),
            listOf("/usr/bin/gallery-dl"),
            listOf("python3", "-m", "gallery_dl"),
        )
        return candidates.distinctBy { it.joinToString("\u0000") }
    }

    private fun resolveGalleryDlCommand(): List<String>? =
        galleryDlCandidates().firstOrNull { isCommandAvailable(it) }

    private fun galleryDlBaseArgs(command: List<String>, outDir: Path): MutableList<String> {
        val args = command.toMutableList()
        args += listOf("-D", outDir.toString(), "--no-mtime")
        cookiesPath?.let { args += listOf("--cookies", it) }
        return args
    }

    private fun runProcessOutputLenient(args: List<String>, timeoutSeconds: Long): String {
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
            logger.debug("Process exit {}: {}", process.exitValue(), ytdlpErrorSnippet(output))
        }
        return output
    }

    private fun listGalleryDlFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = mutableListOf<Path>()
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isRegularFile(file)) {
                    files.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private fun deleteDirectoryQuietly(dir: Path) {
        if (!Files.isDirectory(dir)) return
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deleteQuietly(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exc: java.io.IOException?): FileVisitResult {
                    deleteQuietly(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            logger.debug("Failed to cleanup gallery-dl dir {}", dir, e)
        }
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
                    .filter {
                        val name = it.fileName.toString()
                        name.startsWith("dl-$id") || name.startsWith("gdl-$id")
                    }
                    .forEach { path ->
                        if (Files.isDirectory(path)) {
                            deleteDirectoryQuietly(path)
                        } else {
                            deleteQuietly(path)
                        }
                    }
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
