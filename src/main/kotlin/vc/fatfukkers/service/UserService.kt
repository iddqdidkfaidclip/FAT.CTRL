package vc.fatfukkers.service

import com.github.kotlintelegrambot.entities.Update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vc.fatfukkers.db.Users
import java.math.BigDecimal

data class BotUser(
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

object UserService {
    fun upsertFromUpdate(update: Update): BotUser {
        val from = update.message?.from ?: update.editedMessage?.from
        requireNotNull(from) { "Update has no user info" }

        val telegramId = from.id
        val username = from.username
        val firstName = from.firstName
        val lastName = from.lastName
        val now = System.currentTimeMillis()

        transaction {
            Users.insertIgnore {
                it[Users.telegramId] = telegramId
                it[Users.username] = username
                it[Users.firstName] = firstName
                it[Users.lastName] = lastName
                it[Users.createdAtEpochMs] = now
            }

            Users.update({ Users.telegramId eq telegramId }) {
                it[Users.username] = username
                it[Users.firstName] = firstName
                it[Users.lastName] = lastName
            }
        }

        return BotUser(telegramId, username, firstName, lastName)
    }

    fun exists(telegramId: Long): Boolean =
        transaction { Users.selectAll().where { Users.telegramId eq telegramId }.limit(1).count() > 0 }

    fun setGoalWeight(telegramId: Long, goalKg: BigDecimal): String {
        transaction {
            Users.update({ Users.telegramId eq telegramId }) {
                it[Users.goalWeightKg] = goalKg
            }
        }
        return "твоя цель теперь ${goalKg.setScale(1)} кг 💪"
    }

    fun getGoalWeight(telegramId: Long): BigDecimal? =
        transaction {
            Users
                .selectAll()
                .where { Users.telegramId eq telegramId }
                .firstOrNull()
                ?.get(Users.goalWeightKg)
        }
}

