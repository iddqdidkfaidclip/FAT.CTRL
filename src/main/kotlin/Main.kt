package vc.fatfukkers

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import org.slf4j.LoggerFactory
import vc.fatfukkers.db.Db
import vc.fatfukkers.BotTask
import vc.fatfukkers.Task
import vc.fatfukkers.handleTask
import vc.fatfukkers.service.ActivityService
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.WeightService
import java.time.ZoneId
import java.util.Locale

private val logger = LoggerFactory.getLogger("FatCtrlBot")

fun main() {
    System.setProperty("java.awt.headless", "true")

    val token = System.getenv("FATCTRL_BOT_TOKEN")?.trim().orEmpty()
    require(token.isNotBlank()) {
        "Missing FATCTRL_BOT_TOKEN env var. On VPS: export FATCTRL_BOT_TOKEN='123:ABC' or set it in systemd env."
    }

    val tz = System.getenv("FATCTRL_BOT_TZ")?.trim().takeUnless { it.isNullOrBlank() } ?: "Europe/Moscow"
    val zoneId = ZoneId.of(tz)

    val dbPath = System.getenv("DB_PATH")?.trim().takeUnless { it.isNullOrBlank() } ?: "./fatctrlbot.sqlite"
    Db.init(sqlitePath = dbPath)
    ActivityService.ensureSeedData()

    val weightService = WeightService(zoneId)
    val activityService = ActivityService(zoneId)

    val bot = bot {
        this.token = token

        dispatch {
            command("start") {
                val u = UserService.upsertFromUpdate(update)
                logger.info("start with user id: ${u.telegramId}")
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = """
Привет 🐷 ты себя в зеркало видел? Давай подработаем над твоей талией!
Я буду трекать твой вес — раз в день пиши сколько весишь, и вместе будем следить за прогрессом!

<b>📋 Команды:</b>

⚖️ <b>вес XX.X</b> — записать сегодняшний вес (раз в день)
   <i>пример: вес 99.5</i>

🎯 <b>цель XX.X</b> — установить цель по весу
   <i>пример: цель 80</i>

📊 <b>прогресс</b> — посмотреть график и статистику

🏋️ <b>задание</b> — получить физ. задание на сегодня

<b>🏅 Звания (зависят от того сколько ты далёк от цели):</b>

🥇 <b>достигший цели</b> — цель достигнута, поздравляю!
😊 <b>умничка</b> — осталось меньше 2 кг
🙂 <b>худой толстячок</b> — осталось до 6% от веса
😐 <b>толстый толстячок</b> — осталось до 10% от веса
😕 <b>худая жиробасина</b> — осталось до 20% от веса
😬 <b>жирная жиробасина</b> — осталось больше 20% от веса

<i>Установи цель командой «цель XX» — и звание начнёт считаться!</i>
                    """.trimIndent(),
                    parseMode = ParseMode.HTML
                )
            }

            text {
                val rawOriginal = message.text?.trim().orEmpty()
                val lower = rawOriginal.lowercase(Locale("ru", "RU"))

                // Находим все команды в сообщении и их позиции
                data class Hit(val pos: Int, val botTask: BotTask)
                val hits = mutableListOf<Hit>()
                for (bt in BotTask.values()) {
                    val name = bt.taskName
                    var from = 0
                    while (true) {
                        val idx = lower.indexOf(name, from)
                        if (idx == -1) break
                        val wordStart = idx == 0 || lower[idx - 1] == ' '
                        val wordEnd = idx + name.length >= lower.length || lower[idx + name.length] == ' '
                        if (wordStart && wordEnd) hits.add(Hit(idx, bt))
                        from = idx + 1
                    }
                }

                hits.sortBy { it.pos }

                // Каждой команде передаём её сегмент текста (от неё до следующей команды)
                hits.forEachIndexed { i, hit ->
                    val segEnd = if (i + 1 < hits.size) hits[i + 1].pos else rawOriginal.length
                    val segment = rawOriginal.substring(hit.pos, segEnd).trim()
                    val task = when (hit.botTask) {
                        BotTask.WEIGHT   -> Task.Weight
                        BotTask.GOAL     -> Task.Goal
                        BotTask.ACTIVITY -> Task.Activity
                        BotTask.PROGRESS -> Task.Progress
                    }
                    bot.handleTask(
                        task = task,
                        rawText = segment,
                        update = update,
                        message = message,
                        weightService = weightService,
                        activityService = activityService,
                        zoneId = zoneId
                    )
                }
            }
        }
    }

    bot.startPolling()
}