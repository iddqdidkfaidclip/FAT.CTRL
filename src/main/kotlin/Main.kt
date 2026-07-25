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
import vc.fatfukkers.detectVideoUrl
import vc.fatfukkers.handleTask
import vc.fatfukkers.handleTrainerDelete
import vc.fatfukkers.handleVideoDownload
import vc.fatfukkers.jokeQueryPattern
import vc.fatfukkers.newsQueryPattern
import vc.fatfukkers.ukraineNewsQueryPattern
import vc.fatfukkers.service.ActivityService
import vc.fatfukkers.service.ChatParticipantService
import vc.fatfukkers.service.TrainerMessageRegistry
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.VideoDownloadService
import vc.fatfukkers.service.WeightService
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneId
import java.util.Locale

private val logger = LoggerFactory.getLogger("FatCtrlBot")

private fun isCommandBoundary(c: Char): Boolean =
    c.isWhitespace() || c in ",.!?:;—-»«"

private val trainerShowImagePattern = Regex("""^\s*покажи(\s+|$)""", RegexOption.IGNORE_CASE)
private val deleteTrainerPattern = Regex("""^\s*удоли\s*[!.?]*\s*$""", RegexOption.IGNORE_CASE)

private fun commandsHelpText(): String = """
<b>📋 Команды:</b>

⚖️ <b>вес XX.X</b> — записать сегодняшний вес (раз в день)
   <i>пример: вес 99.5</i>

🎯 <b>цель XX.X</b> — установить цель по весу
   <i>пример: цель 80</i>

📊 <b>прогресс</b> — посмотреть график и статистику

🏋️ <b>задание</b> — получить физ. задание на сегодня

🏋️‍♂️ <b>тренер …</b> — спросить ИИ-тренера
   <i>пример: тренер как начать бегать?</i>
   <i>картинка: покажи ... / тренер покажи ...</i>
   <i>забыть диалог: тренер забудь / тренер забудь всё</i>
   <i>новости: новости / расскажи новости / тренер новости — топ-10 актуальных заголовков (Россия)</i>
   <i>хохлы: что там у хохлов / тренер что там у хохлов — топ-10 новостей Украины</i>
   <i>анекдот: расскажи анекдот / тренер расскажи анекдот</i>
   <i>ответь реплаем на сообщение тренера — сработает так же, с учётом контекста</i>

📥 <b>ссылка YouTube / Instagram</b> — скачать видео или фото
   <i>просто отправь ссылку первой в сообщении</i>
   <i>пример: https://youtube.com/watch?v=...</i>

<b>🏅 Звания (зависят от того сколько ты далёк от цели):</b>

🥇 <b>достигший цели</b> — цель достигнута, поздравляю!
😊 <b>умничка</b> — осталось меньше 2 кг
🙂 <b>худой толстячок</b> — осталось до 6% от веса
😐 <b>толстый толстячок</b> — осталось до 10% от веса
😕 <b>худая жиробасина</b> — осталось до 20% от веса
😬 <b>жирная жиробасина</b> — осталось больше 20% от веса

<i>Установи цель командой «цель XX» — и звание начнёт считаться!</i>

<i>версия ${BotVersion.VERSION}</i>
""".trimIndent()

private fun initTrainerLogPath() {
    val path = System.getenv("TRAINER_LOG_PATH")?.trim().takeUnless { it.isNullOrBlank() }
        ?: "./logs/trainer.log"
    System.setProperty("TRAINER_LOG_PATH", path)
    Paths.get(path).parent?.let { Files.createDirectories(it) }
}

fun main() {
    System.setProperty("java.awt.headless", "true")
    initTrainerLogPath()
    VideoDownloadService.onStartup()

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
                val firstToken = message.text?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                if (!firstToken.equals("/start@FAT_CTRL_BOT", ignoreCase = true)) return@command
                val u = UserService.upsertFromUpdate(update)
                ChatParticipantService.rememberFromUser(message.chat.id, u, message.text)
                logger.info("start with user id: ${u.telegramId}")
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = """
Привет! Я твоя тренер 🩷 Обращайся ко мне по любому вопросу!
Буду следить за твоим весом — раз в день пиши сколько весишь, и вместе будем кайфовать от прогресса!

${commandsHelpText()}
                    """.trimIndent(),
                    parseMode = ParseMode.HTML
                )
            }

            command("help") {
                val u = UserService.upsertFromUpdate(update)
                ChatParticipantService.rememberFromUser(message.chat.id, u, message.text)
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = commandsHelpText(),
                    parseMode = ParseMode.HTML
                )
            }

            text {
                val u = UserService.upsertFromUpdate(update)
                ChatParticipantService.rememberFromUser(message.chat.id, u, message.text)

                val rawOriginal = message.text?.trim().orEmpty()
                val lower = rawOriginal.lowercase(Locale("ru", "RU"))

                detectVideoUrl(rawOriginal)?.let { videoUrl ->
                    bot.handleVideoDownload(
                        url = videoUrl,
                        update = update,
                        message = message,
                    )
                    return@text
                }

                if (deleteTrainerPattern.matches(rawOriginal)) {
                    UserService.upsertFromUpdate(update)
                    bot.handleTrainerDelete(message)
                    return@text
                }

                message.replyToMessage?.let { reply ->
                    if (TrainerMessageRegistry.isTrainerMessage(message.chat.id, reply.messageId)) {
                        bot.handleTask(
                            task = Task.Trainer,
                            rawText = rawOriginal,
                            update = update,
                            message = message,
                            weightService = weightService,
                            activityService = activityService,
                            zoneId = zoneId,
                            trainerReplyTo = reply,
                        )
                        return@text
                    }
                }

                if (trainerShowImagePattern.containsMatchIn(rawOriginal)) {
                    bot.handleTask(
                        task = Task.Trainer,
                        rawText = rawOriginal,
                        update = update,
                        message = message,
                        weightService = weightService,
                        activityService = activityService,
                        zoneId = zoneId,
                    )
                    return@text
                }
                if (newsQueryPattern.matches(rawOriginal)) {
                    bot.handleTask(
                        task = Task.Trainer,
                        rawText = rawOriginal,
                        update = update,
                        message = message,
                        weightService = weightService,
                        activityService = activityService,
                        zoneId = zoneId,
                    )
                    return@text
                }
                if (ukraineNewsQueryPattern.matches(rawOriginal)) {
                    bot.handleTask(
                        task = Task.Trainer,
                        rawText = rawOriginal,
                        update = update,
                        message = message,
                        weightService = weightService,
                        activityService = activityService,
                        zoneId = zoneId,
                    )
                    return@text
                }
                if (jokeQueryPattern.matches(rawOriginal)) {
                    bot.handleTask(
                        task = Task.Trainer,
                        rawText = rawOriginal,
                        update = update,
                        message = message,
                        weightService = weightService,
                        activityService = activityService,
                        zoneId = zoneId,
                    )
                    return@text
                }

                // Находим все команды в сообщении и их позиции
                data class Hit(val pos: Int, val botTask: BotTask)
                val hits = mutableListOf<Hit>()
                for (bt in BotTask.values()) {
                    val name = bt.taskName
                    var from = 0
                    while (true) {
                        val idx = lower.indexOf(name, from)
                        if (idx == -1) break
                        val wordStart = idx == 0 || isCommandBoundary(lower[idx - 1])
                        val wordEnd = idx + name.length >= lower.length || isCommandBoundary(lower[idx + name.length])
                        if (wordStart && wordEnd) hits.add(Hit(idx, bt))
                        from = idx + 1
                    }
                }

                hits.sortBy { it.pos }

                // Сообщение обрабатывается только если оно начинается с команды
                // и между командами нет посторонних слов (только пробелы/переносы и одно значение)
                if (hits.isEmpty()) return@text
                if (hits[0].pos != 0) return@text

                val singleValueGap = Regex("""^\s*\S*\s*$""")
                fun gapMatches(botTask: BotTask, gap: String): Boolean = when (botTask) {
                    BotTask.TRAINER -> gap.trim().isNotEmpty()
                    else -> singleValueGap.matches(gap)
                }
                val allGapsClean = hits.mapIndexed { i, hit ->
                    val gapStart = hit.pos + hit.botTask.taskName.length
                    val gapEnd = if (i + 1 < hits.size) hits[i + 1].pos else rawOriginal.length
                    gapMatches(hit.botTask, rawOriginal.substring(gapStart, gapEnd))
                }.all { it }
                if (!allGapsClean) return@text

                // Каждой команде передаём её сегмент текста (от неё до следующей команды)
                hits.forEachIndexed { i, hit ->
                    val segEnd = if (i + 1 < hits.size) hits[i + 1].pos else rawOriginal.length
                    val segment = rawOriginal.substring(hit.pos, segEnd).trim()
                    val task = when (hit.botTask) {
                        BotTask.WEIGHT   -> Task.Weight
                        BotTask.GOAL     -> Task.Goal
                        BotTask.ACTIVITY -> Task.Activity
                        BotTask.PROGRESS -> Task.Progress
                        BotTask.TRAINER  -> Task.Trainer
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

    logger.info("FATCTRLBOT v{} starting", BotVersion.VERSION)
    bot.setMyDescription(description = BotVersion.description()).fold(
        ifSuccess = { logger.info("Bot description updated to v{}", BotVersion.VERSION) },
        ifError = { logger.warn("Failed to set bot description: {}", it) },
    )
    bot.setMyShortDescription(shortDescription = BotVersion.shortDescription()).fold(
        ifSuccess = { logger.info("Bot short description updated to v{}", BotVersion.VERSION) },
        ifError = { logger.warn("Failed to set bot short description: {}", it) },
    )
    bot.startPolling()
}