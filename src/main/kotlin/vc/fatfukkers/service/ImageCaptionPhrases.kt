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
        Template("Сидишь, пялишься на %s… и что дальше?", Case.ACCUSATIVE),
        Template("Ну вот, получил %s. Гордость так и прёт?", Case.ACCUSATIVE),
        Template("Держи %s — мечты подростков", Case.ACCUSATIVE),
        Template("Вот %s. Можно было и не просить, но ты же не можешь без этого", Case.NOMINATIVE),
        Template("Смотри на %s и не делай вид, что это для работы", Case.ACCUSATIVE),
        Template("Лови %s, чемпион", Case.ACCUSATIVE),
        Template("Вот тебе %s — сохрани в папку «важное»", Case.ACCUSATIVE),
        Template("Глянь на %s. Все гордятся тобой", Case.ACCUSATIVE),
        Template("Нашла %s. Ты счастлив? Я — нет", Case.ACCUSATIVE),
        Template("Смотри на %s вместо сна, как обычно", Case.ACCUSATIVE),
        Template("Оцени %s. Если меньше 7 — ты врёшь", Case.ACCUSATIVE),
        Template("Вот тебе %s, чтобы было о чём нассказать друзьям", Case.ACCUSATIVE),
        Template("Смотри на %s и вспомни, зачем ты вообще открыл чат", Case.ACCUSATIVE),
        Template("%s — вот и весь твой вечер коту под хвост", Case.NOMINATIVE),
        Template("Лови %s. Культурный человек, ничего не скажешь", Case.ACCUSATIVE),
        Template("Вот %s — выглядит дорого, тебе не по карману", Case.NOMINATIVE),
        Template("Глянь на %s и признайся: ты этого ждал весь день", Case.ACCUSATIVE),
        Template("Держи %s, но... ты что пьяный?", Case.ACCUSATIVE),
        Template("Вот %s. Можешь показать соседу.", Case.NOMINATIVE),
        Template("Смотри на %s. Чем пахнет?", Case.ACCUSATIVE),
        Template("Вот %s. И ты серьёзно это искал?", Case.NOMINATIVE),
        Template("%s ждёт. Ты тоже, видимо, ничего лучше не придумал", Case.NOMINATIVE),
        Template("Вот тебе %s. Поставь лайк если нравится!", Case.ACCUSATIVE),
        Template("Вот %s — и да, это будет в твоей биографии", Case.NOMINATIVE),
        Template("Смотри на %s. Время потрачено не зря… зря", Case.ACCUSATIVE),
        Template("Глянь на %s и скажи спасибо, что я не скриню чат", Case.ACCUSATIVE),
        Template("Вот тебе %s. Можешь считать это заботой", Case.ACCUSATIVE),
        Template("Лови %s — лучшее, что случилось с тобой за неделю", Case.ACCUSATIVE),
        Template("%s — вот чем ты занимаешься вместо сна", Case.NOMINATIVE),
        Template("Вот тебе %s. Можно было просто выйти на улицу - там то же самое", Case.ACCUSATIVE),
        Template("Нашла %s. Ты просил — теперь живи с этим", Case.ACCUSATIVE),
        Template("Глянь на %s. Красота требует жертв.", Case.ACCUSATIVE),
        Template("Смотри на %s, как на зеркало души", Case.ACCUSATIVE),
        Template("Держи %s — минимум усилий, максимум позора", Case.ACCUSATIVE),
        Template("Вот тебе %s. За такую просьбу стыдно даже мне", Case.ACCUSATIVE),
        Template("Вот тебе %s. Сохрани, чтобы потом стыдно было дважды", Case.ACCUSATIVE),
        Template("Глянь на %s! повторил бы?", Case.ACCUSATIVE),
        Template("Вот %s. Как в том фильме из детства..", Case.NOMINATIVE),
        Template("Смотри на %s, будто это нормально. Спойлер: нет", Case.ACCUSATIVE),
        Template("Вот %s — выглядит как твой поисковый запрос в 3 ночи", Case.NOMINATIVE),
        Template("Глянь на %s. Вот ради чего ты проснулся сегодня", Case.ACCUSATIVE),
        Template("Вот %s. Можно было заняться спортом — выбрал это", Case.NOMINATIVE),
        Template("Смотри на %s. Твои друзья думают, что ты работаешь", Case.ACCUSATIVE),
        Template("Лови %s. Лучший аргумент, почему тебя не зовут на свидания", Case.ACCUSATIVE),
        Template("Смотри на %s. Ты просил — я выполнила — оба виноваты", Case.ACCUSATIVE),
        Template("Держи %s — доказательство, что тебе можно всё… к сожалению", Case.ACCUSATIVE),
        Template("Лови %s и не говори, что я тебя не балую", Case.ACCUSATIVE),
        Template("%s готово к просмотру. Ты — к последствиям?", Case.NOMINATIVE)
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
