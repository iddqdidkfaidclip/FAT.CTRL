package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import kotlin.random.Random

object JokesService {
    private val logger = LoggerFactory.getLogger(JokesService::class.java)
    private const val JOKES_RESOURCE = "/jokes.txt"

    private val fallbackJokes = listOf(
        "Купил умные весы. Теперь они не показывают мой вес, а вздыхают.",
        "Тренер сказала бегать по утрам. Я бегаю. Правда, от будильника к телефону и обратно.",
        "Решил питаться правильно. Теперь ем пиццу только правой рукой.",
    )

    private val jokes: List<String> by lazy { loadJokes() }

    fun random(random: Random = Random.Default): String {
        val pool = jokes.ifEmpty { fallbackJokes }
        return pool.random(random)
    }

    private fun loadJokes(): List<String> {
        val stream = JokesService::class.java.getResourceAsStream(JOKES_RESOURCE)
        if (stream == null) {
            logger.warn("Jokes resource {} not found, using fallback list", JOKES_RESOURCE)
            return emptyList()
        }
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return content.split("\n---\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
    }
}
