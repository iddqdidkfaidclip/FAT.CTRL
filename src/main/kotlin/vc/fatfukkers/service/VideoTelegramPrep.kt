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
 * Telegram часто ломает пропорции, если у видео неквадратный SAR / rotation
 * или если в sendVideo не передать width/height.
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
            return Prepared(file, probe.displayInfo())
        }

        val normalized = normalize(file) ?: return Prepared(file, probe.displayInfo())
        val fixedProbe = probe(normalized) ?: return Prepared(normalized, probe.displayInfo())
        return Prepared(normalized, fixedProbe.displayInfo())
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
            get() = rotation % 360 != 0 || (sarDen != 0 && !(sarNum == sarDen || sarNum == 0))

        fun displayInfo(): Info {
            val displayW = if (sarDen > 0 && sarNum > 0) {
                (width.toDouble() * sarNum / sarDen).roundToInt().coerceAtLeast(1)
            } else {
                width
            }
            val displayH = height
            val (w, h) = when (rotation % 360) {
                90, 270, -90, -270 -> displayH to displayW
                else -> displayW to displayH
            }
            return Info(width = even(w), height = even(h), durationSec = durationSec)
        }
    }

    private fun probe(file: Path): Probe? {
        val ffprobe = ffprobePath()
        return try {
            val process = ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries",
                "stream=width,height,duration,sample_aspect_ratio" +
                    ":stream_tags=rotate:stream_side_data=rotation:format=duration",
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
            parseProbe(output)
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

        val stream = root["streams"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val width = stream.int("width") ?: return null
        val height = stream.int("height") ?: return null
        if (width <= 0 || height <= 0) return null

        val (sarNum, sarDen) = parseRatio(stream.string("sample_aspect_ratio")) ?: (1 to 1)
        val rotation = stream["tags"]?.jsonObject?.string("rotate")?.toIntOrNull()
            ?: stream["side_data_list"]?.jsonArray
                ?.mapNotNull { it.jsonObject.string("rotation")?.toDoubleOrNull()?.roundToInt() }
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
            // scale учитывает SAR; при re-encode ffmpeg применяет rotation → квадратные пиксели
            val process = ProcessBuilder(
                ffmpeg,
                "-y",
                "-i", file.toString(),
                "-vf", "scale=trunc(iw*sar/2)*2:trunc(ih/2)*2,setsar=1",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
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

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
