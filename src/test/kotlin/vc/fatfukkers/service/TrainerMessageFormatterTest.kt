package vc.fatfukkers.service

import kotlin.test.Test
import kotlin.test.assertEquals

class TrainerMessageFormatterTest {
    @Test
    fun `double asterisks become italic in brackets`() {
        assertEquals(
            "Ой, [<i>покраснела</i>] от такого вопроса.",
            TrainerMessageFormatter.formatForTelegramHtml("Ой, **покраснела** от такого вопроса."),
        )
    }

    @Test
    fun `single asterisks become italic in brackets`() {
        assertEquals(
            "Ой, [<i>покраснела</i>] от такого вопроса.",
            TrainerMessageFormatter.formatForTelegramHtml("Ой, *покраснела* от такого вопроса."),
        )
    }

    @Test
    fun `escapes html outside emphasis`() {
        assertEquals(
            "a &lt;b&gt; [<i>x</i>]",
            TrainerMessageFormatter.formatForTelegramHtml("a <b> *x*"),
        )
    }

    @Test
    fun `escapes html inside emphasis`() {
        assertEquals(
            "[<i>&lt;script&gt;</i>]",
            TrainerMessageFormatter.formatForTelegramHtml("**<script>**"),
        )
    }

    @Test
    fun `normalizeCodeFences adds newline after opening fence`() {
        assertEquals(
            "```\nhello\n```",
            TrainerMessageFormatter.normalizeCodeFences("```hello```"),
        )
    }

    @Test
    fun `normalizeCodeFences keeps language header line`() {
        assertEquals(
            "```python\nprint(1)\n```",
            TrainerMessageFormatter.normalizeCodeFences("```python\nprint(1)```"),
        )
    }

    @Test
    fun `normalizeCodeFences adds newline before closing fence`() {
        assertEquals(
            "```\nhello\n```",
            TrainerMessageFormatter.normalizeCodeFences("```\nhello```"),
        )
    }

    @Test
    fun `fenced code becomes pre block`() {
        assertEquals(
            "вот код:\n<pre>print(\"hi\")</pre>",
            TrainerMessageFormatter.formatForTelegramHtml("""вот код:
```python
print("hi")
```"""),
        )
    }

    @Test
    fun `fenced code without newlines is normalized and rendered`() {
        assertEquals(
            "<pre>hello</pre>",
            TrainerMessageFormatter.formatForTelegramHtml("```hello```"),
        )
    }
}
