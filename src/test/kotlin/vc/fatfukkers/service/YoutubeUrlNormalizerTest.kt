package vc.fatfukkers.service

import kotlin.test.Test
import kotlin.test.assertEquals

class YoutubeUrlNormalizerTest {
    @Test
    fun `normalize shorts URL strips tracking params`() {
        val url = "https://youtube.com/shorts/W6_tPonOkOc?si=JOg3gYHWb2ZyASIp"
        assertEquals("https://www.youtube.com/watch?v=W6_tPonOkOc", YoutubeUrlNormalizer.normalize(url))
    }

    @Test
    fun `normalize youtu be link`() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YoutubeUrlNormalizer.normalize("https://youtu.be/dQw4w9WgXcQ?si=abc"),
        )
    }

    @Test
    fun `normalize watch URL keeps video id`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            YoutubeUrlNormalizer.normalize("https://m.youtube.com/watch?v=abc123&list=PLfoo"),
        )
    }

    @Test
    fun `extractVideoId from shorts`() {
        assertEquals("W6_tPonOkOc", YoutubeUrlNormalizer.extractVideoId("https://youtube.com/shorts/W6_tPonOkOc"))
    }
}
