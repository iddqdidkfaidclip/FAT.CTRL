package vc.fatfukkers.service

import java.util.Locale

object RussianMorph {
    private val SKIP_WORDS = setOf(
        "про", "для", "без", "от", "до", "из", "на", "в", "с", "к", "о", "по", "за", "и", "у", "при",
    )

    private val GKHZHCHSHCH = setOf('г', 'к', 'х', 'ж', 'ч', 'ш', 'щ')

    private val VERB_ENDING = Regex("""(ют|ут|ат|ят|ит|ет|ишь|ешь|им|ем|ите|ете|л[аи]?)$""")

    fun isClauseLike(query: String): Boolean {
        val trimmed = query.trim().lowercase(Locale("ru", "RU"))
        if (trimmed.startsWith("как ")) return true
        if (" как " in trimmed) return true
        return trimmed.split(Regex("\\s+")).any { VERB_ENDING.containsMatchIn(it) }
    }

    fun toAccusative(query: String): String {
        val normalized = query.trim().lowercase(Locale("ru", "RU"))
        if (isClauseLike(normalized)) return normalized

        return normalized
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                if (word in SKIP_WORDS) word else toAccusativeWord(word)
            }
    }

    private fun toAccusativeWord(word: String): String {
        if (word.length < 2) return word

        return when {
            word.endsWith("у") || word.endsWith("ю") -> word
            word.endsWith("ия") -> word.dropLast(1) + "ю"
            word.endsWith("ие") -> word
            word.endsWith("ь") -> word
            word.endsWith("й") -> word.dropLast(1) + "я"
            word.endsWith("а") -> feminineOrAnimateAccusativeA(word)
            word.endsWith("я") -> word.dropLast(1) + "ю"
            word.endsWith("о") || word.endsWith("е") -> word
            word.endsWith("ы") || word.endsWith("и") -> word
            word.length <= 4 -> word + "а"
            else -> word
        }
    }

    /** голова → голову, кота → кота, книга → книгу */
    private fun feminineOrAnimateAccusativeA(word: String): String {
        val stem = word.dropLast(1)
        if (stem.length <= 4 && stem.none { it in "аеёиоуыэюя" }) return word
        if (stem.endsWith("ик") || stem.endsWith("чик") || stem.endsWith("ец") || stem.endsWith("ок")) return word
        return when (stem.lastOrNull()) {
            in GKHZHCHSHCH -> stem + "у"
            else -> stem + "у"
        }
    }
}
