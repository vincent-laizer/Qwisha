package com.tanzaniaprogrammers.qwisha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class MessagePart(
    val messageId: String,
    val partNumber: Int,
    val totalParts: Int,
    val content: String,
    val timestamp: Long
)

/**
 * Manages reconstruction of multi-part messages
 */
object MultiPartMessageManager {
    private const val TAG = "MultiPartMessageManager"

    // Store pending message parts: messageId -> list of parts
    private val pendingParts = mutableMapOf<String, MutableList<MessagePart>>()

    // Store complete messages waiting to be processed: messageId -> full content
    private val completedMessages = mutableMapOf<String, String>()

    /**
     * Add a message part and check if all parts are received
     * Returns the complete message content if all parts are received, null otherwise
     */
    suspend fun addPart(
        messageId: String,
        partNumber: Int,
        totalParts: Int,
        content: String
    ): String? = withContext(Dispatchers.IO) {
        synchronized(pendingParts) {
            val parts = pendingParts.getOrPut(messageId) { mutableListOf() }

            // Check if this part already exists
            if (parts.any { it.partNumber == partNumber }) {
                Log.d(TAG, "Part $partNumber of message $messageId already exists, skipping")
                return@withContext null
            }

            // Add the part
            parts.add(MessagePart(messageId, partNumber, totalParts, content, System.currentTimeMillis()))
            Log.d(TAG, "Added part $partNumber/$totalParts for message $messageId. Total parts: ${parts.size}")

            // Check if we have all parts
            if (parts.size == totalParts) {
                // Sort by part number
                val sortedParts = parts.sortedBy { it.partNumber }

                // Reconstruct the complete message
                val completeContent = sortedParts.joinToString("") { it.content }

                // Remove from pending
                pendingParts.remove(messageId)

                // Store in completed messages
                completedMessages[messageId] = completeContent

                Log.d(TAG, "All parts received for message $messageId. Complete content length: ${completeContent.length}")
                return@withContext completeContent
            } else {
                Log.d(TAG, "Waiting for more parts for message $messageId. Have ${parts.size}/$totalParts")
                return@withContext null
            }
        }
    }

    /**
     * Get completed message content
     */
    fun getCompletedMessage(messageId: String): String? {
        synchronized(completedMessages) {
            return completedMessages.remove(messageId)
        }
    }

    /**
     * Check if we have a completed message
     */
    fun hasCompletedMessage(messageId: String): Boolean {
        synchronized(completedMessages) {
            return completedMessages.containsKey(messageId)
        }
    }

    /**
     * Clean up old pending parts (older than 5 minutes)
     */
    fun cleanupOldParts() {
        synchronized(pendingParts) {
            val now = System.currentTimeMillis()
            val fiveMinutesAgo = now - (5 * 60 * 1000)

            val toRemove = mutableListOf<String>()
            pendingParts.forEach { (messageId, parts) ->
                val oldestPart = parts.minByOrNull { it.timestamp }
                if (oldestPart != null && oldestPart.timestamp < fiveMinutesAgo) {
                    Log.d(TAG, "Cleaning up old parts for message $messageId")
                    toRemove.add(messageId)
                }
            }

            toRemove.forEach { pendingParts.remove(it) }
        }

        synchronized(completedMessages) {
            val now = System.currentTimeMillis()
            val oneHourAgo = now - (60 * 60 * 1000)
            // Completed messages are cleaned up when retrieved, but we can also clean old ones
            // For now, we'll keep them for 1 hour
        }
    }

    /**
     * Clear all pending parts (for testing or reset)
     */
    fun clearAll() {
        synchronized(pendingParts) {
            pendingParts.clear()
        }
        synchronized(completedMessages) {
            completedMessages.clear()
        }
    }
}

