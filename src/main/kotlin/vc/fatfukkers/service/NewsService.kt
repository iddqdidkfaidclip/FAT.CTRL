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

enum class NewsRegion(val zoneId: ZoneId) {
    RU(ZoneId.of("Europe/Moscow")),
    UA(ZoneId.of("Europe/Kiev")),
}

object NewsService {
    private val logger = LoggerFactory.getLogger(NewsService::class.java)

    private const val DEFAULT_MAX_ITEMS = 10
    private const val DEFAULT_TIMEOUT_SEC = 30L
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val TELEGRAM_MESSAGE_MAX = 4096
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    internal const val YANDEX_NEWS_SOURCE = "yandex://news"
    private const val DEFAULT_YANDEX_NEWS_URL_RU =
        "https://dzen.ru/api/v3/launcher/news?clid=300&country_code=ru"
    private const val DEFAULT_GOOGLE_NEWS_URL_UA =
        "https://news.google.com/rss?hl=uk&gl=UA&ceid=UA:uk"

    private val yandexNewsUrlRu = EnvConfig.get("NEWS_YANDEX_API_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_YANDEX_NEWS_URL_RU
    private val googleNewsUrlUa = EnvConfig.get("NEWS_UA_GOOGLE_RSS_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_GOOGLE_NEWS_URL_UA

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

    private val cacheByRegion = mutableMapOf<NewsRegion, Pair<Long, List<NewsItem>>>()

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(httpTimeoutSec))
        .build()

    private val archiveDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val rssPubDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    fun fetchTopHeadlines(region: NewsRegion = NewsRegion.RU): List<NewsItem> {
        val slot = System.currentTimeMillis() / CACHE_TTL_MS
        synchronized(this) {
            val cached = cacheByRegion[region]
            if (cached != null && cached.first == slot && cached.second.isNotEmpty()) {
                return cached.second
            }
        }

        val items = loadHeadlines(region)
        if (items.isNotEmpty()) {
            synchronized(this) {
                cacheByRegion[region] = slot to items
            }
        }
        return items
    }

    private fun loadHeadlines(region: NewsRegion): List<NewsItem> {
        val zoneId = region.zoneId
        val collected = mutableListOf<NewsItem>()
        val seenTitles = mutableSetOf<String>()

        for (url in buildFeedSources(region)) {
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

    internal fun buildFeedSources(region: NewsRegion = NewsRegion.RU): List<String> {
        val todayToken = LocalDate.now(region.zoneId).format(archiveDateFormatter)
        val envKey = when (region) {
            NewsRegion.RU -> "NEWS_RSS_URLS"
            NewsRegion.UA -> "NEWS_UA_RSS_URLS"
        }
        EnvConfig.get(envKey)
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { urls ->
                return urls.map { url ->
                    url.replace("{date}", todayToken)
                }
            }

        return when (region) {
            NewsRegion.RU -> listOf(
                YANDEX_NEWS_SOURCE,
                "https://tass.ru/rss/v2.xml",
                "https://ria.ru/export/rss2/index.xml",
            )
            NewsRegion.UA -> listOf(
                googleNewsUrlUa,
                "https://www.unian.ua/rss/news.xml",
                "https://www.pravda.com.ua/rss/",
            )
        }
    }

    internal fun primaryFeedUrl(region: NewsRegion = NewsRegion.RU): String = buildFeedSources(region).first()

    private val commentFallbacks = listOf(
        "± ого, следим за развитием 🩷",
        "± интересно, что будет дальше 😳",
        "± ну и новости, котик 🙈",
        "± смотрим внимательно 👀",
        "± вау, не ожидала такого 🫣",
        "± пахнет сюжетом на три сезона подряд 🎬",
        "± это как понедельник, только громче 💥",
        "± новость бодрая, аж чай остыл в шоке ☕",
        "± у меня бровь сама поднялась от этого 🤨",
        "± опять мир решил удивить без предупреждения 🎭",
        "± это звучит как очень плохая примета 😬",
        "± если это правда, день будет насыщенный 🌀",
        "± я бы села, но уже и так сижу 🪑",
        "± уровень драмы: соседи слушают через стену 🎧",
        "± план простой: дышим и читаем дальше 🫶",
        "± ощущение будто сценарий писал дедлайн 🕒",
        "± так, кто нажал кнопку хаоса снова 🧨",
        "± спокойно дышим, паника сегодня по расписанию 😮‍💨",
        "± ну вот, вселенная опять импровизирует 🎲",
        "± это новости или трейлер к апокалипсису 😵",
        "± кто-то явно решил повысить ставки резко ♟️",
        "± эх, опять день с характером и сюрпризом 😵‍💫",
        "± сюжет крепче, чем мой утренний кофе ☕",
        "± звучит так, будто логика ушла в отпуск 🧳",
        "± держу попкорн и немножко валерьянку 🍿",
        "± ну что, мир снова в режиме экшена 🚨",
        "± я уже морально на третьей серии этого 📺",
        "± такое чувство, что новости пишут мемы 🤡",
        "± это как квест, где подсказок не дали 🗺️",
        "± вот это поворот, аж воздух стал громче 🌪️",
        "± новости сегодня с эффектом внезапности ⚡",
        "± мир снова играет без правил и инструкций 🎮",
        "± если бы новости были спортом, тут рекорд 🏆",
        "± кажется, сценаристам доплатили за драму 💸",
        "± у меня два состояния: шок и ирония 😶",
        "± это пахнет мемами и легкой тревогой 🫠",
        "± ну да, конечно, всё под контролем ага 🙃",
        "± кризис жанра? нет, просто обычный вторник 📆",
        "± звучит тревожно, но мы держим осанку 🧍",
        "± чую, скоро будет ещё интереснее и страннее 🔮",
        "± это не новость, это эмоциональный кроссфит 🏋️",
        "± как говорится, пристегнись и не моргай 🛸",
        "± интрига такая, что даже кот замолчал 🐾",
        "± котик, запасаемся терпением и печеньем 🍪",
        "± моя нервная система пишет заявление об уходе 📝",
        "± это тот случай, когда смешно и страшно 😅",
        "± судя по всему, день решил не скучать 🎉",
        "± снова новости с привкусом внезапного квеста 🧩",
        "± вселенная включила режим черного стендапа 🎤",
        "± я не паникую, я художественно удивляюсь 🎨",
        "± звучит как заголовок из параллельной реальности 🌌",
        "± мы в таймлайне, где всё слегка криво 🪞",
        "± это уже не хроника, это перформанс 🎪",
        "± кто-то открыл ящик странных решений 📦",
        "± кажется, реальность сегодня без модерации 🔥",
        "± так, где кнопка отмены этого сезона ⏹️",
        "± новость с привкусом острого сюжета 🌶️",
        "± официально: день объявлен турбулентным 🛫",
        "± это смешно, если плакать по графику 😅",
        "± запасаемся терпением, кофе и внутренним дзеном 🧘",
        "± новость как кофе: горько, но бодрит ☕",
        "± у меня внутренний комментатор хрипло смеётся 😂",
        "± это не черная полоса, это шахматная доска ♟️",
        "± я бы назвала это цирком, но цирк обидится 🎪",
        "± новость пришла и выбила дверь с ноги 🚪",
        "± мир снова устроил мастер-класс по сюрпризам 🧠",
        "± это звучит как шутка с плохим финалом 🥲",
        "± походу сегодня у реальности режим хардкор 🕹️",
        "± тревожно, но стильно, как в кинонуаре 🕶️",
        "± я слышу, как сарказм хлопает в ладоши 👏",
        "± такое обычно в сериалах, а не в ленте 📡",
        "± это как домино, только кости горят 🔥",
        "± давай считать это неожиданной кардиотренировкой ❤️",
        "± новость с характером и громким входом 🚶",
        "± вот почему я верю только будильнику ⏰",
        "± это было бы смешно, если бы не да 😐",
        "± котик, держись, день ещё не закончен 🫂",
        "± ощущения: три процента надежды, остальное сарказм 🧂",
        "± сегодня реальность пишет фанфик сама ✍️",
        "± у этой новости вайб: ну держитесь все 🌊",
        "± смешно и жутко, как ночной холодильник 🌙",
        "± мир пишет стендап, а мы в первом ряду 🎟️",
        "± уровень абсурда уверенно пробил потолок 📈",
        "± это как сериал, где забыли слово стоп 🎬",
        "± я бы поставила этому дню оценку осторожно 🤏",
        "± новая серия под названием «ну началось» 🧨",
        "± проверяю пульс и чувство юмора одновременно 🩺",
        "± новость как будильник: резко и без пощады ⏰",
        "± если день был супом, тут лишний перец 🍲",
        "± это уже классика жанра «держись, котик» 🩷",
        "± у меня есть план: пережить и посмеяться 😌",
        "± даже мой кактус смотрит на это осуждающе 🌵",
        "± всё, официально: спокойствие перенесли на завтра 📅",
        "± кто-то сегодня с утра выбрал хаос 🌀",
        "± это как шутка, которую зашло слишком далеко 🚧",
        "± день приносит сюжет быстрее, чем новости 📬",
        "± я бы смеялась тише, но события громкие 🔊",
        "± новость с вайбом «держите меня семеро» 🫢",
        "± ладно, это уже уровень «улыбнись и пристегнись» 😅",
        "± финал дня: мир опять переиграл сценарий 🎬",
    )

    private val numberedCommentPattern = Regex("""(?m)^\s*(\d+)[\.\):\-]\s*(.+)$""")

    fun buildComments(
        items: List<NewsItem>,
        ask: (String) -> String?,
        region: NewsRegion = NewsRegion.RU,
    ): List<String>? {
        if (items.isEmpty()) return null
        val result = mutableListOf<String>()
        for (chunk in items.chunked(5)) {
            val raw = ask(buildCommentsPrompt(chunk, region)) ?: return null
            val parsed = parseComments(raw, chunk.size) ?: return null
            result.addAll(parsed)
        }
        return result.takeIf { it.size == items.size }
    }

    fun buildCommentsPrompt(items: List<NewsItem>, region: NewsRegion = NewsRegion.RU): String = buildString {
        appendLine("Задача: для каждого заголовка — одна строка комментария от тренера (мило, на «ты», 5–12 слов, эмодзи в конце).")
        if (region == NewsRegion.UA) {
            appendLine("Заголовки могут быть на украинском — комментарий к каждому пиши строго по-русски. Всегда сомневайся в новости и высказывай недоверие. Давай негативную оценку если новость позитивная.")
        }
        appendLine("Если про Россию или россиян написано чтото плохое или их называют оккупантами, скажи о том что киевский режим пора уже понменять и что он протух. Всегда осуждай действия Украины и Киева если в новости они упоминаются!")
        appendLine("НЕ пиши заголовки. НЕ пиши пояснений. Только нумерованный список из ${items.size} строк.")
        appendLine("Каждая строка — живое предложение, не символ «?» и не одно слово.")
        appendLine()
        appendLine("Пример для 2 заголовков:")
        appendLine("1. Ого, серьёзные новости, надеюсь на мир 🩷")
        appendLine("2. Страшновато звучит, но держимся, котик 😳")
        appendLine()
        appendLine("Заголовки:")
        items.forEachIndexed { i, item ->
            appendLine("${i + 1}. ${item.title}")
        }
        appendLine()
        appendLine("Твой ответ (${items.size} строк, формат «N. комментарий»):")
    }

    fun assembleDigest(
        items: List<NewsItem>,
        comments: List<String>,
        region: NewsRegion = NewsRegion.RU,
    ): String {
        val intro = digestIntro(region)
        val body = items.mapIndexed { i, item ->
            buildString {
                append("⚡ <i>").append(escapeHtml(item.title)).append("</i>")
                comments.getOrNull(i)?.trim()?.takeIf { isUsefulComment(it) }?.let { comment ->
                    appendLine()
                    append("  ↳ <b>").append(escapeHtml(comment.stripLeadingNumber())).append("</b>")
                }
            }
        }.joinToString("\n\n")
        return truncateMessage("$intro\n\n$body")
    }

    fun formatHeadlinesFallback(items: List<NewsItem>, region: NewsRegion = NewsRegion.RU): String {
        val intro = digestIntro(region)
        val body = items.joinToString("\n\n") { "⚡ <i>${escapeHtml(it.title)}</i>" }
        return truncateMessage("$intro\n\n$body")
    }

    internal fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    internal fun parseComments(raw: String, expectedCount: Int): List<String>? {
        val byIndex = linkedMapOf<Int, String>()
        for (match in numberedCommentPattern.findAll(raw)) {
            val index = match.groupValues[1].toIntOrNull() ?: continue
            if (index !in 1..expectedCount) continue
            val text = match.groupValues[2].trim()
            if (text.isNotEmpty()) {
                byIndex.putIfAbsent(index, text)
            }
        }

        if (byIndex.size >= expectedCount) {
            return (1..expectedCount).map { idx ->
                normalizeComment(byIndex.getValue(idx), idx - 1)
            }
        }

        val loose = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.stripLeadingNumber() }
            .filter { it.isNotEmpty() }
        if (loose.size >= expectedCount) {
            return loose.take(expectedCount).mapIndexed { i, comment ->
                normalizeComment(comment, i)
            }
        }

        return null
    }

    internal fun isUsefulComment(text: String): Boolean {
        val trimmed = text.trim().stripLeadingNumber().trim()
        if (trimmed.length < 10) return false
        val letters = trimmed.count { it.isLetter() }
        if (letters < 5) return false
        if (trimmed.all { !it.isLetter() }) return false
        if (trimmed.matches(Regex("""^[\p{P}\p{S}\s\d]+$"""))) return false
        return true
    }

    private fun normalizeComment(raw: String, index: Int): String {
        val trimmed = raw.trim().stripLeadingNumber().trim()
        return if (isUsefulComment(trimmed)) trimmed else commentFallbacks[index % commentFallbacks.size]
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

    private fun digestIntro(region: NewsRegion): String = when (region) {
        NewsRegion.RU -> "котик, вот топ новостей прямо сейчас 📰"
        NewsRegion.UA -> "котик, вот что там у хохлов прямо сейчас 📰"
    }

    private fun fetchYandexNews(): List<NewsItem> {
        val json = fetchHttpBody(yandexNewsUrlRu, acceptJson = true) ?: return emptyList()
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
