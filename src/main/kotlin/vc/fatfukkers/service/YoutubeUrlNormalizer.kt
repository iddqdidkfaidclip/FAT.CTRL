package vc.fatfukkers.service

import java.net.URI

object YoutubeUrlNormalizer {
    fun normalize(url: String): String {
        return try {
            val uri = URI(url)
            val videoId = extractVideoId(uri) ?: return url
            URI("https", "www.youtube.com", "/watch", "v=$videoId", null).toString()
        } catch (_: Exception) {
            url
        }
    }

    fun extractVideoId(url: String): String? = try {
        extractVideoId(URI(url))
    } catch (_: Exception) {
        null
    }

    private fun extractVideoId(uri: URI): String? {
        val path = uri.path.orEmpty().trimEnd('/')
        val host = uri.host?.lowercase().orEmpty()
        return when {
            path.startsWith("/shorts/") -> idFromPathSegment(path, "/shorts/")
            path.startsWith("/live/") -> idFromPathSegment(path, "/live/")
            path.startsWith("/embed/") -> idFromPathSegment(path, "/embed/")
            path == "/watch" -> videoIdFromQuery(uri.rawQuery)
            host == "youtu.be" -> path.removePrefix("/").substringBefore('/').takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun idFromPathSegment(path: String, prefix: String): String? =
        path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() }

    private fun videoIdFromQuery(query: String?): String? =
        query?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
}
