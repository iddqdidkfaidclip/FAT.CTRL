package vc.fatfukkers.service

import java.util.Locale
import kotlin.random.Random

object ImageCaptionPhrases {
    private val nounTemplates = listOf(
        "Теперь ты сидишь и смотришь на %s, ты доволен?",
        "Лови %s, как просил",
        "Держи %s, вот бы увидеть с другого ракурса",
        "Вот %s, наслаждайся",
        "Ты хотел %s? Получай",
        "Смотри на %s, очень красиво как по мне",
        "Вот тебе %s — больше не проси",
        "Нашла %s специально для тебя",
        "Оценивай %s по 10-бальной шкале",
        "Полюбуйся — вот %s",
    )

    private val clauseTemplates = listOf(
        "Ты хотел увидеть %s — доволен?",
        "Вот %s, как и заказывал",
        "Смотри: %s",
        "Лови: %s",
        "Вот то, что ты просил: %s",
        "Твой запрос — %s, ну как?",
        "Запоминай: %s",
        "Результат поиска: %s",
        "Наслаждайся: %s",
        "Держи, это %s",
    )

    fun random(query: String, random: Random = Random.Default): String {
        val normalized = query.trim().lowercase(Locale("ru", "RU"))
        return if (RussianMorph.isClauseLike(normalized)) {
            clauseTemplates.random(random).format(normalized)
        } else {
            val accusative = RussianMorph.toAccusative(query)
            nounTemplates.random(random).format(accusative)
        }
    }
}
