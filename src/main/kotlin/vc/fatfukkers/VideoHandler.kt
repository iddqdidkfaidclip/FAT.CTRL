package vc.fatfukkers

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import vc.fatfukkers.service.UserService
import vc.fatfukkers.service.VideoDownloadService
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val videoWorker = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "video-worker").apply { isDaemon = true }
}

private val leadingUrlPattern = Regex("""^(https?://\S+)""", RegexOption.IGNORE_CASE)

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

fun Bot.handleVideoDownload(url: String, update: Update, message: Message) {
    UserService.upsertFromUpdate(update)
    val chatId = ChatId.fromId(message.chat.id)
    val replyToMessageId = message.messageId

    videoWorker.execute {
        when (val outcome = downloadWithUploadAction(chatId, url)) {
            is VideoDownloadService.DownloadOutcome.Ok -> {
                val result = outcome.result
                try {
                    val file = result.file.toFile()
                    val caption = result.title.take(1024)
                    if (result.audioOnly) {
                        sendAudio(
                            chatId = chatId,
                            audio = TelegramFile.ByFile(file),
                            title = caption,
                            replyToMessageId = replyToMessageId,
                        )
                    } else {
                        sendVideo(
                            chatId = chatId,
                            video = TelegramFile.ByFile(file),
                            caption = caption,
                            replyToMessageId = replyToMessageId,
                        )
                    }
                } finally {
                    VideoDownloadService.deleteQuietly(result.file)
                }
            }
            is VideoDownloadService.DownloadOutcome.Err -> {
                sendMessage(
                    chatId = chatId,
                    text = outcome.message,
                    replyToMessageId = replyToMessageId,
                )
            }
        }
    }
}

private fun Bot.downloadWithUploadAction(
    chatId: ChatId,
    url: String,
): VideoDownloadService.DownloadOutcome {
    val stopUpload = AtomicBoolean(false)
    val uploadThread = Thread {
        while (!stopUpload.get()) {
            sendChatAction(chatId, ChatAction.UPLOAD_VIDEO)
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
