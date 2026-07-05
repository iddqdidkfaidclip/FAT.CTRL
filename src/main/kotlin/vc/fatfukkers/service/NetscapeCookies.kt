package vc.fatfukkers.service

import vc.fatfukkers.EnvConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object NetscapeCookies {
    fun cookieHeaderForDomain(path: String?, domainSuffix: String): String? {
        if (path.isNullOrBlank()) return null
        val file = Paths.get(path)
        if (!Files.isRegularFile(file)) return null

        val cookies = mutableListOf<String>()
        Files.readAllLines(file).forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split('\t')
            if (parts.size < 7) return@forEach
            val domain = parts[0]
            val name = parts[5]
            val value = parts[6]
            if (domain.contains(domainSuffix)) {
                cookies += "$name=$value"
            }
        }
        return cookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun youtubeCookieHeader(): String? =
        cookieHeaderForDomain(EnvConfig.get("YTDLP_YT_COOKIES_PATH"), "youtube.com")
}
