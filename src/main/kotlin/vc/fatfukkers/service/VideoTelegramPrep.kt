package vc.fatfukkers.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Мобильный Telegram для bot-видео часто берёт width/height из sendVideo,
 * а десктоп — из самого файла. Поэтому в API можно отдавать только пиксельные
 * размеры файла с SAR=1 и без rotation; иначе на телефоне «сплющивает».
 */
object VideoTelegramPrep {
    private val logger = LoggerFactory.getLogger(VideoTelegramPrep::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_SECONDS = 600L

    data class Info(
        val width: Int,
        val height: Int,
        val durationSec: Int?,
    )

    data class Prepared(
        val file: Path,
        val info: Info?,
    )

    fun prepareForTelegram(file: Path, forceNormalize: Boolean = false): Prepared {
        val probe = probe(file)
        if (probe == null) {
            return Prepared(file, null)
        }

        val needsFix = forceNormalize || probe.needsAspectFix
        if (!needsFix) {
            // Без перекодирования — только coded size, без SAR/rotation-математики.
            return Prepared(file, probe.telegramInfoOrNull())
        }

        val normalized = normalize(file)
        if (normalized == null) {
            // Лучше не слать угаданные width/height: мобильный клиент им слепо верит.
            logger.warn(
                "Normalize failed for {}, sending without width/height (mobile may distort)",
                file.fileName,
            )
            return Prepared(file, null)
        }

        val fixedProbe = probe(normalized)
        if (fixedProbe == null) {
            return Prepared(normalized, null)
        }
        if (fixedProbe.needsAspectFix) {
            logger.warn(
                "Video still has SAR/rotation after normalize {}: sar={}:{} rot={}",
                normalized.fileName,
                fixedProbe.sarNum,
                fixedProbe.sarDen,
                fixedProbe.rotation,
            )
            return Prepared(normalized, null)
        }
        return Prepared(normalized, fixedProbe.telegramInfoOrNull())
    }

    private data class Probe(
        val width: Int,
        val height: Int,
        val durationSec: Int?,
        val sarNum: Int,
        val sarDen: Int,
        val rotation: Int,
    ) {
        val needsAspectFix: Boolean
            get() = normalizedRotation != 0 || !isSquareSar

        private val normalizedRotation: Int
            get() {
                val r = ((rotation % 360) + 360) % 360
                return if (r > 180) r - 360 else r
            }

        private val isSquareSar: Boolean
            get() = sarDen == 0 || sarNum == 0 || sarNum == sarDen

        /** Размеры для Bot API: только пиксели контейнера, без SAR/rotation swap. */
        fun telegramInfoOrNull(): Info? {
            if (needsAspectFix) return null
            if (width <= 0 || height <= 0) return null
            return Info(width = width, height = height, durationSec = durationSec)
        }
    }

    private fun probe(file: Path): Probe? {
        val ffprobe = ffprobePath()
        // -show_streams совместим с ffmpeg 4.4 (на сервере); stream_side_data в show_entries — нет.
        return try {
            val process = ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_streams",
                "-show_format",
                "-of", "json",
                file.toString(),
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("ffprobe timed out for {}", file.fileName)
                return null
            }
            if (process.exitValue() != 0) {
                logger.warn("ffprobe exit {} for {}: {}", process.exitValue(), file.fileName, output.take(200))
                return null
            }
            parseProbe(output)?.also {
                logger.info(
                    "ffprobe {}: {}x{} sar={}:{} rot={} dur={}",
                    file.fileName,
                    it.width,
                    it.height,
                    it.sarNum,
                    it.sarDen,
                    it.rotation,
                    it.durationSec,
                )
            }
        } catch (e: Exception) {
            logger.warn("ffprobe failed for {}: {}", file.fileName, e.message)
            null
        }
    }

    private fun parseProbe(output: String): Probe? {
        val root = try {
            json.parseToJsonElement(output).jsonObject
        } catch (e: Exception) {
            logger.warn("ffprobe JSON parse failed: {}", e.message)
            return null
        }

        val stream = root["streams"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it.string("codec_type") == "video" || it.int("width") != null }
            ?: return null
        val width = stream.int("width") ?: return null
        val height = stream.int("height") ?: return null
        if (width <= 0 || height <= 0) return null

        val (sarNum, sarDen) = parseRatio(stream.string("sample_aspect_ratio")) ?: (1 to 1)
        val rotation = stream["tags"]?.jsonObject?.string("rotate")?.toIntOrNull()
            ?: stream["side_data_list"]?.jsonArray
                ?.mapNotNull { el ->
                    val obj = el.jsonObject
                    obj.string("rotation")?.toDoubleOrNull()?.roundToInt()
                        ?: obj.int("rotation")
                }
                ?.firstOrNull()
            ?: 0

        val durationSec = listOfNotNull(
            stream.string("duration")?.toDoubleOrNull(),
            root["format"]?.jsonObject?.string("duration")?.toDoubleOrNull(),
        ).firstOrNull()?.roundToInt()?.takeIf { it > 0 }

        return Probe(
            width = width,
            height = height,
            durationSec = durationSec,
            sarNum = sarNum,
            sarDen = sarDen,
            rotation = rotation,
        )
    }

    private fun normalize(file: Path): Path? {
        val ffmpeg = EnvConfig.get("FFMPEG_PATH") ?: "ffmpeg"
        val out = file.resolveSibling(
            file.fileName.toString().substringBeforeLast('.') + "-norm.mp4",
        )
        return try {
            // Фильтр включает autorotate: rotation «впекается» в пиксели, SAR → 1:1.
            val process = ProcessBuilder(
                ffmpeg,
                "-y",
                "-i", file.toString(),
                "-vf", "scale=trunc(iw*sar/2)*2:trunc(ih/2)*2,setsar=1",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k",
                "-ac", "2",
                "-movflags", "+faststart",
                "-metadata:s:v:0", "rotate=0",
                out.toString(),
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Files.deleteIfExists(out)
                logger.warn("ffmpeg normalize timed out for {}", file.fileName)
                return null
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0L) {
                Files.deleteIfExists(out)
                logger.warn(
                    "ffmpeg normalize failed for {}: {}",
                    file.fileName,
                    output.lines().lastOrNull { it.isNotBlank() }?.take(300),
                )
                return null
            }

            Files.move(out, file, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Normalized video aspect for {}", file.fileName)
            file
        } catch (e: Exception) {
            Files.deleteIfExists(out)
            logger.warn("ffmpeg normalize error for {}: {}", file.fileName, e.message)
            null
        }
    }

    private fun ffprobePath(): String {
        val ffmpeg = EnvConfig.get("FFMPEG_PATH") ?: "ffmpeg"
        return when {
            ffmpeg.endsWith("ffmpeg") -> ffmpeg.removeSuffix("ffmpeg") + "ffprobe"
            ffmpeg.endsWith("ffmpeg.exe") -> ffmpeg.removeSuffix("ffmpeg.exe") + "ffprobe.exe"
            else -> "ffprobe"
        }
    }

    private fun parseRatio(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank() || raw.equals("N/A", ignoreCase = true)) return null
        val parts = raw.split(':', '/')
        if (parts.size != 2) return null
        val num = parts[0].toIntOrNull() ?: return null
        val den = parts[1].toIntOrNull() ?: return null
        if (num <= 0 || den <= 0) return null
        return num to den
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
