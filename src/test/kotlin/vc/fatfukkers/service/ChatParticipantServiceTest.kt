package vc.fatfukkers.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import vc.fatfukkers.db.ChatParticipants
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatParticipantServiceTest {

    companion object {
        init {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:file:chat-participants-test?mode=memory&cache=shared"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
            })
            Database.connect(ds)
            transaction { SchemaUtils.create(ChatParticipants) }
        }
    }

    @BeforeTest
    fun cleanup() {
        transaction { ChatParticipants.deleteAll() }
    }

    @Test
    fun `displayNickname prefers username with at`() {
        assertEquals("@alice", ChatParticipantService.displayNickname("alice", "Алиса"))
        assertEquals("Алиса", ChatParticipantService.displayNickname(null, "Алиса"))
        assertNull(ChatParticipantService.displayNickname(null, null))
        assertNull(ChatParticipantService.displayNickname("  ", "  "))
    }

    @Test
    fun `remember stores and updates nickname`() {
        ChatParticipantService.remember(chatId = -100L, telegramUserId = 1L, nickname = "@old")
        ChatParticipantService.remember(chatId = -100L, telegramUserId = 1L, nickname = "@new")

        val others = ChatParticipantService.otherParticipants(chatId = -100L, excludeUserId = 2L)
        assertEquals(listOf("@new"), others.map { it.nickname })
    }

    @Test
    fun `otherParticipants excludes asker and other chats`() {
        ChatParticipantService.remember(-100L, 1L, "@asker")
        ChatParticipantService.remember(-100L, 2L, "@bob")
        ChatParticipantService.remember(-100L, 3L, "@carol")
        ChatParticipantService.remember(-200L, 4L, "@otherchat")

        val others = ChatParticipantService.otherParticipants(-100L, excludeUserId = 1L)
        assertEquals(setOf("@bob", "@carol"), others.map { it.nickname }.toSet())
    }

    @Test
    fun `otherParticipants empty when alone in chat`() {
        ChatParticipantService.remember(-100L, 1L, "@only")
        assertEquals(emptyList(), ChatParticipantService.otherParticipants(-100L, excludeUserId = 1L))
    }

    @Test
    fun `appendMessage keeps last 20 messages glued`() {
        val user = BotUser(telegramId = 2L, username = "bob", firstName = "Bob", lastName = null)
        ChatParticipantService.rememberFromUser(-100L, user, "first")
        repeat(25) { i ->
            ChatParticipantService.rememberFromUser(-100L, user, "msg$i")
        }

        val bob = ChatParticipantService.otherParticipants(-100L, excludeUserId = 1L).single()
        val lines = bob.recentMessages.lines()
        assertEquals(20, lines.size)
        assertEquals("msg5", lines.first())
        assertEquals("msg24", lines.last())
        assertFalse(bob.recentMessages.contains("first"))
    }

    @Test
    fun `normalizeMessage truncates to 280 chars`() {
        val long = "а".repeat(400)
        val normalized = ChatParticipantService.normalizeMessage(long)!!
        assertEquals(280, normalized.length)

        val user = BotUser(telegramId = 2L, username = "bob", firstName = "Bob", lastName = null)
        ChatParticipantService.rememberFromUser(-100L, user, long)
        val stored = ChatParticipantService.participantsInChat(-100L).single().recentMessages
        assertEquals(280, stored.length)
    }

    @Test
    fun `buildSystemPrompt includes all participants including asker`() {
        val asker = BotUser(1L, "alice", "Alice", null)
        val bob = BotUser(2L, "bob", "Bob", null)
        val carol = BotUser(3L, "carol", "Carol", null)
        ChatParticipantService.rememberFromUser(-100L, asker, "алиса спрашивает про бег")
        ChatParticipantService.rememberFromUser(-100L, bob, "боб любит бег")
        ChatParticipantService.rememberFromUser(-100L, bob, "боб ест курицу")
        ChatParticipantService.rememberFromUser(-100L, carol, "кэрол на диете")

        val withList = TrainerService.buildSystemPrompt(chatId = -100L)
        assertTrue(withList.contains("Участники этого чата"), withList)
        assertTrue(withList.contains("@alice"), withList)
        assertTrue(withList.contains("алиса спрашивает про бег"), withList)
        assertTrue(withList.contains("@bob"), withList)
        assertTrue(withList.contains("боб любит бег"), withList)
        assertTrue(withList.contains("боб ест курицу"), withList)
        assertTrue(withList.contains("@carol"), withList)
        assertTrue(withList.contains("кэрол на диете"), withList)

        ChatParticipantService.remember(-200L, 10L, "@alone")
        val alone = TrainerService.buildSystemPrompt(chatId = -200L)
        assertFalse(alone.contains("Участники этого чата"))

        ChatParticipantService.rememberFromUser(-300L, BotUser(10L, "solo", "Solo", null), "я одна в чате")
        val aloneWithMsgs = TrainerService.buildSystemPrompt(chatId = -300L)
        assertTrue(aloneWithMsgs.contains("@solo"), aloneWithMsgs)
        assertTrue(aloneWithMsgs.contains("я одна в чате"), aloneWithMsgs)
    }

    @Test
    fun `resetContext clears asker and trainer messages in chat`() {
        val asker = BotUser(1L, "alice", "Alice", null)
        val bob = BotUser(2L, "bob", "Bob", null)
        ChatParticipantService.rememberFromUser(-100L, asker, "вопрос алисы")
        ChatParticipantService.rememberFromUser(-100L, bob, "реплика боба")
        ChatParticipantService.rememberTrainerReply(-100L, "ответ тренера")

        TrainerService.resetContext(-100L, asker.telegramId)

        val participants = ChatParticipantService.participantsInChat(-100L)
        assertEquals("", participants.first { it.nickname == "@alice" }.recentMessages)
        assertEquals("реплика боба", participants.first { it.nickname == "@bob" }.recentMessages)
        assertEquals("", ChatParticipantService.trainerRecentMessages(-100L))
    }

    @Test
    fun `resetAllContext clears every participant in chat`() {
        val asker = BotUser(1L, "alice", "Alice", null)
        val bob = BotUser(2L, "bob", "Bob", null)
        ChatParticipantService.rememberFromUser(-100L, asker, "вопрос алисы")
        ChatParticipantService.rememberFromUser(-100L, bob, "реплика боба")
        ChatParticipantService.rememberTrainerReply(-100L, "ответ тренера")

        TrainerService.resetAllContext(-100L)

        val participants = ChatParticipantService.participantsInChat(-100L)
        assertTrue(participants.all { it.recentMessages.isEmpty() })
        assertEquals("", ChatParticipantService.trainerRecentMessages(-100L))
    }

    @Test
    fun `rememberTrainerReply keeps last 20 and goes into prompt`() {
        ChatParticipantService.rememberTrainerReply(-100L, "привет, котик")
        repeat(25) { i ->
            ChatParticipantService.rememberTrainerReply(-100L, "ответ$i")
        }

        val stored = ChatParticipantService.trainerRecentMessages(-100L).lines()
        assertEquals(20, stored.size)
        assertEquals("ответ5", stored.first())
        assertEquals("ответ24", stored.last())
        assertFalse(ChatParticipantService.trainerRecentMessages(-100L).contains("привет"))

        // Тренер не попадает в список участников для упоминаний
        assertTrue(
            ChatParticipantService.otherParticipants(-100L, excludeUserId = 1L).isEmpty(),
        )

        val prompt = TrainerService.buildSystemPrompt(chatId = -100L)
        assertTrue(prompt.contains("Твои предыдущие ответы"), prompt)
        assertTrue(prompt.contains("ответ24"), prompt)
    }
}
