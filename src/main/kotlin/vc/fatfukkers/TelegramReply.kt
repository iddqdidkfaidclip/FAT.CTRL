package vc.fatfukkers

import com.github.kotlintelegrambot.entities.ReplyParameters

internal fun replyTo(messageId: Long?, allowWithoutReply: Boolean = false): ReplyParameters? =
    messageId?.let {
        ReplyParameters(
            messageId = it,
            allowSendingWithoutReply = if (allowWithoutReply) true else null,
        )
    }
