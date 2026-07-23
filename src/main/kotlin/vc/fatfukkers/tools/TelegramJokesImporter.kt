package vc.fatfukkers.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

object TelegramJokesImporter {
    private val json = Json { ignoreUnknownKeys = true }

    private val adMarkers = listOf(
        "http://", "https://", "t.me/", "telegram.me/",
        "подписывай", "подпис", "реклам", "партнер", "розыгрыш", "конкурс",
        "промокод", "скидк", "донат", "ваканси",
        "сотрудничеств", "переходи", "казино",
        "взято из:", "читать продолжение", "залетай к нам",
        "инфоцыган", "рабочую схему",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size !in 1..2) {
            println("Usage: TelegramJokesImporter <export_dir_or_file> [output_file]")
            println("Example: TelegramJokesImporter '/path/ChatExport' src/main/resources/jokes.txt")
            return
        }

        val source = Paths.get(args[0])
        val output = if (args.size == 2) {
            Paths.get(args[1])
        } else {
            Paths.get("src/main/resources/jokes.txt")
        }

        val files = collectExportFiles(source)
        if (files.isEmpty()) {
            println("No Telegram export files found in: $source")
            return
        }

        val extracted = files.flatMap { file ->
            when (file.extension.lowercase()) {
                "html" -> readHtmlMessageTexts(file)
                else -> readJsonMessageTexts(file)
            }
        }
        val cleaned = extracted.map(::normalizeText).filter { it.isNotBlank() }
        val jokes = cleaned.filter(::looksLikeJoke).distinct()

        Files.createDirectories(output.parent)
        Files.write(
            output,
            jokes.joinToString(separator = "\n---\n").plus("\n").toByteArray(StandardCharsets.UTF_8),
        )

        println("Processed files: ${files.size}")
        println("Raw text messages: ${extracted.size}")
        println("Candidate jokes: ${jokes.size}")
        println("Saved to: ${output.toAbsolutePath()}")
    }

    private fun collectExportFiles(path: Path): List<Path> {
        if (path.isRegularFile()) return listOf(path)
        if (!Files.isDirectory(path)) return emptyList()

        Files.list(path).use { stream ->
            return stream
                .filter { p ->
                    p.isRegularFile() && (
                        p.extension.equals("json", ignoreCase = true) ||
                            p.extension.equals("js", ignoreCase = true) ||
                            (p.extension.equals("html", ignoreCase = true) && p.name.startsWith("messages"))
                        )
                }
                .sorted(compareBy<Path> { it.name })
                .toList()
        }
    }

    private fun readHtmlMessageTexts(file: Path): List<String> {
        val html = Files.readString(file, StandardCharsets.UTF_8)
        val chunks = html.split(Regex("""<div class="message """))
        return chunks.mapNotNull { chunk ->
            if (chunk.startsWith("service")) return@mapNotNull null
            if (!chunk.startsWith("default")) return@mapNotNull null
            if (chunk.contains("forwarded body")) return@mapNotNull null
            val textStart = chunk.indexOf("""<div class="text">""")
            if (textStart == -1) return@mapNotNull null
            val contentStart = textStart + """<div class="text">""".length
            val textEnd = chunk.indexOf("</div>", contentStart)
            if (textEnd == -1) return@mapNotNull null
            htmlToPlainText(chunk.substring(contentStart, textEnd))
        }
    }

    private fun htmlToPlainText(html: String): String {
        var text = html
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?strong>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""</a>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<[^>]+>"""), "")
        text = decodeHtmlEntities(text)
        return text.trim()
    }

    private fun decodeHtmlEntities(text: String): String =
        text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }

    private fun readJsonMessageTexts(file: Path): List<String> {
        val raw = Files.readString(file, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) return emptyList()

        val jsonBody = toJsonBody(raw) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(jsonBody) }.getOrNull() ?: return emptyList()
        val messages = root.jsonObject["messages"]?.jsonArray ?: return emptyList()

        return messages.mapNotNull { msg ->
            val obj = msg as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") return@mapNotNull null
            if (obj["from"] == null && obj["from_id"] == null) return@mapNotNull null
            if (obj["forwarded_from"] != null) return@mapNotNull null
            if (obj["media_type"]?.jsonPrimitive?.contentOrNull == "video_file") return@mapNotNull null
            if (obj["photo"] != null || obj["file"] != null || obj["sticker_emoji"] != null) return@mapNotNull null
            if (obj["edited"]?.jsonPrimitive?.booleanOrNull == true) {
                // Keep edited messages too; no-op branch for readability.
            }
            val text = extractTextField(obj["text"]) ?: return@mapNotNull null
            text.takeIf { it.isNotBlank() }
        }
    }

    private fun toJsonBody(raw: String): String? {
        if (raw.startsWith("{")) return raw
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun extractTextField(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.joinToString(separator = "") { extractTextField(it).orEmpty() }
        is JsonObject -> {
            val typedText = element["text"]?.jsonPrimitive?.contentOrNull
            if (!typedText.isNullOrBlank()) typedText else null
        }
    }

    private fun normalizeText(text: String): String =
        text.replace("\u00A0", " ")
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun looksLikeJoke(text: String): Boolean {
        if (text.length !in 25..1500) return false
        val lower = text.lowercase()
        if (adMarkers.any { lower.contains(it) }) return false
        if (Regex("""@\w{3,}""").containsMatchIn(text)) return false
        if (lower.startsWith("—") || lower.startsWith("-")) return false
        if (Regex("""^[\p{Punct}\s\d]+$""").matches(text)) return false
        if (Regex("""^[а-яёa-z]{1,18}$""", RegexOption.IGNORE_CASE).matches(lower)) return false

        var score = 0
        if (text.contains('\n')) score += 1
        if (text.contains("—") || text.contains(":")) score += 1
        if (text.contains("— ") || text.contains("- ")) score += 1
        if (Regex("""[!?]\s*$""").containsMatchIn(text)) score += 1
        if (Regex("""\b(муж|жена|врач|психолог|блондин|теща|программист|бармен|штирлиц)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) {
            score += 1
        }
        if (Regex("""\b(скидка|подпишись|промокод|акция|реклама)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            score -= 3
        }
        return score >= 1
    }
}
