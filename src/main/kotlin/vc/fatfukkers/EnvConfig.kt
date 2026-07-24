package vc.fatfukkers

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object EnvConfig {
    private val logger = LoggerFactory.getLogger(EnvConfig::class.java)
    private val envFile: Path? = findEnvFile()
    private val dotEnv: Map<String, String> = loadDotEnv()

    init {
        if (envFile != null) {
            logger.info("Loaded .env from {}", envFile.toAbsolutePath())
        } else {
            logger.warn("No .env file found (jar dir / cwd)")
        }
    }

    private fun loadDotEnv(): Map<String, String> {
        val path = envFile ?: return emptyMap()
        return Files.readAllLines(path).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }.toMap()
    }

    private fun findEnvFile(): Path? {
        jarDirEnv()?.takeIf { Files.exists(it) }?.let { return it }
        Paths.get(".env").toAbsolutePath().normalize().takeIf { Files.exists(it) }?.let { return it }
        return null
    }

    private fun jarDirEnv(): Path? = try {
        val location = EnvConfig::class.java.protectionDomain.codeSource?.location ?: return null
        val jarPath = Paths.get(location.toURI())
        jarPath.parent?.resolve(".env")
    } catch (_: Exception) {
        null
    }

    fun get(name: String): String? =
        dotEnv[name]?.trim().takeUnless { it.isNullOrBlank() }
            ?: System.getenv(name)?.trim()?.takeUnless { it.isNullOrBlank() }

    fun getSource(name: String): String? = when {
        !dotEnv[name].isNullOrBlank() -> "file"
        !System.getenv(name).isNullOrBlank() -> "env"
        else -> null
    }
}
