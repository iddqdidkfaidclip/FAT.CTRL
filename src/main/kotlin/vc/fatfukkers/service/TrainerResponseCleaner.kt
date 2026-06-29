package vc.fatfukkers.service

/**
 * Убирает chain-of-thought и служебный текст из ответа локальной модели,
 * оставляя только то, что должно уйти пользователю в Telegram.
 */
internal object TrainerResponseCleaner {
    const val ANSWER_DELIMITER = "|||"

    private val thinkingCloseTagPattern = Regex(
        """</(?:think|redacted_thinking)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val thinkingBlockPattern = Regex(
        """(?is)<(?:think|redacted_thinking)\b[^>]*>[\s\S]*?</(?:think|redacted_thinking)\s*>""",
    )
    private val rolePrefixPattern = Regex("""^\s*(тренер|trainer)\s*:\s*""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        var result = raw.trim()
        if (result.isEmpty()) return result

        result = thinkingBlockPattern.replace(result, "").trim()
        stripAfterLastThinkingCloseTag(result)?.let { result = it }
        stripAfterAnswerDelimiter(result)?.let { result = it }
        result = rolePrefixPattern.replace(result.trimStart(), "")
        return result.trimEnd()
    }

    private fun stripAfterAnswerDelimiter(text: String): String? {
        val index = text.lastIndexOf(ANSWER_DELIMITER)
        if (index == -1) return null
        val tail = text.substring(index + ANSWER_DELIMITER.length).trim()
        return tail.takeIf { it.isNotEmpty() }
    }

    private fun stripAfterLastThinkingCloseTag(text: String): String? {
        val match = thinkingCloseTagPattern.findAll(text).lastOrNull() ?: return null
        val tail = text.substring(match.range.last + 1).trim()
        return tail.takeIf { it.isNotEmpty() }
    }
}
