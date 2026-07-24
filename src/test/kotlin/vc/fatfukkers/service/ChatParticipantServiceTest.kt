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
    fun `buildSystemPrompt includes per-participant message context`() {
        val bob = BotUser(2L, "bob", "Bob", null)
        val carol = BotUser(3L, "carol", "Carol", null)
        ChatParticipantService.rememberFromUser(-100L, bob, "боб любит бег")
        ChatParticipantService.rememberFromUser(-100L, bob, "боб ест курицу")
        ChatParticipantService.rememberFromUser(-100L, carol, "кэрол на диете")

        val withList = TrainerService.buildSystemPrompt(chatId = -100L, askerUserId = 1L)
        assertTrue(withList.contains("Участники этого чата"), withList)
        assertTrue(withList.contains("@bob"), withList)
        assertTrue(withList.contains("боб любит бег"), withList)
        assertTrue(withList.contains("боб ест курицу"), withList)
        assertTrue(withList.contains("@carol"), withList)
        assertTrue(withList.contains("кэрол на диете"), withList)

        ChatParticipantService.remember(-200L, 10L, "@alone")
        val alone = TrainerService.buildSystemPrompt(chatId = -200L, askerUserId = 10L)
        assertFalse(alone.contains("Участники этого чата"))
    }
}
