package vc.fatfukkers.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCaptionPhrasesTest {
    @Test
    fun `toAccusative keeps already accusative noun`() {
        assertEquals("голову", RussianMorph.toAccusative("голову"))
    }

    @Test
    fun `toAccusative converts nominative feminine`() {
        assertEquals("голову", RussianMorph.toAccusative("голова"))
        assertEquals("машину", RussianMorph.toAccusative("машина"))
        assertEquals("кота", RussianMorph.toAccusative("кот"))
    }

    @Test
    fun `toAccusative keeps inanimate masculine`() {
        assertEquals("самолет", RussianMorph.toAccusative("самолет"))
        assertEquals("мем", RussianMorph.toAccusative("мем"))
    }

    @Test
    fun `clause-like queries are left unchanged`() {
        assertEquals("как котики ходят", RussianMorph.toAccusative("как котики ходят"))
        assertEquals("как самолет летит", RussianMorph.toAccusative("как самолет летит"))
        assertTrue(RussianMorph.isClauseLike("как котики ходят"))
        assertTrue(RussianMorph.isClauseLike("как самолет летит"))
        assertFalse(RussianMorph.isClauseLike("голову"))
    }

    @Test
    fun `random uses noun templates for simple queries`() {
        val caption = ImageCaptionPhrases.random("голову", Random(0))
        assertTrue(caption.contains("голову"))
        assertTrue(nounTemplates.any { template -> caption == template.format("голову") })
    }

    @Test
    fun `random uses clause templates for как queries`() {
        val query = "как котики ходят"
        val caption = ImageCaptionPhrases.random(query, Random(0))
        assertTrue(caption.contains(query))
        assertTrue(clauseTemplates.any { template -> caption == template.format(query) })
    }

    @Test
    fun `user examples produce grammatical captions`() {
        val head = ImageCaptionPhrases.random("голову", Random(42))
        assertTrue(head.contains("голову"))
        assertFalse(head.contains("головы"))
        assertTrue(nounTemplates.any { head == it.format("голову") })

        val cats = ImageCaptionPhrases.random("как котики ходят", Random(42))
        assertTrue(cats.contains("как котики ходят"))
        assertTrue(clauseTemplates.any { cats == it.format("как котики ходят") })
        assertFalse(cats.contains("на как"))

        val plane = ImageCaptionPhrases.random("как самолет летит", Random(42))
        assertTrue(plane.contains("как самолет летит"))
        assertTrue(clauseTemplates.any { plane == it.format("как самолет летит") })
        assertFalse(plane.contains("на как"))
    }

    private companion object {
        val nounTemplates = listOf(
            "Теперь ты сидишь и смотришь на %s, ты доволен?",
            "Лови %s, как просил",
            "Держи %s и молчи",
            "Вот %s, наслаждайся",
            "Ты хотел %s? Получай",
            "Смотри на %s и радуйся",
            "Вот тебе %s — больше не проси",
            "Нашла %s специально для тебя",
            "Оценивай %s",
            "Полюбуйся — вот %s",
        )

        val clauseTemplates = listOf(
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
    }
}
