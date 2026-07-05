package vc.fatfukkers.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import vc.fatfukkers.newsQueryPattern
import java.time.LocalDate
import java.time.ZoneId

class NewsServiceTest {

    private val sampleRss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss>
          <channel>
            <item>
              <title><![CDATA[Первая новость — Источник]]></title>
              <link>https://example.com/1</link>
            </item>
            <item>
              <title>Вторая новость</title>
              <link>https://example.com/2</link>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `parseRssItems extracts titles and links`() {
        val items = NewsService.parseRssItems(sampleRss)
        assertEquals(2, items.size)
        assertEquals("Первая новость — Источник", items[0].title)
        assertEquals("https://example.com/1", items[0].link)
        assertEquals("Вторая новость", items[1].title)
    }

    @Test
    fun `primaryFeedUrl points to Yandex news`() {
        assertEquals(NewsService.YANDEX_NEWS_SOURCE, NewsService.primaryFeedUrl())
    }

    @Test
    fun `buildFeedSources prefers Yandex then live RSS feeds`() {
        val sources = NewsService.buildFeedSources(ZoneId.of("Europe/Moscow"))
        assertEquals(NewsService.YANDEX_NEWS_SOURCE, sources.first())
        assertTrue(sources.any { it.contains("tass.ru") })
        assertTrue(sources.any { it.contains("ria.ru/export/rss2/index.xml") })
        assertTrue(!sources.any { it.contains("archive") })
    }

    @Test
    fun `parseYandexNewsJson extracts story titles and links`() {
        val json = """
            {"block":[{"data":{"states":[{"div":{"items":[
              {"title":"Сейчас в СМИ","action":{"url":"https://m.dzen.ru/news"}},
              {"title":"Песков: тестовая новость про события","action":{"url":"yellowskin://?url=https%3A%2F%2Fdzen.ru%2Fnews%2Fstory%2F93fbb1ad-8699-56bf-b42c-92c861739a72"}},
              {"title":"Ещё по теме"},
              {"title":"В\u00a0Кремле подтвердили готовность","action":{"url":"news/story/13d014b0-b6db-521e-8d77-bdbac421b909"}}
            ]}}]}}]}
        """.trimIndent()
        val items = NewsService.parseYandexNewsJson(json)
        assertEquals(2, items.size)
        assertEquals("Песков: тестовая новость про события", items[0].title)
        assertTrue(items[0].link.contains("93fbb1ad-8699-56bf-b42c-92c861739a72"))
        assertEquals("В Кремле подтвердили готовность", items[1].title)
    }

    @Test
    fun `parsePubDate parses RFC 1123 dates`() {
        val zone = ZoneId.of("Europe/Moscow")
        assertEquals(LocalDate.of(2026, 7, 4), NewsService.parsePubDate("Sat, 04 Jul 2026 08:17:00 +0300", zone))
    }

    @Test
    fun `parseComments extracts numbered lines`() {
        val raw = """
            1. ого, интересно 🙈
            2. ну и дела 😳
        """.trimIndent()
        val comments = NewsService.parseComments(raw, 2)
        assertNotNull(comments)
        assertEquals(listOf("ого, интересно 🙈", "ну и дела 😳"), comments)
    }

    @Test
    fun `parseComments returns null when too few lines`() {
        assertNull(NewsService.parseComments("1. только одна", 3))
    }

    @Test
    fun `assembleDigest keeps headlines verbatim`() {
        val items = listOf(
            NewsItem("Заголовок А", "https://a"),
            NewsItem("Заголовок Б", "https://b"),
        )
        val text = NewsService.assembleDigest(items, listOf("коммент а 🩷", "коммент б 😳"))
        assertTrue(text.contains("топ новостей прямо сейчас"))
        assertTrue(text.contains("⚡ Заголовок А"))
        assertTrue(text.contains("⚡ Заголовок Б"))
        assertTrue(text.contains("↳😺 коммент а 🩷"))
        assertTrue(text.contains("↳😺 коммент б 😳"))
    }

    @Test
    fun `formatHeadlinesFallback lists titles only`() {
        val items = listOf(NewsItem("Только заголовок", "https://x"))
        val text = NewsService.formatHeadlinesFallback(items)
        assertTrue(text.contains("топ новостей прямо сейчас"))
        assertTrue(text.contains("• Только заголовок"))
    }
}

class NewsQueryPatternTest {

    @Test
    fun `matches news commands`() {
        assertTrue(newsQueryPattern.matches("новости"))
        assertTrue(newsQueryPattern.matches("расскажи новости"))
        assertTrue(newsQueryPattern.matches("новости за вчера"))
        assertTrue(newsQueryPattern.matches("  Расскажи   новости  "))
    }

    @Test
    fun `does not match unrelated queries`() {
        assertTrue(!newsQueryPattern.matches("новости про спорт"))
        assertTrue(!newsQueryPattern.matches("расскажи анекдот"))
    }
}
