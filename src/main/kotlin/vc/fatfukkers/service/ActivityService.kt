package vc.fatfukkers.service

import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vc.fatfukkers.db.Activities
import vc.fatfukkers.db.DailyAssignments
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random as KRandom

class ActivityService(private val zoneId: ZoneId) {
    data class Assignment(val dateIso: String, val activity: String, val reps: Int)

    data class AssignmentResult(val ok: Boolean, val message: String)

    fun getOrAssignToday(telegramUserId: Long): AssignmentResult {
        val today = LocalDate.now(zoneId)
        val iso = today.toString()
        val now = System.currentTimeMillis()

        return transaction {
            val existing = DailyAssignments
                .innerJoin(Activities)
                .selectAll()
                .where { (DailyAssignments.telegramUserId eq telegramUserId) and (DailyAssignments.dateIso eq iso) }
                .limit(1)
                .firstOrNull()

            if (existing != null) {
                val activity = existing[Activities.name]
                val reps = existing[DailyAssignments.reps]
                return@transaction AssignmentResult(
                    ok = true,
                    message = "Сегодня ($iso) для тебя:\n$reps × $activity\n(Уже назначено на сегодня, отдохни до завтра) \uD83D\uDE1C"
                )
            }

            val activityRow = pickRandomActiveActivity()
                ?: return@transaction AssignmentResult(
                    ok = false,
                    message = "Нет активностей, зовите админа!"
                )

            val activityId = activityRow[Activities.id].value
            val name = activityRow[Activities.name]
            val min = activityRow[Activities.minReps]
            val max = activityRow[Activities.maxReps]
            val raw = KRandom.nextInt(min.coerceAtLeast(19), (max.coerceAtMost(59)) + 1)
            val reps = ((raw + 2) / 5) * 5

            DailyAssignments.insert {
                it[DailyAssignments.telegramUserId] = telegramUserId
                it[DailyAssignments.dateIso] = iso
                it[DailyAssignments.activityId] = activityId
                it[DailyAssignments.reps] = reps
                it[DailyAssignments.createdAtEpochMs] = now
            }

            AssignmentResult(ok = true, message = "Сегодня ($iso) для тебя:\n$reps × $name \uD83D\uDE0A")
        }
    }

    private fun pickRandomActiveActivity(): ResultRow? {
        // SQLite: ORDER BY RANDOM() is fine for small tables
        return Activities
            .selectAll()
            .where { Activities.isActive eq true }
            .orderBy(Random() to SortOrder.ASC)
            .limit(1)
            .firstOrNull()
    }

    companion object {
        fun ensureSeedData() {
            transaction {
                val any = Activities.selectAll().limit(1).count() > 0
                if (any) return@transaction

                val defaults = listOf(
                    "отжимания",
                    "приседания",
                    "планка (секунд)",
                    "выпады",
                    "берпи",
                    "прыжки на месте",
                    "подъемы ног лежа",
                    "скручивания на пресс",
                    "приседания с прыжком",
                    "велосипед лежа для пресса (секунд)",
                    "прыжки через невидимую скакалку (секунд)",
                    "отжимания с колен"
                )

                defaults.forEach { name ->
                    Activities.insert {
                        it[Activities.name] = name
                        it[Activities.minReps] = 20
                        it[Activities.maxReps] = 60
                        it[Activities.isActive] = true
                    }
                }
            }
        }
    }
}

