package vc.fatfukkers.service

import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object TrainerQueue {
    private val logger = LoggerFactory.getLogger(TrainerQueue::class.java)
    private const val MAX_PENDING_PER_USER = 2

    private val queues = ConcurrentHashMap<Long, ArrayDeque<() -> Unit>>()
    private val draining = ConcurrentHashMap.newKeySet<Long>()

    private val worker = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "trainer-queue").apply { isDaemon = true }
    }

    fun submit(telegramUserId: Long, job: () -> Unit) {
        val queue = queues.computeIfAbsent(telegramUserId) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size >= MAX_PENDING_PER_USER) {
                queue.removeFirst()
                logger.info("Trainer queue dropped oldest pending job user={}", telegramUserId)
            }
            queue.addLast(job)
        }
        scheduleDrain(telegramUserId)
    }

    private fun scheduleDrain(telegramUserId: Long) {
        if (!draining.add(telegramUserId)) return
        worker.execute {
            try {
                drain(telegramUserId)
            } finally {
                draining.remove(telegramUserId)
                val pending = queues[telegramUserId]?.let { q -> synchronized(q) { q.isNotEmpty() } } == true
                if (pending) scheduleDrain(telegramUserId)
            }
        }
    }

    private fun drain(telegramUserId: Long) {
        val queue = queues[telegramUserId] ?: return
        while (true) {
            val job = synchronized(queue) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: break
            try {
                job()
            } catch (e: Exception) {
                logger.warn("Trainer queue job failed user={}", telegramUserId, e)
            }
        }
        synchronized(queue) {
            if (queue.isEmpty()) queues.remove(telegramUserId, queue)
        }
    }
}
