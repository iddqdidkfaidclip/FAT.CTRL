package vc.fatfukkers

import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BuildTrainerQueryTest {
    private fun trainerMessage(text: String) = Message(
        messageId = 1L,
        date = 0L,
        chat = Chat(id = 1L, type = "private"),
        text = text,
    )

    @Test
    fun `reply without quote uses full trainer message`() {
        val query = buildTrainerQuery(
            rawText = "а почему?",
            replyToTrainerMessage = trainerMessage("Длинный ответ тренера про бег и питание"),
        )
        assertEquals(
            "Пользователь отвечает на моё сообщение: «Длинный ответ тренера про бег и питание».\nЕго вопрос: а почему?",
            query,
        )
    }

    @Test
    fun `reply with quote uses only quoted fragment`() {
        val query = buildTrainerQuery(
            rawText = "а почему?",
            replyToTrainerMessage = trainerMessage("Длинный ответ тренера про бег и питание"),
            quotedText = "про бег",
        )
        assertEquals(
            "Пользователь отвечает на моё сообщение: «про бег».\nЕго вопрос: а почему?",
            query,
        )
    }

    @Test
    fun `blank quote falls back to full trainer message`() {
        val query = buildTrainerQuery(
            rawText = "уточни",
            replyToTrainerMessage = trainerMessage("полный текст"),
            quotedText = "   ",
        )
        assertEquals(
            "Пользователь отвечает на моё сообщение: «полный текст».\nЕго вопрос: уточни",
            query,
        )
    }

    @Test
    fun `no reply keeps only user text`() {
        val query = buildTrainerQuery(
            rawText = "тренер как питаться?",
            replyToTrainerMessage = null,
        )
        assertEquals("как питаться?", query)
        assertFalse(query.contains("Пользователь отвечает"))
    }
}
