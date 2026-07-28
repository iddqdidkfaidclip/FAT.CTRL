package vc.fatfukkers.service

/**
 * Готовит текст ответа Тренер для Telegram HTML: экранирует разметку,
 * превращает *мысли* / **мысли** в курсив и ```блоки``` в &lt;pre&gt;.
 */
internal object TrainerMessageFormatter {
    private const val FENCE = "```"
    private val doubleAsteriskPattern = Regex("""\*\*(.+?)\*\*""")
    private val singleAsteriskPattern = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")
    private val fencedCodePattern = Regex("""```[^\n]*\n([\s\S]*?)```""")

    fun formatForTelegramHtml(text: String): String {
        val placeholders = mutableListOf<String>()
        var working = extractCodeFences(normalizeCodeFences(text), placeholders)
        working = doubleAsteriskPattern.replace(working) { match ->
            placeholder(placeholders, italicHtml(match.groupValues[1]))
        }
        working = singleAsteriskPattern.replace(working) { match ->
            placeholder(placeholders, italicHtml(match.groupValues[1]))
        }
        working = escapeHtml(working)
        placeholders.forEachIndexed { index, html ->
            working = working.replace(placeholderToken(index), html)
        }
        return working
    }

    internal fun normalizeCodeFences(text: String): String {
        val result = StringBuilder()
        var index = 0
        var inFence = false
        while (index < text.length) {
            if (text.startsWith(FENCE, index)) {
                if (inFence && result.isNotEmpty() && result.last() != '\n') {
                    result.append('\n')
                }
                result.append(FENCE)
                index += FENCE.length
                if (!inFence) {
                    val nextNewline = text.indexOf('\n', index).let { if (it == -1) text.length else it }
                    val nextClose = text.indexOf(FENCE, index).let { if (it == -1) text.length else it }
                    if (nextClose < nextNewline) {
                        result.append('\n')
                    } else {
                        result.append(text.substring(index, nextNewline))
                        result.append('\n')
                        index = (nextNewline + 1).coerceAtMost(text.length)
                    }
                    inFence = true
                } else {
                    inFence = false
                    if (index < text.length && text[index] != '\n') {
                        result.append('\n')
                    }
                }
            } else {
                result.append(text[index++])
            }
        }
        return result.toString()
    }

    private fun extractCodeFences(text: String, placeholders: MutableList<String>): String =
        fencedCodePattern.replace(text) { match ->
            val body = match.groupValues[1].trimEnd('\n', '\r')
            placeholder(placeholders, "<pre>${escapeHtml(body)}</pre>")
        }

    private fun italicHtml(text: String): String =
        "[<i>${escapeHtml(text)}</i>]"

    private fun placeholder(placeholders: MutableList<String>, html: String): String {
        val index = placeholders.size
        placeholders.add(html)
        return placeholderToken(index)
    }

    private fun placeholderToken(index: Int): String = "\u0000FMT$index\u0000"

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
