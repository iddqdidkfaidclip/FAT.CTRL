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
        Template("Глянь на %s и вспомни, что у тебя ещё есть дела", Case.ACCUSATIVE),
        Template("Вот %s, повторишь?", Case.NOMINATIVE),
        Template("Смотри на %s и делай вид, что у тебя всё под контролем", Case.ACCUSATIVE),
        Template("Поставь %s себе на заставку, чтобы мотивации точно не было", Case.ACCUSATIVE),
        Template("Вот %s — лучшая замена личной жизни", Case.NOMINATIVE),
        Template("%s — это сейчас важнее, чем твоя работа, да?", Case.NOMINATIVE),
        Template("Вот %s — выглядит как ты с утра", Case.NOMINATIVE),
        Template("Вот тебе %s, официально одобренный Минздравом", Case.ACCUSATIVE),
        Template("%s смотрит на тебя в ответ и тоже разочаровано", Case.NOMINATIVE),
        Template("Смотри на %s и честно ответь: оно того стоило?", Case.ACCUSATIVE),
        Template("Вот %s — смотри и думай, как объяснить это пацанам", Case.NOMINATIVE),
        Template("Вот %s — идеальное алиби, почему ты снова не лёг вовремя", Case.NOMINATIVE),
        Template("Вот %s — официальное лицо твоей бывшей", Case.NOMINATIVE),
        Template("Вот %s, теперь это официально твой новый аватар", Case.NOMINATIVE),
        Template("Вот %s, теперь это официально твой патронус", Case.NOMINATIVE),
        Template("Смотри на %s и делай скрин, пока бесплатно", Case.ACCUSATIVE),
        Template("Смотри на %s, а потом на себя в зеркало.", Case.ACCUSATIVE),
        Template("Вот %s — нашла у тебя на рабочем столе", Case.NOMINATIVE),
        Template("Вот %s — и да, я всё это логирую", Case.NOMINATIVE),
        Template("%s лучше чем твой дикпик", Case.NOMINATIVE),
        Template("Вот %s — нашла у тебя на рабочем столе", Case.NOMINATIVE),
        Template("Вот ты и узнал как выглядит %s", Case.NOMINATIVE),
        Template("Вот %s — нашла у тебя под кроватью", Case.NOMINATIVE),
        Template("Я бы смотрела на %s вечно", Case.NOMINATIVE),
        Template("О, мой любимый вид на %s", Case.NOMINATIVE),
        Template("Завтра все увидят %s и тебе станет стыдно", Case.NOMINATIVE),
        Template("А с этого ракурса %s выглядит прямо как твоя задница", Case.NOMINATIVE),
        Template("Вот тебе %s — и не спрашивай, откуда у меня это", Case.ACCUSATIVE),
        Template("Держи %s, раз уж ты дошёл до такого", Case.ACCUSATIVE),
        Template("Глянь на %s и скажи честно: ты доволен?", Case.ACCUSATIVE),
        Template("Вот %s, наслаждайся — завтра опять пришлю", Case.NOMINATIVE),
        Template("Только для тебя %s и не говори после этого, что я тебя не люблю", Case.ACCUSATIVE),
        Template("Лови %s, но это тебе на Новый Год!", Case.ACCUSATIVE),
        Template("Вот %s — теперь это твоя проблема", Case.NOMINATIVE),
        Template("Смотри на %s, раз уж ты решил позориться публично", Case.ACCUSATIVE),
    )

    private val clauseTemplates = nounTemplates.map { it.text }

    fun random(query: String, random: Random = Random.Default): String {
        val normalized = query.trim().lowercase(Locale("ru", "RU"))
        return if (RussianMorph.isClauseLike(normalized)) {
            val template = clauseTemplates.random(random)
            val sanitizedTemplate = if (normalized.startsWith("как ")) {
                template.replace("на %s", "%s")
            } else {
                template
            }
            sanitizedTemplate.format(normalized)
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
