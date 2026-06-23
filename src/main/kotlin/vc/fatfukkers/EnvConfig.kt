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
            logger.warn("No .env file found (checked ENV_FILE, ./.env, .env next to jar)")
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
        System.getenv("ENV_FILE")?.trim()?.takeUnless { it.isBlank() }?.let { explicit ->
            val p = Paths.get(explicit)
            if (Files.exists(p)) return p
        }
        val cwd = Paths.get(".env")
        if (Files.exists(cwd)) return cwd
        jarDirEnv()?.let { if (Files.exists(it)) return it }
        return null
    }

    private fun jarDirEnv(): Path? = try {
        val location = EnvConfig::class.java.protectionDomain.codeSource?.location ?: return null
        val jarPath = Paths.get(location.toURI())
        jarPath.parent?.resolve(".env")
    } catch (_: Exception) {
        null
    }

    fun get(name: String): String? {
        val fromEnv = System.getenv(name)?.trim().takeUnless { it.isNullOrBlank() }
        if (fromEnv != null) return fromEnv
        return dotEnv[name]?.trim().takeUnless { it.isNullOrBlank() }
    }

    fun getSource(name: String): String? {
        val fromEnv = System.getenv(name)?.trim().takeUnless { it.isNullOrBlank() }
        if (fromEnv != null) return "env"
        val fromFile = dotEnv[name]?.trim().takeUnless { it.isNullOrBlank() }
        if (fromFile != null) return "file"
        return null
    }
}
