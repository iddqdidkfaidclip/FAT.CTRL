package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object YoutubeNewPipeDownloader {
    private val logger = LoggerFactory.getLogger(YoutubeNewPipeDownloader::class.java)
    private val initialized = AtomicBoolean(false)

    private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
    private const val MAX_HEIGHT = 720

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    data class Result(
        val file: Path,
        val title: String,
    )

    fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        NewPipe.init(
            NewPipeHttpDownloader(),
            Localization.fromLocale(Locale("ru", "RU")),
            ContentCountry("RU"),
        )
        logger.info("NewPipe Extractor initialized for YouTube fallback")
    }

    fun download(url: String, tempDir: Path, id: String): Result? {
        ensureInitialized()
        return try {
            val info = StreamInfo.getInfo(url)
            val title = info.name?.take(500) ?: "видео"
            val file = downloadBestStream(info, tempDir, id) ?: return null
            Result(file = file, title = title)
        } catch (e: Exception) {
            logger.warn("NewPipe download failed for {}: {}", url, e.message)
            null
        }
    }

    private fun downloadBestStream(info: StreamInfo, tempDir: Path, id: String): Path? {
        pickProgressiveStream(info)?.let { stream ->
            return downloadStream(stream.content, tempDir.resolve("dl-$id.mp4"))
        }

        val video = pickVideoOnlyStream(info) ?: return null
        val audio = pickAudioStream(info) ?: return null
        val videoPath = downloadStream(video.content, tempDir.resolve("dl-$id-video.mp4")) ?: return null
        val audioPath = downloadStream(audio.content, tempDir.resolve("dl-$id-audio.m4a")) ?: run {
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

    private fun pickProgressiveStream(info: StreamInfo): VideoStream? =
        info.videoStreams
            .orEmpty()
            .asSequence()
            .filter { !it.isVideoOnly() }
            .filter { streamHeight(it) <= MAX_HEIGHT }
            .maxWithOrNull(compareBy<VideoStream> { streamHeight(it) }.thenBy { it.bitrate })

    private fun pickVideoOnlyStream(info: StreamInfo): VideoStream? =
        info.videoOnlyStreams
            .orEmpty()
            .asSequence()
            .filter { streamHeight(it) <= MAX_HEIGHT }
            .maxWithOrNull(compareBy<VideoStream> { streamHeight(it) }.thenBy { it.bitrate })

    private fun pickAudioStream(info: StreamInfo): AudioStream? =
        info.audioStreams
            .orEmpty()
            .maxWithOrNull(compareBy<AudioStream> { it.averageBitrate })

    private fun streamHeight(stream: VideoStream): Int {
        val height = stream.height
        if (height > 0) return height
        return stream.getResolution()
            ?.removeSuffix("p")
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun downloadStream(url: String, target: Path): Path? {
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            NetscapeCookies.youtubeCookieHeader()?.let { builder.header("Cookie", it) }
            val request = builder.GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                logger.warn("NewPipe stream HTTP {} for {}", response.statusCode(), target.fileName)
                return null
            }
            val bytes = response.body()
            if (bytes.isEmpty()) {
                logger.warn("NewPipe stream empty for {}", target.fileName)
                return null
            }
            Files.write(target, bytes)
            target
        } catch (e: Exception) {
            logger.warn("NewPipe stream download failed for {}: {}", target.fileName, e.message)
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
            logger.warn("ffmpeg merge failed: {}", e.message)
            false
        }
    }
}
