package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import vc.fatfukkers.EnvConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class NewsItem(val title: String, val link: String, val pubDate: LocalDate? = null)

object NewsService {
    private val logger = LoggerFactory.getLogger(NewsService::class.java)

    private const val DEFAULT_MAX_ITEMS = 10
    private const val DEFAULT_TIMEOUT_SEC = 30L
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val TELEGRAM_MESSAGE_MAX = 4096
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    internal const val YANDEX_NEWS_SOURCE = "yandex://news"
    private const val DEFAULT_YANDEX_NEWS_URL =
        "https://dzen.ru/api/v3/launcher/news?clid=300&country_code=ru"

    private val yandexNewsUrl = EnvConfig.get("NEWS_YANDEX_API_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_YANDEX_NEWS_URL

    private val yandexTitleSkip = setOf(
        "ещё по теме",
        "сейчас в сми",
    )

    private val yandexStoryIdPattern = Regex(
        """(?:news/story/|%2Fnews%2Fstory%2F)([a-f0-9-]{36})""",
        RegexOption.IGNORE_CASE,
    )
    private val yandexTitlePattern = Regex("""\"title\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")

    private val maxItems = EnvConfig.get("NEWS_MAX_ITEMS")?.toIntOrNull()?.coerceIn(1, 15) ?: DEFAULT_MAX_ITEMS
    private val httpTimeoutSec = EnvConfig.get("NEWS_HTTP_TIMEOUT_SEC")?.toLongOrNull()?.coerceAtLeast(5)
        ?: DEFAULT_TIMEOUT_SEC

    private var cacheSlot: Long = -1
    private var cacheItems: List<NewsItem> = emptyList()

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(httpTimeoutSec))
        .build()

    private val archiveDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val rssPubDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    fun fetchTopHeadlines(zoneId: ZoneId = ZoneId.of("Europe/Moscow")): List<NewsItem> {
        val slot = System.currentTimeMillis() / CACHE_TTL_MS
        synchronized(this) {
            if (slot == cacheSlot && cacheItems.isNotEmpty()) {
                return cacheItems
            }
        }

        val items = loadHeadlines(zoneId)
        if (items.isNotEmpty()) {
            synchronized(this) {
                cacheSlot = slot
                cacheItems = items
            }
        }
        return items
    }

    private fun loadHeadlines(zoneId: ZoneId): List<NewsItem> {
        val collected = mutableListOf<NewsItem>()
        val seenTitles = mutableSetOf<String>()

        for (url in buildFeedSources(zoneId)) {
            if (collected.size >= maxItems) break
            val items = when {
                url == YANDEX_NEWS_SOURCE -> fetchYandexNews()
                else -> fetchRss(url)?.let { parseRssItems(it, zoneId) } ?: emptyList()
            }
            for (item in items) {
                val key = item.title.lowercase(Locale.ROOT)
                if (!seenTitles.add(key)) continue
                collected.add(item)
                if (collected.size >= maxItems) break
            }
        }

        return collected.take(maxItems)
    }

    internal fun buildFeedSources(zoneId: ZoneId = ZoneId.of("Europe/Moscow")): List<String> {
        val todayToken = LocalDate.now(zoneId).format(archiveDateFormatter)
        EnvConfig.get("NEWS_RSS_URLS")
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { urls ->
                return urls.map { url ->
                    url.replace("{date}", todayToken)
                }
            }

        return listOf(
            YANDEX_NEWS_SOURCE,
            "https://tass.ru/rss/v2.xml",
            "https://ria.ru/export/rss2/index.xml",
        )
    }

    internal fun primaryFeedUrl(): String = buildFeedSources().first()

    fun buildCommentsPrompt(items: List<NewsItem>): String = buildString {
        appendLine("Ниже ${items.size} актуальных топовых заголовков новостей прямо сейчас.")
        appendLine("Для каждого напиши ОДИН короткий комментарий от себя — одна строка, мило и игриво, со смайликом в конце.")
        appendLine("НЕ переписывай и НЕ цитируй заголовки — только комментарии.")
        appendLine("Ответ строго в формате (ровно ${items.size} строк):")
        items.indices.forEach { i ->
            appendLine("${i + 1}. комментарий")
        }
        appendLine()
        appendLine("Заголовки:")
        items.forEachIndexed { i, item ->
            appendLine("${i + 1}. ${item.title}")
        }
    }

    fun assembleDigest(items: List<NewsItem>, comments: List<String>): String {
        val intro = "котик, вот топ новостей прямо сейчас 📰"
        val body = buildString {
            items.forEachIndexed { i, item ->
                if (i > 0) appendLine()
                append("⚡ ").append(item.title)
                comments.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { comment ->
                    appendLine()
                    append("  ↳😺 ").append(comment.stripLeadingNumber())
                }
            }
        }
        return truncateMessage("$intro\n\n$body")
    }

    fun formatHeadlinesFallback(items: List<NewsItem>): String {
        val intro = "котик, вот топ новостей прямо сейчас 📰"
        val body = items.joinToString("\n\n") { "⚡ ${it.title}" }
        return truncateMessage("$intro\n\n$body")
    }

    internal fun parseComments(raw: String, expectedCount: Int): List<String>? {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.stripLeadingNumber() }
            .filter { it.isNotEmpty() }
        if (lines.size < expectedCount) return null
        return lines.take(expectedCount)
    }

    internal fun parseRssItems(xml: String, zoneId: ZoneId = ZoneId.of("Europe/Moscow")): List<NewsItem> {
        val itemPattern = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        return itemPattern.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = extractXmlTag(block, "title") ?: return@mapNotNull null
            val link = extractXmlTag(block, "link") ?: ""
            val pubDateRaw = extractXmlTag(block, "pubDate")
            val pubDate = pubDateRaw?.let { parsePubDate(it, zoneId) }
            NewsItem(
                title = decodeXmlEntities(title.trim()),
                link = link.trim(),
                pubDate = pubDate,
            )
        }.toList()
    }

    internal fun parsePubDate(pubDate: String, zoneId: ZoneId): LocalDate? {
        return try {
            ZonedDateTime.parse(pubDate.trim(), rssPubDateFormatter)
                .withZoneSameInstant(zoneId)
                .toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    internal fun parseYandexNewsJson(json: String): List<NewsItem> {
        val seenTitles = mutableSetOf<String>()
        val seenStoryIds = mutableSetOf<String>()
        val items = mutableListOf<NewsItem>()

        for (storyMatch in yandexStoryIdPattern.findAll(json)) {
            val storyId = storyMatch.groupValues[1]
            if (!seenStoryIds.add(storyId)) continue

            val lookbackStart = (storyMatch.range.first - 900).coerceAtLeast(0)
            val before = json.substring(lookbackStart, storyMatch.range.first)
            val titleRaw = yandexTitlePattern.findAll(before).lastOrNull()?.groupValues?.get(1) ?: continue
            val title = decodeJsonString(titleRaw).trim().replace('\u00A0', ' ')
            if (title.length < 15) continue
            if (title.lowercase(Locale.ROOT) in yandexTitleSkip) continue

            val titleKey = title.lowercase(Locale.ROOT)
            if (!seenTitles.add(titleKey)) continue

            items.add(
                NewsItem(
                    title = title,
                    link = "https://dzen.ru/news/story/$storyId",
                ),
            )
        }
        return items
    }

    private fun fetchYandexNews(): List<NewsItem> {
        val json = fetchHttpBody(yandexNewsUrl, acceptJson = true) ?: return emptyList()
        return parseYandexNewsJson(json)
    }

    private fun fetchRss(url: String): String? = fetchHttpBody(url, acceptJson = false)

    private fun fetchHttpBody(url: String, acceptJson: Boolean): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(httpTimeoutSec))
                .header("User-Agent", USER_AGENT)
                .apply {
                    if (acceptJson) {
                        header("Accept", "application/json")
                    }
                }
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("News fetch HTTP {} url={}", response.statusCode(), url)
                return null
            }
            response.body()
        } catch (e: Exception) {
            logger.warn("News fetch failed url={}", url, e)
            null
        }
    }

    private fun decodeJsonString(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> if (i + 5 < raw.length) {
                        raw.substring(i + 2, i + 6).toIntOrNull(16)?.toChar()?.let { sb.append(it) }
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(raw[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractXmlTag(block: String, tag: String): String? {
        val cdata = Regex("""<$tag><!\[CDATA\[(.*?)\]\]></$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)
        if (cdata != null) return cdata
        return Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)
    }

    private fun decodeXmlEntities(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("""&#(\d+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }

    private fun String.stripLeadingNumber(): String =
        replace(Regex("""^\d+[\.\):\-]\s*"""), "").trim()

    private fun truncateMessage(text: String): String =
        if (text.length <= TELEGRAM_MESSAGE_MAX) text
        else text.take(TELEGRAM_MESSAGE_MAX - 1) + "…"
}
