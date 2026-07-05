package vc.fatfukkers.service

import java.util.Locale

object RussianMorph {
    private val SKIP_WORDS = setOf(
        "про", "для", "без", "от", "до", "из", "на", "в", "с", "к", "о", "по", "за", "и", "у", "при",
    )

    private val VOWELS = setOf('а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я')

    private val VERB_ENDING = Regex("""(ют|ут|ат|ят|ит|ет|ишь|ешь|им|ем|ите|ете|л[аи]?)$""")

    fun isClauseLike(query: String): Boolean {
        val trimmed = query.trim().lowercase(Locale("ru", "RU"))
        if (trimmed.startsWith("как ")) return true
        if (" как " in trimmed) return true
        return trimmed.split(Regex("\\s+")).any { VERB_ENDING.containsMatchIn(it) }
    }

    fun toAccusative(query: String): String =
        inflectQuery(query) { word -> toAccusativeWord(word) }

    fun toNominative(query: String): String =
        inflectQuery(query) { word -> toNominativeWord(word) }

    private fun inflectQuery(query: String, transform: (String) -> String): String {
        val normalized = query.trim().lowercase(Locale("ru", "RU"))
        if (isClauseLike(normalized)) return normalized

        return normalized
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                if (word in SKIP_WORDS) word else transform(word)
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

    private fun toNominativeWord(word: String): String {
        if (word.length < 2) return word

        return when {
            word.endsWith("у") -> accusativeFeminineUToNominative(word)
            word.endsWith("ю") -> word.dropLast(1) + "я"
            word.endsWith("а") && isMasculineAccusativeA(word) -> word.dropLast(1)
            word.endsWith("й") -> word.dropLast(1) + "й"
            else -> word
        }
    }

    /** голова → голову, судака → судака, собака → собаку */
    private fun feminineOrAnimateAccusativeA(word: String): String {
        val stem = word.dropLast(1)
        if (stem.length <= 4 && stem.none { it in VOWELS }) return word
        if (stem.endsWith("ик") || stem.endsWith("чик") || stem.endsWith("ец") || stem.endsWith("ок")) return word
        if (isMasculineAccusativeA(word)) return word
        if (!isFeminineNominativeA(word)) return word
        return stem + "у"
    }

    /** судака, кота — винительный м.р., основа = именительный */
    private fun isMasculineAccusativeA(word: String): Boolean {
        if (!word.endsWith("а")) return false
        val stem = word.dropLast(1)
        if (stem.length < 2 || stem.last() in VOWELS) return false
        if (isFeminineNominativeA(word)) return false
        return true
    }

    private fun isFeminineNominativeA(word: String): Boolean {
        val stem = word.dropLast(1)
        if (stem.endsWith("ов") || stem.endsWith("ев") || stem.endsWith("ин") || stem.endsWith("ен")) return true
        if (stem.endsWith("ал") || stem.endsWith("ел") || stem.endsWith("ил") || stem.endsWith("ол")) return true
        if (stem.endsWith("в") || stem.endsWith("м") || stem.endsWith("н") || stem.endsWith("л")) return true
        if (word.endsWith("га") || word.endsWith("ха") || word.endsWith("ча") || word.endsWith("ща")) return true
        if (word.endsWith("ка") && stem.length <= 3) return true
        val second = stem.getOrNull(1)
        if (word.endsWith("ка") && second != null && second in setOf('о', 'е', 'ё', 'э', 'я')) return true
        return false
    }

    private fun accusativeFeminineUToNominative(word: String): String {
        val stem = word.dropLast(1)
        if (stem.isEmpty()) return word
        return when (stem.last()) {
            'г', 'к', 'х', 'ч', 'щ', 'ж', 'ш' -> stem.dropLast(1) + "а"
            'л' -> stem + "я"
            else -> stem + "а"
        }
    }
}
