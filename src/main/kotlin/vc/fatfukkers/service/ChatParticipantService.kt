package vc.fatfukkers.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vc.fatfukkers.db.ChatParticipants

data class ChatParticipantSnapshot(
    val nickname: String,
    /** Последние сообщения, склеенные через перевод строки. */
    val recentMessages: String,
)

object ChatParticipantService {
    /** Служебный id для сообщений самой Тренер в чате. */
    internal const val TRAINER_TELEGRAM_USER_ID = 0L
    private const val TRAINER_NICKNAME = "Тренер"
    private const val MAX_RECENT_MESSAGES = 20
    private const val MAX_MESSAGE_CHARS = 280

    fun displayNickname(username: String?, firstName: String?): String? {
        username?.trim()?.takeIf { it.isNotEmpty() }?.let { return "@$it" }
        firstName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    fun remember(chatId: Long, telegramUserId: Long, nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()

        transaction {
            ChatParticipants.insertIgnore {
                it[ChatParticipants.chatId] = chatId
                it[ChatParticipants.telegramUserId] = telegramUserId
                it[ChatParticipants.nickname] = trimmed
                it[ChatParticipants.recentMessages] = ""
                it[ChatParticipants.createdAtEpochMs] = now
            }
            ChatParticipants.update({
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.telegramUserId eq telegramUserId)
            }) {
                it[ChatParticipants.nickname] = trimmed
            }
        }
    }

    /**
     * Запоминает участника; если передан текст сообщения — дописывает его
     * в контекст (хранятся последние [MAX_RECENT_MESSAGES] сообщений).
     */
    fun rememberFromUser(chatId: Long, user: BotUser, messageText: String? = null) {
        val nick = displayNickname(user.username, user.firstName) ?: return
        remember(chatId, user.telegramId, nick)
        val text = messageText?.trim()?.takeIf { it.isNotEmpty() } ?: return
        appendMessage(chatId, user.telegramId, text)
    }

    /** Сохраняет ответ Тренер в контексте чата (последние [MAX_RECENT_MESSAGES]). */
    fun rememberTrainerReply(chatId: Long, messageText: String) {
        remember(chatId, TRAINER_TELEGRAM_USER_ID, TRAINER_NICKNAME)
        appendMessage(chatId, TRAINER_TELEGRAM_USER_ID, stripHtml(messageText))
    }

    fun trainerRecentMessages(chatId: Long): String =
        transaction {
            ChatParticipants
                .selectAll()
                .where {
                    (ChatParticipants.chatId eq chatId) and
                        (ChatParticipants.telegramUserId eq TRAINER_TELEGRAM_USER_ID)
                }
                .firstOrNull()
                ?.get(ChatParticipants.recentMessages)
                .orEmpty()
        }

    fun appendMessage(chatId: Long, telegramUserId: Long, messageText: String) {
        val line = normalizeMessage(messageText) ?: return

        transaction {
            val current = ChatParticipants
                .selectAll()
                .where {
                    (ChatParticipants.chatId eq chatId) and
                        (ChatParticipants.telegramUserId eq telegramUserId)
                }
                .firstOrNull()
                ?.get(ChatParticipants.recentMessages)
                .orEmpty()

            val next = (parseMessages(current) + line).takeLast(MAX_RECENT_MESSAGES)
            ChatParticipants.update({
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.telegramUserId eq telegramUserId)
            }) {
                it[ChatParticipants.recentMessages] = next.joinToString("\n")
            }
        }
    }

    /** Очищает сохранённые сообщения участника в чате. */
    fun clearRecentMessages(chatId: Long, telegramUserId: Long) {
        transaction {
            ChatParticipants.update({
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.telegramUserId eq telegramUserId)
            }) {
                it[ChatParticipants.recentMessages] = ""
            }
        }
    }

    /** Очищает сохранённые сообщения всех участников чата (включая Тренер). */
    fun clearAllRecentMessages(chatId: Long) {
        transaction {
            ChatParticipants.update({ ChatParticipants.chatId eq chatId }) {
                it[ChatParticipants.recentMessages] = ""
            }
        }
    }

    /** Все участники чата с их недавним контекстом (кроме самой Тренер). */
    fun participantsInChat(chatId: Long): List<ChatParticipantSnapshot> =
        loadParticipants(chatId, excludeUserId = null)

    /** Другие участники чата (не автор и не сама Тренер). */
    fun otherParticipants(chatId: Long, excludeUserId: Long): List<ChatParticipantSnapshot> =
        loadParticipants(chatId, excludeUserId = excludeUserId)

    private fun loadParticipants(
        chatId: Long,
        excludeUserId: Long?,
    ): List<ChatParticipantSnapshot> =
        transaction {
            ChatParticipants
                .selectAll()
                .where {
                    var cond = (ChatParticipants.chatId eq chatId) and
                        (ChatParticipants.telegramUserId neq TRAINER_TELEGRAM_USER_ID)
                    if (excludeUserId != null) {
                        cond = cond and (ChatParticipants.telegramUserId neq excludeUserId)
                    }
                    cond
                }
                .map {
                    ChatParticipantSnapshot(
                        nickname = it[ChatParticipants.nickname],
                        recentMessages = it[ChatParticipants.recentMessages].orEmpty(),
                    )
                }
                .filter { it.nickname.isNotBlank() }
                .distinctBy { it.nickname }
                .sortedBy { it.nickname }
        }

    internal fun normalizeMessage(text: String): String? {
        val line = text.trim()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(MAX_MESSAGE_CHARS)
            .trim()
        return line.takeIf { it.isNotEmpty() }
    }

    internal fun parseMessages(stored: String): List<String> =
        stored.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]+>"), " ")
}
