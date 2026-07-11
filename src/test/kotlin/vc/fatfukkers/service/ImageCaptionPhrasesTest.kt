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
        assertEquals("судака", RussianMorph.toAccusative("судака"))
        assertEquals("кота", RussianMorph.toAccusative("кота"))
    }

    @Test
    fun `toAccusative converts nominative feminine`() {
        assertEquals("голову", RussianMorph.toAccusative("голова"))
        assertEquals("машину", RussianMorph.toAccusative("машина"))
        assertEquals("собаку", RussianMorph.toAccusative("собака"))
        assertEquals("кота", RussianMorph.toAccusative("кот"))
    }

    @Test
    fun `toAccusative does not produce dative from masculine accusative`() {
        assertFalse(RussianMorph.toAccusative("судака").endsWith("у"))
        assertEquals("судака", RussianMorph.toAccusative("судака"))
    }

    @Test
    fun `toNominative converts masculine accusative and feminine accusative`() {
        assertEquals("судак", RussianMorph.toNominative("судака"))
        assertEquals("кот", RussianMorph.toNominative("кота"))
        assertEquals("голова", RussianMorph.toNominative("голову"))
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
    fun `sudak in nominative template`() {
        assertEquals("судак", RussianMorph.toNominative("судака"))
        assertEquals(
            "Полюбуйся — вот судак",
            "Полюбуйся — вот %s".format(RussianMorph.toNominative("судака")),
        )
    }

    @Test
    fun `random uses noun templates for simple queries`() {
        val caption = ImageCaptionPhrases.random("голову", Random(0))
        assertTrue(caption.contains("голову") || caption.contains("голова"))
    }

    @Test
    fun `random uses clause templates for как queries`() {
        val query = "как котики ходят"
        val caption = ImageCaptionPhrases.random(query, Random(0))
        assertTrue(caption.contains(query))
        assertFalse(caption.contains("на как"))
    }

    @Test
    fun `user examples produce grammatical captions`() {
        val head = ImageCaptionPhrases.random("голову", Random(42))
        assertTrue(head.contains("голову") || head.contains("голова"))

        val cats = ImageCaptionPhrases.random("как котики ходят", Random(42))
        assertTrue(cats.contains("как котики ходят"))
        assertFalse(cats.contains("на как"))

        val plane = ImageCaptionPhrases.random("как самолет летит", Random(42))
        assertTrue(plane.contains("как самолет летит"))
        assertFalse(plane.contains("на как"))
    }
}
