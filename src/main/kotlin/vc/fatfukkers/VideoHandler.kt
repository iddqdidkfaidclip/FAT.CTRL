package vc.fatfukkers

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.inputmedia.GroupableMedia
import com.github.kotlintelegrambot.entities.inputmedia.InputMediaPhoto
import com.github.kotlintelegrambot.entities.inputmedia.InputMediaVideo
import com.github.kotlintelegrambot.entities.inputmedia.MediaGroup
import org.slf4j.LoggerFactory
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.VideoDownloadService
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("VideoHandler")

private val videoWorker = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "video-worker").apply { isDaemon = true }
}

private val leadingUrlPattern = Regex("""^(https?://\S+)""", RegexOption.IGNORE_CASE)
private const val TELEGRAM_MEDIA_GROUP_MAX = 10

fun detectVideoUrl(text: String): String? {
    val trimmed = text.trim()
    val rawUrl = leadingUrlPattern.find(trimmed)?.groupValues?.get(1) ?: return null
    val url = rawUrl.trimEnd(',', '.', '!', '?', ')', '»', '"', '\'')
    return url.takeIf { isSupportedVideoHost(it) }
}

private fun isSupportedVideoHost(url: String): Boolean {
    val host = try {
        URI(url).host?.lowercase() ?: return false
    } catch (_: Exception) {
        return false
    }
    return host == "youtu.be" ||
        host.endsWith("youtube.com") ||
        host.endsWith("instagram.com") ||
        host == "instagr.am"
}

private fun isInstagramPost(url: String): Boolean =
    try {
        URI(url).path.orEmpty().contains("/p/")
    } catch (_: Exception) {
        url.contains("/p/")
    }

fun Bot.handleVideoDownload(url: String, update: Update, message: Message) {
    UserService.upsertFromUpdate(update)
    val chatId = ChatId.fromId(message.chat.id)
    val replyToMessageId = message.messageId

    videoWorker.execute {
        try {
            when (val outcome = downloadWithUploadAction(chatId, url)) {
                is VideoDownloadService.DownloadOutcome.Ok -> {
                    val result = outcome.result
                    try {
                        val sent = sendDownloadedMedia(
                            chatId = chatId,
                            result = result,
                            replyToMessageId = replyToMessageId,
                        )
                        if (!sent) {
                            sendMessage(
                                chatId = chatId,
                                text = VideoDownloadService.userError("не вышло отправить в Telegram"),
                                replyParameters = replyTo(replyToMessageId),
                            )
                        }
                    } finally {
                        result.items.forEach { VideoDownloadService.deleteQuietly(it.file) }
                    }
                }
                is VideoDownloadService.DownloadOutcome.Err -> {
                    sendMessage(
                        chatId = chatId,
                        text = outcome.message,
                        replyParameters = replyTo(replyToMessageId),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Video download worker failed for {}", url, e)
            sendMessage(
                chatId = chatId,
                text = VideoDownloadService.userError("ошибка при скачивании — ${e.message ?: "неизвестная"}"),
                replyParameters = replyTo(replyToMessageId),
            )
        }
    }
}

private fun Bot.sendDownloadedMedia(
    chatId: ChatId,
    result: VideoDownloadService.DownloadResult,
    replyToMessageId: Long?,
): Boolean {
    val caption = result.title.take(1024)
    val items = result.items
    if (items.isEmpty()) return false

    if (items.size == 1) {
        return sendSingleItem(
            chatId = chatId,
            item = items.single(),
            caption = caption,
            replyToMessageId = replyToMessageId,
        )
    }

    var sentAny = false
    items.chunked(TELEGRAM_MEDIA_GROUP_MAX).forEachIndexed { batchIndex, batch ->
        val batchCaption = if (batchIndex == 0) caption else null
        val media: Array<GroupableMedia> = batch.mapIndexed { index, item ->
            val itemCaption = if (batchIndex == 0 && index == 0) batchCaption else null
            val telegramFile = TelegramFile.ByFile(item.file.toFile())
            when (item.mediaKind) {
                VideoDownloadService.MediaKind.PHOTO -> InputMediaPhoto(
                    media = telegramFile,
                    caption = itemCaption,
                )
                VideoDownloadService.MediaKind.VIDEO -> InputMediaVideo(
                    media = telegramFile,
                    caption = itemCaption,
                    width = item.width,
                    height = item.height,
                    duration = item.durationSec,
                    supportsStreaming = true,
                )
                VideoDownloadService.MediaKind.AUDIO ->
                    throw IllegalStateException("audio not supported in media group")
            }
        }.toTypedArray()

        val groupResult = sendMediaGroup(
            chatId = chatId,
            mediaGroup = MediaGroup.from(*media),
            replyParameters = replyTo(if (batchIndex == 0) replyToMessageId else null),
        )

        if (groupResult.isSuccess) {
            sentAny = true
            return@forEachIndexed
        }

        groupResult.onError { error ->
            logger.warn("sendMediaGroup failed ({} items): {}", batch.size, error)
        }

        batch.forEachIndexed { index, item ->
            val itemCaption = if (batchIndex == 0 && index == 0) batchCaption else null
            if (sendSingleItem(
                    chatId = chatId,
                    item = item,
                    caption = itemCaption,
                    replyToMessageId = if (batchIndex == 0 && index == 0) replyToMessageId else null,
                )
            ) {
                sentAny = true
            }
        }
    }
    return sentAny
}

private fun Bot.sendSingleItem(
    chatId: ChatId,
    item: VideoDownloadService.MediaItem,
    caption: String?,
    replyToMessageId: Long?,
): Boolean {
    val file = item.file.toFile()
    val (_, error) = when (item.mediaKind) {
        VideoDownloadService.MediaKind.AUDIO -> sendAudio(
            chatId = chatId,
            audio = TelegramFile.ByFile(file),
            title = caption,
            replyParameters = replyTo(replyToMessageId),
        )
        VideoDownloadService.MediaKind.PHOTO -> sendPhoto(
            chatId = chatId,
            photo = TelegramFile.ByFile(file),
            caption = caption,
            replyParameters = replyTo(replyToMessageId),
        )
        VideoDownloadService.MediaKind.VIDEO -> sendVideo(
            chatId = chatId,
            video = TelegramFile.ByFile(file),
            duration = item.durationSec,
            width = item.width,
            height = item.height,
            caption = caption,
            replyParameters = replyTo(replyToMessageId),
        )
    }
    if (error != null) {
        logger.warn("Failed to send {}: {}", item.mediaKind, error.message)
        return false
    }
    return true
}

private fun Bot.downloadWithUploadAction(
    chatId: ChatId,
    url: String,
): VideoDownloadService.DownloadOutcome {
    val uploadPhotos = isInstagramPost(url)
    val stopUpload = AtomicBoolean(false)
    val uploadThread = Thread {
        while (!stopUpload.get()) {
            sendChatAction(
                chatId,
                if (uploadPhotos) ChatAction.UPLOAD_PHOTO else ChatAction.UPLOAD_VIDEO,
            )
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
        VideoDownloadService.download(url)
    } finally {
        stopUpload.set(true)
        uploadThread.interrupt()
    }
}
