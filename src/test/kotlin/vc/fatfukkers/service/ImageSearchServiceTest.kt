package vc.fatfukkers.service

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ImageSearchServiceTest {
    @BeforeTest
    fun resetRecentGifUrls() {
        ImageSearchService.clearRecentGifUrlsForTest()
    }

    @Test
    fun `selectCandidates for gif skips globally recent urls across different queries`() {
        ImageSearchService.rememberGifUrlForTest("https://example.com/1.gif")
        ImageSearchService.rememberGifUrlForTest("https://example.com/2.gif")

        val all = listOf(
            "https://example.com/1.gif",
            "https://example.com/2.gif",
            "https://example.com/3.gif",
            "https://example.com/4.gif",
        )

        val selected = ImageSearchService.selectCandidates(
            allCandidates = all,
            queryKey = "gif котик",
            useGlobalGifDedup = true,
        )

        assertEquals(listOf("https://example.com/3.gif", "https://example.com/4.gif"), selected)
    }

    @Test
    fun `selectCandidates for gif prefers fresh for query and global before relaxing query dedup`() {
        ImageSearchService.rememberGifUrlForTest("https://example.com/global.gif")
        ImageSearchService.rememberUrlForTest("gif котик", "https://example.com/query.gif")

        val all = listOf(
            "https://example.com/global.gif",
            "https://example.com/query.gif",
            "https://example.com/fresh.gif",
        )

        val selected = ImageSearchService.selectCandidates(
            allCandidates = all,
            queryKey = "gif котик",
            useGlobalGifDedup = true,
        )

        assertEquals(listOf("https://example.com/fresh.gif"), selected)
    }

    @Test
    fun `selectCandidates for gif falls back to all candidates when everything was shown recently`() {
        ImageSearchService.rememberGifUrlForTest("https://example.com/only.gif")

        val all = listOf("https://example.com/only.gif")

        val selected = ImageSearchService.selectCandidates(
            allCandidates = all,
            queryKey = "gif собака",
            useGlobalGifDedup = true,
        )

        assertEquals(all, selected)
    }

    @Test
    fun `selectCandidates for photos does not apply global gif dedup`() {
        ImageSearchService.rememberGifUrlForTest("https://example.com/1.gif")

        val all = listOf("https://example.com/1.gif", "https://example.com/2.jpg")

        val selected = ImageSearchService.selectCandidates(
            allCandidates = all,
            queryKey = "котик",
            useGlobalGifDedup = false,
        )

        assertEquals(all, selected)
    }

    @Test
    fun `rememberGifUrl keeps only last five urls`() {
        repeat(6) { index ->
            ImageSearchService.rememberGifUrlForTest("https://example.com/$index.gif")
        }

        val recent = ImageSearchService.recentGifUrlsForTest()
        assertEquals(5, recent.size)
        assertFalse(recent.contains("https://example.com/0.gif"))
        assertEquals("https://example.com/5.gif", recent.last())
    }
}
