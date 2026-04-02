package vc.fatfukkers

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import vc.fatfukkers.service.ActivityService
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.WeightChartService
import vc.fatfukkers.service.WeightService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

fun Bot.handleTask(
    task: Task,
    rawText: String,
    update: Update,
    message: Message,
    weightService: WeightService,
    activityService: ActivityService,
    zoneId: ZoneId
) {
    val u = UserService.upsertFromUpdate(update)
    val chatId = ChatId.fromId(message.chat.id)
    val current = weightService.getLastWeight(u.telegramId)
    val goalBefore = UserService.getGoalWeight(u.telegramId)

    fun buildMention(statusWord: String, kgHint: String? = null): String {
        val base = u.username?.let { "@$it" } ?: (u.firstName ?: "жиробасина")
        return if (kgHint != null) "$base $statusWord ($kgHint)" else "$base $statusWord"
    }

    val mention = buildMention(
        resolveStatusWord(current = current, goal = goalBefore),
        resolveKgToNextStatus(current = current, goal = goalBefore)
    )

    fun sendProgressWithChart(mentionStr: String, prefixLine: String? = null) {
        val progressMsg = weightService.getProgressMessage(u.telegramId)
        val caption = buildString {
            if (prefixLine != null) appendLine(prefixLine).appendLine()
            append(progressMsg)
        }
        val chartBytes = WeightChartService.buildChartBytes(
            telegramUserId = u.telegramId,
            goal = UserService.getGoalWeight(u.telegramId)
        )
        if (chartBytes != null) {
            sendPhoto(
                chatId = chatId,
                photo = TelegramFile.ByByteArray(chartBytes, "progress.png"),
                caption = "$mentionStr\n$caption"
            )
        } else {
            sendMessage(chatId, "$mentionStr\n$caption")
        }
    }

    when (task) {
        is Task.Weight -> {
            val lower = rawText.lowercase(java.util.Locale("ru", "RU"))
            if (lower.contains("отмена")) {
                val res = weightService.cancelLastWeight(u.telegramId)
                sendMessage(chatId, "$mention, ${res.message}")
            } else {
                val weight = Regex("""\d+([.,]\d+)?""").find(rawText)?.value
                val w = weight?.replace(',', '.')?.toDoubleOrNull()
                if (w == null) {
                    sendMessage(chatId, "$mention, \uD83E\uDEE8 напиши сколько! например: \"вес 99.9\"")
                } else {
                    if (w <= 0.0 || w > 200.0) {
                        sendMessage(chatId, "$mention, хуйню то не неси \uD83E\uDDD0 напиши правду, например: \"вес 99.9\"")
                        return
                    } else {
                        val today = LocalDate.now(zoneId)
                        val res = weightService.tryAddWeight(u.telegramId, today, w)
                        if (res.ok) {
                            val lastAfter = weightService.getLastWeight(u.telegramId)
                            val goalNow = UserService.getGoalWeight(u.telegramId)
                            val mentionAfterSave = buildMention(
                                resolveStatusWord(current = lastAfter, goal = goalNow),
                                resolveKgToNextStatus(current = lastAfter, goal = goalNow)
                            )
                            sendProgressWithChart(mentionAfterSave, prefixLine = res.message)
                        } else {
                            sendMessage(chatId, "$mention, ${res.message}")
                        }
                    }
                }
            }
        }
        is Task.Activity -> {
            val msg = activityService.getOrAssignToday(u.telegramId).message
            sendMessage(chatId, "$mention, $msg")
        }
        is Task.Progress -> {
            sendProgressWithChart(mention)
        }
        is Task.Goal -> {
            val num = Regex("""\d+([.,]\d+)?""").find(rawText)?.value
            val g = num?.replace(',', '.')?.toDoubleOrNull()
            if (g == null || g <= 0.0 || g > 300.0) {
                sendMessage(chatId, "$mention, укажи нормальную цель, например: \"цель 75\"")
            } else {
                val newGoal = BigDecimal.valueOf(g).setScale(2, BigDecimal.ROUND_HALF_UP)
                val msg = UserService.setGoalWeight(u.telegramId, newGoal)
                val mentionAfter = buildMention(
                    resolveStatusWord(current = current, goal = newGoal),
                    resolveKgToNextStatus(current = current, goal = newGoal)
                )
                sendMessage(chatId, "$mentionAfter, $msg")
            }
        }
    }
}

sealed class Task {
    data object Weight : Task()
    data object Activity : Task()
    data object Progress : Task()
    data object Goal : Task()
}

enum class BotTask(val taskName: String) {
    WEIGHT("вес"),
    ACTIVITY("задание"),
    PROGRESS("прогресс"),
    GOAL("цель")
}

private fun resolveStatusWord(current: BigDecimal?, goal: BigDecimal?): String {
    if (current == null || goal == null) return "толстый толстячок"

    val diff = current.subtract(goal)
    if (diff.signum() <= 0) return "достигший цели"

    val remaining = diff
    if (remaining <= BigDecimal("2.0")) return "умничка"

    val sixPercent   = current.multiply(BigDecimal("0.06"))
    val tenPercent   = current.multiply(BigDecimal("0.10"))
    val twentyPercent = current.multiply(BigDecimal("0.20"))

    return when {
        remaining <= sixPercent    -> "худой толстячок"
        remaining <= tenPercent    -> "толстый толстячок"
        remaining <= twentyPercent -> "худая жиробасина"
        else                       -> "жирная жиробасина"
    }
}

private fun resolveKgToNextStatus(current: BigDecimal?, goal: BigDecimal?): String? {
    if (current == null || goal == null) return null

    val diff = current.subtract(goal)
    if (diff.signum() <= 0) return null

    val remaining     = diff
    val sixPercent    = current.multiply(BigDecimal("0.06"))
    val tenPercent    = current.multiply(BigDecimal("0.10"))
    val twentyPercent = current.multiply(BigDecimal("0.20"))

    return when {
        remaining <= BigDecimal("2.0") -> {
            // умничка → достигший цели
            val needed = remaining.setScale(1, RoundingMode.CEILING)
            "ещё $needed кг до цели"
        }
        remaining <= sixPercent -> {
            // худой толстячок → умничка
            val needed = remaining.subtract(BigDecimal("2.0")).setScale(1, RoundingMode.CEILING)
            "ещё $needed кг до умнички"
        }
        remaining <= tenPercent -> {
            // толстый толстячок → худой толстячок: (remaining − 6%) / 0.94
            val needed = remaining.subtract(sixPercent)
                .divide(BigDecimal("0.94"), 1, RoundingMode.CEILING)
            "ещё $needed кг до худого толстячка"
        }
        remaining <= twentyPercent -> {
            // худая жиробасина → толстый толстячок: (remaining − 10%) / 0.90
            val needed = remaining.subtract(tenPercent)
                .divide(BigDecimal("0.90"), 1, RoundingMode.CEILING)
            "ещё $needed кг до толстого толстячка"
        }
        else -> {
            // жирная жиробасина → худая жиробасина: (remaining − 20%) / 0.80
            val needed = remaining.subtract(twentyPercent)
                .divide(BigDecimal("0.80"), 1, RoundingMode.CEILING)
            "ещё $needed кг до худой жиробасины"
        }
    }
}
