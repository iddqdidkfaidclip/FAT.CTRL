package vc.fatfukkers.service

import java.util.Locale
import kotlin.random.Random

object ImageCaptionPhrases {
    private enum class Case { ACCUSATIVE, NOMINATIVE }

    private data class Template(val text: String, val wordCase: Case)

    private val nounTemplates = listOf(
        Template("Теперь ты сидишь и смотришь на %s, ты доволен?", Case.ACCUSATIVE),
        Template("Лови %s, как просил", Case.ACCUSATIVE),
        Template("Держи %s, вот бы увидеть с другого ракурса", Case.ACCUSATIVE),
        Template("Вот %s, наслаждайся красотой", Case.NOMINATIVE),
        Template("Ты хотел %s? Получай, ты это заслужил.", Case.ACCUSATIVE),
        Template("Смотри на %s, очень красиво как по мне", Case.ACCUSATIVE),
        Template("Вот тебе %s — больше не проси", Case.ACCUSATIVE),
        Template("Нашла %s специально для тебя", Case.ACCUSATIVE),
        Template("Оценивай %s по 10-бальной шкале", Case.ACCUSATIVE),
        Template("Полюбуйся — вот нечто похожее на %s", Case.NOMINATIVE),
    )

    private val clauseTemplates = listOf(
        "Ты хотел увидеть %s — доволен?",
        "Вот %s, как и заказывал",
        "Смотри: %s",
        "Лови: %s",
        "Вот то, что ты просил: %s",
        "Твой запрос — %s, ты доволен?",
        "Запоминай как выглядит %s",
        "Первый раз вижу как выглядит %s",
        "Наслаждайся: %s",
        "Держи, это %s",
    )

    fun random(query: String, random: Random = Random.Default): String {
        val normalized = query.trim().lowercase(Locale("ru", "RU"))
        return if (RussianMorph.isClauseLike(normalized)) {
            clauseTemplates.random(random).format(normalized)
        } else {
            val template = nounTemplates.random(random)
            val word = when (template.wordCase) {
                Case.ACCUSATIVE -> RussianMorph.toAccusative(query)
                Case.NOMINATIVE -> RussianMorph.toNominative(query)
            }
            template.text.format(word)
        }
    }
}
