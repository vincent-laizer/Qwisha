package com.tanzaniaprogrammers.qwisha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MarkReadReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "MARK_AS_READ") {
            val threadId = intent.getStringExtra("threadId") ?: return
            val notificationId = intent.getIntExtra("notificationId", 0)

            Log.d("MarkReadReceiver", "Marking thread as read: $threadId")

            scope.launch {
                try {
                    // Use singleton database instance to ensure Flow observers are notified
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.messageDao().markThreadAsRead(threadId)

                    // Cancel the notification
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(notificationId)

                    Log.d("MarkReadReceiver", "Thread marked as read and notification cancelled")
                } catch (e: Exception) {
                    Log.e("MarkReadReceiver", "Error marking thread as read: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        }
    }
}

