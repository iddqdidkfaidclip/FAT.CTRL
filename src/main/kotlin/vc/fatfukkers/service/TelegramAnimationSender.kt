package vc.fatfukkers.service

import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

object TelegramAnimationSender {
    private val logger = LoggerFactory.getLogger(TelegramAnimationSender::class.java)
    private val gifMediaType = MediaType.parse("image/gif")!!
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val messageIdPattern = Regex(""""message_id"\s*:\s*(\d+)""")

    fun send(
        token: String,
        chatId: Long,
        gifBytes: ByteArray,
        replyToMessageId: Long?,
        caption: String,
    ): Long? {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "animation",
                "animation.gif",
                RequestBody.create(gifMediaType, gifBytes),
            )
        if (replyToMessageId != null) {
            bodyBuilder.addFormDataPart("reply_to_message_id", replyToMessageId.toString())
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendAnimation")
            .post(bodyBuilder.build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body()?.string().orEmpty()
                if (!response.isSuccessful) {
                    logger.warn("sendAnimation HTTP {}: {}", response.code(), body)
                    null
                } else {
                    messageIdPattern.find(body)?.groupValues?.get(1)?.toLongOrNull()
                }
            }
        } catch (e: Exception) {
            logger.warn("sendAnimation failed", e)
            null
        }
    }
}
