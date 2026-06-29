package vc.fatfukkers.service

import java.util.concurrent.ConcurrentHashMap

object TrainerMessageRegistry {
    private val byChat = ConcurrentHashMap<Long, MutableSet<Long>>()

    fun register(chatId: Long, messageId: Long) {
        byChat.compute(chatId) { _, ids ->
            (ids ?: ConcurrentHashMap.newKeySet()).also { it.add(messageId) }
        }
    }

    fun isTrainerMessage(chatId: Long, messageId: Long): Boolean =
        byChat[chatId]?.contains(messageId) == true

    fun unregister(chatId: Long, messageId: Long) {
        byChat[chatId]?.remove(messageId)
    }
}
