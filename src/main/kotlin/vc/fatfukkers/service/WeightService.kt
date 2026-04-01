package vc.fatfukkers.service

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vc.fatfukkers.db.WeightEntries
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

data class ActionResult(val ok: Boolean, val message: String)

class WeightService(private val zoneId: ZoneId) {

    fun tryAddWeight(telegramUserId: Long, date: LocalDate, weightKg: Double): ActionResult {
        val iso = date.toString()
        val now = System.currentTimeMillis()
        val w = BigDecimal.valueOf(weightKg).setScale(2, RoundingMode.HALF_UP)

        return transaction {
            val already = WeightEntries
                .selectAll()
                .where { (WeightEntries.telegramUserId eq telegramUserId) and (WeightEntries.dateIso eq iso) }
                .limit(1)
                .count() > 0

            if (already) {
                return@transaction ActionResult(
                    ok = false,
                    message = "\uD83E\uDEE2 ты уже сохранил свой вес $iso. Напиши завтра снова."
                )
            }

            WeightEntries.insert {
                it[WeightEntries.telegramUserId] = telegramUserId
                it[WeightEntries.dateIso] = iso
                it[WeightEntries.weightKg] = w
                it[WeightEntries.createdAtEpochMs] = now
            }

            ActionResult(ok = true, message = "Схоронил! $w кг на $iso. \uD83D\uDE42\u200D↕\uFE0F")
        }
    }

    fun getProgressMessage(telegramUserId: Long): String {
        val rows = transaction {
            WeightEntries
                .selectAll()
                .where { WeightEntries.telegramUserId eq telegramUserId }
                .orderBy(WeightEntries.dateIso to SortOrder.ASC)
                .toList()
        }

        if (rows.isEmpty()) {
            return "Нет данных. Присылай мне свой вес раз в день. \uD83D\uDE36\u200D\uD83C\uDF2B\uFE0F"
        }

        val firstW = rows.first()[WeightEntries.weightKg]
        val lastW  = rows.last()[WeightEntries.weightKg]
        val delta  = lastW.subtract(firstW)

        val sincePrev = if (rows.size >= 2) {
            lastW.subtract(rows[rows.size - 2][WeightEntries.weightKg])
        } else null

        val goal = UserService.getGoalWeight(telegramUserId)

        return buildString {
            val f1 = firstW.setScale(1, RoundingMode.HALF_UP)
            val f2 = lastW.setScale(1, RoundingMode.HALF_UP)

            if (goal != null) {
                val diffToGoal = lastW.subtract(goal) // > 0: надо худеть, < 0: надо набирать
                when {
                    diffToGoal.signum() == 0 -> {
                        appendLine("\uD83C\uDFC6 Ты достиг цели ${goal.setScale(1, RoundingMode.HALF_UP)} кг! \uD83C\uDF89")
                    }
                    diffToGoal.signum() > 0 -> {
                        val remaining = diffToGoal.setScale(1, RoundingMode.HALF_UP)
                        appendLine("\u2696\uFE0F Ты начинал с $f1 кг, уже $f2 кг!")
                        appendLine("\uD83C\uDFAF До цели осталось сбросить $remaining кг.")
                        appendLine("\uD83D\uDD25 Худей братик, ХУДЕЙ!")
                    }
                    else -> {
                        val remaining = diffToGoal.abs().setScale(1, RoundingMode.HALF_UP)
                        appendLine("\u2696\uFE0F Ты начинал с $f1 кг, уже $f2 кг!")
                        appendLine("\uD83C\uDFAF До цели осталось набрать $remaining кг.")
                        appendLine("\uD83C\uDF54 Толстей братик, ТОЛСТЕЙ!")
                    }
                }
            } else {
                appendLine("\u2696\uFE0F Ты начинал с $f1 кг, уже $f2 кг!")
                appendLine("\uD83D\uDCCC Установи цель чтобы стремиться к ней.")
            }

            append("\uD83D\uDCCA С начала: ${formatSigned(delta)} кг")
            if (sincePrev != null) append(" | с прошлого: ${formatSigned(sincePrev)} кг")
        }.trim()
    }

    fun getLastWeight(telegramUserId: Long) =
        transaction {
            WeightEntries
                .selectAll()
                .where { WeightEntries.telegramUserId eq telegramUserId }
                .orderBy(WeightEntries.dateIso to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(WeightEntries.weightKg)
        }

    private fun formatSigned(x: BigDecimal): String {
        val s = x.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (x.signum() > 0) "+$s" else s
    }
}

