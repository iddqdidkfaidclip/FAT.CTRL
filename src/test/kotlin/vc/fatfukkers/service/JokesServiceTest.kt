package vc.fatfukkers.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JokesServiceTest {

    @Test
    fun `random returns non blank joke from resource file`() {
        val joke = JokesService.random()
        assertTrue(joke.isNotBlank())
        assertTrue(joke.length > 20)
    }
}
