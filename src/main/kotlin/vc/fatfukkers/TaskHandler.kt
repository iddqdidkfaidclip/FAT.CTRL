package vc.fatfukkers

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.types.TelegramBotResult
import vc.fatfukkers.service.ActivityService
import vc.fatfukkers.service.ForecastResult
import vc.fatfukkers.service.ForecastService
import vc.fatfukkers.service.ImageCaptionPhrases
import vc.fatfukkers.service.ImageSearchService
import vc.fatfukkers.service.JokesService
import vc.fatfukkers.service.NewsRegion
import vc.fatfukkers.service.NewsService
import vc.fatfukkers.service.TelegramAnimationSender
import vc.fatfukkers.service.TrainerMessageFormatter
import vc.fatfukkers.service.TrainerMessageRegistry
import vc.fatfukkers.service.TrainerQueue
import vc.fatfukkers.service.ChatParticipantService
import vc.fatfukkers.service.TrainerService
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.WeightChartService
import vc.fatfukkers.service.WeightService
import org.slf4j.LoggerFactory
import retrofit2.Response
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val taskHandlerLogger = LoggerFactory.getLogger("TaskHandler")

/** Заглушка, если DeepSeek недоступен / ключ не задан / ответ пустой. */
private const val TRAINER_UNAVAILABLE_STUB =
    "Прости, котик, я оказалась слишком тупа чтобы разговаривать  \uD83D\uDC94 " +
        "Могу разве что прокомментировать новости или рассказать что там у хохлов"

private val showImageWorker = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "show-image-worker").apply { isDaemon = true }
}

private val showImageQueryPattern = Regex("""^\s*покажи(\s+|$)""", RegexOption.IGNORE_CASE)
internal val newsQueryPattern = Regex(
    """^\s*(расскажи\s+|прокомментируй\s+)?новости(\s+за\s+вчера)?\s*$""",
    RegexOption.IGNORE_CASE,
)
internal val ukraineNewsQueryPattern = Regex(
    """^\s*(расскажи\s+)?что\s+там\s+у\s+хохлов\s*$""",
    RegexOption.IGNORE_CASE,
)
internal val jokeQueryPattern = Regex(
    """^\s*(расскажи\s+)?анекдот(ик)?\s*[!.?]*\s*$""",
    RegexOption.IGNORE_CASE,
)

fun Bot.handleTrainerDelete(message: Message) {
    val chatId = ChatId.fromId(message.chat.id)
    val chatIdLong = message.chat.id
    val reply = message.replyToMessage ?: return
    if (!TrainerMessageRegistry.isTrainerMessage(chatIdLong, reply.messageId)) {
        sendMessage(
            chatId = chatId,
            text = "хотела бы но не могу :(",
            replyParameters = replyTo(message.messageId),
        )
        return
    }

    val deleted = deleteMessage(chatId, reply.messageId).fold(
        ifSuccess = { it },
        ifError = {
            taskHandlerLogger.warn("deleteMessage failed for trainer message {}: {}", reply.messageId, it)
            false
        },
    )
    if (deleted) {
        TrainerMessageRegistry.unregister(chatIdLong, reply.messageId)
        deleteMessage(chatId, message.messageId)
    } else {
        sendMessage(
            chatId = chatId,
            text = "не получилось удалить (может, прошло больше 48 часов?)",
            replyParameters = replyTo(message.messageId),
        )
    }
}

fun Bot.handleTask(
    task: Task,
    rawText: String,
    update: Update,
    message: Message,
    weightService: WeightService,
    activityService: ActivityService,
    zoneId: ZoneId,
    trainerReplyTo: Message? = null,
) {
    val u = UserService.upsertFromUpdate(update)
    val chatId = ChatId.fromId(message.chat.id)
    ChatParticipantService.rememberFromUser(message.chat.id, u)
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

    fun sendProgressWithChart(
        mentionStr: String,
        prefixLine: String? = null,
        forecast: ForecastResult? = null
    ) {
        val progressMsg = weightService.getProgressMessage(u.telegramId)
        val caption = buildString {
            if (prefixLine != null) appendLine(prefixLine).appendLine()
            append(progressMsg)
            if (!forecast?.message.isNullOrEmpty()) {
                appendLine()
                append(forecast!!.message)
            }
        }
        val chartBytes = WeightChartService.buildChartBytes(
            telegramUserId = u.telegramId,
            goal = UserService.getGoalWeight(u.telegramId),
            forecast = forecast
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
            val goalKg = UserService.getGoalWeight(u.telegramId)
            val dataPoints = weightService.getWeightPoints(u.telegramId)
            val forecast = ForecastService.compute(
                dataPoints = dataPoints,
                goal = goalKg?.toDouble(),
                today = LocalDate.now(zoneId)
            )
            sendProgressWithChart(mention, forecast = forecast)
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
        is Task.Trainer -> {
            val query = buildTrainerQuery(rawText, trainerReplyTo, message.quote?.text)
            if (query.isEmpty()) {
                sendTrainerMessage(
                    chatId = chatId,
                    text = "напиши вопрос тренеру, например: «тренер как начать бегать?»",
                    replyToMessageId = message.messageId
                )
                return
            }
            val forgetQuery = query.lowercase(java.util.Locale("ru", "RU"))
            if (forgetQuery == "забудь вообще всё" || forgetQuery == "забудь вообще все") {
                TrainerService.resetAllContext(message.chat.id)
                sendTrainerMessage(
                    chatId = chatId,
                    text = "тренер забыла вообще всё в этом чате — чистый лист",
                    replyToMessageId = message.messageId
                )
                return
            }
            if (forgetQuery == "забудь" || forgetQuery == "забудь всё" || forgetQuery == "забудь все") {
                TrainerService.resetContext(message.chat.id, u.telegramId)
                sendTrainerMessage(
                    chatId = chatId,
                    text = "тренер забыла прошлый разговор — начинаем с чистого листа",
                    replyToMessageId = message.messageId
                )
                return
            }
            if (showImageQueryPattern.containsMatchIn(forgetQuery)) {
                val imageQuery = query.replace(Regex("^\\s*покажи\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (imageQuery.isEmpty()) {
                    sendTrainerMessage(
                        chatId = chatId,
                        text = "что тебе показать? напиши «покажи котика» например",
                        replyToMessageId = message.messageId
                    )
                    return
                }
                val replyToMessageId = message.messageId
                showImageWorker.execute {
                    sendShowImage(chatId, imageQuery, replyToMessageId)
                }
                return
            }
            if (newsQueryPattern.matches(forgetQuery)) {
                val replyToMessageId = message.messageId
                TrainerQueue.submit(u.telegramId) {
                    sendTrainerNews(chatId, replyToMessageId, NewsRegion.RU)
                }
                return
            }
            if (ukraineNewsQueryPattern.matches(forgetQuery)) {
                val replyToMessageId = message.messageId
                TrainerQueue.submit(u.telegramId) {
                    sendTrainerNews(chatId, replyToMessageId, NewsRegion.UA)
                }
                return
            }
            if (jokeQueryPattern.matches(forgetQuery)) {
                sendTrainerMessage(
                    chatId = chatId,
                    text = JokesService.random(),
                    replyToMessageId = message.messageId,
                )
                return
            }
            val replyToMessageId = message.messageId
            val telegramUserId = u.telegramId
            val chatIdLong = message.chat.id
            TrainerQueue.submit(telegramUserId) {
                val answer = askTrainerWithTyping(chatId, telegramUserId, query, chatIdLong)
                val text = answer?.takeIf { it.isNotBlank() } ?: TRAINER_UNAVAILABLE_STUB
                if (!sendTrainerAnswer(chatId, text, replyToMessageId, query)) {
                    sendTrainerMessage(
                        chatId = chatId,
                        text = "не смогла отправить ответ тренера",
                        allowSendingWithoutReply = true,
                    )
                }
            }
        }
    }
}

sealed class Task {
    data object Weight : Task()
    data object Activity : Task()
    data object Progress : Task()
    data object Goal : Task()
    data object Trainer : Task()
}

private fun Bot.sendShowImage(chatId: ChatId, imageQuery: String, replyToMessageId: Long) {
    val chatIdLong = (chatId as? ChatId.Id)?.id ?: return
    val caption = ImageCaptionPhrases.random(imageQuery)
    val gifBytes = searchImageWithUpload(chatId, "gif $imageQuery") {
        ImageSearchService.searchGifBytes(it)
    }
    if (gifBytes != null) {
        val messageId = sendShowAnimation(chatId, gifBytes, replyToMessageId, caption)
        if (messageId != null) {
            TrainerMessageRegistry.register(chatIdLong, messageId)
            return
        }
    }

    val photoBytes = searchImageWithUpload(chatId, imageQuery) {
        ImageSearchService.searchImageBytes(it)
    }
    if (photoBytes == null) {
        sendTrainerMessage(
            chatId = chatId,
            text = "ничего не нашла по запросу «$imageQuery»",
            replyToMessageId = replyToMessageId
        )
        return
    }

    val ext = ImageSearchService.extensionFor(photoBytes)
    val photoResult = sendPhoto(
        chatId = chatId,
        photo = TelegramFile.ByByteArray(photoBytes, "image.$ext"),
        caption = caption,
        replyParameters = replyTo(replyToMessageId),
    )
    if (photoResult.telegramSucceeded("sendPhoto", imageQuery)) {
        photoResult.first?.body()?.result?.messageId?.let { TrainerMessageRegistry.register(chatIdLong, it) }
    } else {
        sendTrainerMessage(
            chatId = chatId,
            text = "не смогла отправить картинку по запросу «$imageQuery»",
            replyToMessageId = replyToMessageId
        )
    }
}

private fun Bot.sendShowAnimation(
    chatId: ChatId,
    gifBytes: ByteArray,
    replyToMessageId: Long,
    caption: String,
): Long? {
    val token = System.getenv("FATCTRL_BOT_TOKEN")?.trim().orEmpty()
    if (token.isBlank()) {
        taskHandlerLogger.warn("sendAnimation skipped: FATCTRL_BOT_TOKEN is missing")
        return null
    }
    val chatIdLong = (chatId as? ChatId.Id)?.id ?: return null
    sendChatAction(chatId, ChatAction.UPLOAD_VIDEO)
    return TelegramAnimationSender.send(token, chatIdLong, gifBytes, replyToMessageId, caption)
}

private fun TelegramBotResult<Message>.telegramMessageSucceeded(action: String, query: String? = null): Boolean =
    fold(
        ifSuccess = { true },
        ifError = { error ->
            taskHandlerLogger.warn(
                "{} failed{}: {}",
                action,
                query?.let { " for «$it»" } ?: "",
                error,
            )
            false
        },
    )

private fun <T> Pair<Response<T?>?, Exception?>.telegramSucceeded(action: String, query: String? = null): Boolean {
    val (response, exception) = this
    if (exception != null) {
        taskHandlerLogger.warn("{} failed{}: {}", action, query?.let { " for «$it»" } ?: "", exception.message)
        return false
    }
    if (response?.isSuccessful == true && response.body() != null) {
        return true
    }
    val errorBody = try {
        response?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    taskHandlerLogger.warn(
        "{} HTTP {}{}: {}",
        action,
        response?.code(),
        query?.let { " for «$it»" } ?: "",
        errorBody ?: "empty response",
    )
    return false
}

private fun Bot.searchImageWithUpload(
    chatId: ChatId,
    imageQuery: String,
    search: (String) -> ByteArray?,
): ByteArray? {
    val stopUpload = AtomicBoolean(false)
    val uploadThread = Thread {
        while (!stopUpload.get()) {
            sendChatAction(chatId, ChatAction.UPLOAD_PHOTO)
            try {
                Thread.sleep(4_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    return try {
        search(imageQuery)
    } catch (e: Exception) {
        taskHandlerLogger.warn("Image search failed for «{}»", imageQuery, e)
        null
    } finally {
        stopUpload.set(true)
        uploadThread.interrupt()
    }
}

internal fun extractTrainerMessageContext(message: Message): String? {
    message.caption?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    message.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (message.photo != null || message.animation != null || message.video != null) {
        return "фото или видео"
    }
    return null
}

internal fun buildTrainerQuery(
    rawText: String,
    replyToTrainerMessage: Message?,
    quotedText: String? = null,
): String {
    val query = rawText
        .replace(Regex("тренер", RegexOption.IGNORE_CASE), "")
        .trim()
        .replace(Regex("^[,.!?:;—-]+\\s*"), "")
        .trim()
    if (replyToTrainerMessage == null) return query
    val context = quotedText?.trim()?.takeIf { it.isNotEmpty() }
        ?: extractTrainerMessageContext(replyToTrainerMessage)
        ?: return query
    return buildString {
        append("Пользователь отвечает на моё сообщение: «")
        append(context)
        append("».\nЕго вопрос: ")
        append(query)
    }
}

private const val TRAINER_TYPING_REFRESH_MS = 4_000L
private const val TELEGRAM_MESSAGE_MAX = 4096

private fun Bot.sendTrainerMessage(
    chatId: ChatId,
    text: String,
    parseMode: ParseMode? = null,
    replyToMessageId: Long? = null,
    allowSendingWithoutReply: Boolean = false,
): Boolean {
    val chatIdLong = (chatId as? ChatId.Id)?.id ?: return false
    val result = sendMessage(
        chatId = chatId,
        text = text,
        parseMode = parseMode,
        replyParameters = replyTo(replyToMessageId, allowSendingWithoutReply),
    )
    val ok = result.telegramMessageSucceeded("sendMessage")
    if (ok) {
        result.fold(
            ifSuccess = { TrainerMessageRegistry.register(chatIdLong, it.messageId) },
            ifError = {},
        )
        ChatParticipantService.rememberTrainerReply(chatIdLong, text)
    }
    return ok
}

private fun Bot.sendTrainerAnswer(
    chatId: ChatId,
    plainText: String,
    replyToMessageId: Long,
    query: String,
    preformattedHtml: Boolean = false,
): Boolean {
    val chatIdLong = (chatId as? ChatId.Id)?.id ?: return false
    val text = plainText.take(TELEGRAM_MESSAGE_MAX).trim()
    if (text.isEmpty()) return false

    val attempts = listOf(
        {
            sendMessage(
                chatId = chatId,
                text = if (preformattedHtml) text else TrainerMessageFormatter.formatForTelegramHtml(text),
                parseMode = ParseMode.HTML,
                replyParameters = replyTo(replyToMessageId, allowWithoutReply = true),
            )
        },
        {
            sendMessage(
                chatId = chatId,
                text = text,
                replyParameters = replyTo(replyToMessageId, allowWithoutReply = true),
            )
        },
        {
            sendMessage(chatId = chatId, text = text)
        },
    )
    for (attempt in attempts) {
        val result = attempt()
        if (result.telegramMessageSucceeded("sendMessage", query)) {
            result.fold(
                ifSuccess = { TrainerMessageRegistry.register(chatIdLong, it.messageId) },
                ifError = {},
            )
            ChatParticipantService.rememberTrainerReply(chatIdLong, text)
            return true
        }
    }
    return false
}

private fun Bot.sendTrainerNews(chatId: ChatId, replyToMessageId: Long, region: NewsRegion) {
    val stopTyping = AtomicBoolean(false)
    val typingThread = Thread(
        {
            while (!stopTyping.get()) {
                sendChatAction(chatId, ChatAction.TYPING)
                if (sleepUntil(stopTyping, TRAINER_TYPING_REFRESH_MS)) break
            }
        },
        "trainer-news-typing",
    ).apply {
        isDaemon = true
        start()
    }

    val text = try {
        val items = NewsService.fetchTopHeadlines(region)
        if (items.isEmpty()) {
            when (region) {
                NewsRegion.RU -> "котик, сейчас не смогла найти свежие новости — попробуй позже 📰"
                NewsRegion.UA -> "котик, сейчас не смогла найти свежие новости у хохлов — попробуй позже 📰"
            }
        } else {
            val comments = NewsService.buildComments(
                items,
                ask = { TrainerService.askOneShot(it) },
                region = region,
            )
            if (comments != null) {
                NewsService.assembleDigest(items, comments, region)
            } else {
                NewsService.formatHeadlinesFallback(items, region)
            }
        }
    } catch (e: Exception) {
        taskHandlerLogger.warn("Trainer news failed region={}", region, e)
        "не смогла собрать новости — попробуй позже 💔"
    }

    stopTyping.set(true)
    typingThread.interrupt()
    typingThread.join()

    val queryLabel = when (region) {
        NewsRegion.RU -> "новости"
        NewsRegion.UA -> "что там у хохлов"
    }
    if (!sendTrainerAnswer(chatId, text, replyToMessageId, queryLabel, preformattedHtml = true)) {
        sendTrainerMessage(
            chatId = chatId,
            text = "не смогла отправить новости",
            allowSendingWithoutReply = true,
        )
    }
}

private fun Bot.askTrainerWithTyping(
    chatId: ChatId,
    telegramUserId: Long,
    query: String,
    chatIdLong: Long,
): String? {
    val stopTyping = AtomicBoolean(false)
    val typingThread = Thread(
        {
            while (!stopTyping.get()) {
                if (stopTyping.get()) break
                sendChatAction(chatId, ChatAction.TYPING)
                if (stopTyping.get()) break
                if (sleepUntil(stopTyping, TRAINER_TYPING_REFRESH_MS)) break
            }
        },
        "trainer-typing",
    ).apply {
        isDaemon = true
        start()
    }

    return try {
        TrainerService.ask(telegramUserId, query, chatId = chatIdLong)
    } catch (_: Exception) {
        null
    } finally {
        stopTyping.set(true)
        typingThread.interrupt()
        typingThread.join()
    }
}

private fun sleepUntil(stop: AtomicBoolean, ms: Long): Boolean {
    val deadline = System.currentTimeMillis() + ms
    while (!stop.get()) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return false
        try {
            Thread.sleep(minOf(remaining, 500))
        } catch (_: InterruptedException) {
            return true
        }
    }
    return true
}

enum class BotTask(val taskName: String) {
    WEIGHT("вес"),
    ACTIVITY("задание"),
    PROGRESS("прогресс"),
    GOAL("цель"),
    TRAINER("тренер")
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
