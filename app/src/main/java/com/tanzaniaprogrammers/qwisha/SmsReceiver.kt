package com.tanzaniaprogrammers.qwisha

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.*

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            try {
                val pdus = bundle?.get("pdus") as? Array<*>
                if (pdus != null) {
                    for (pdu in pdus) {
                        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val format = bundle.getString("format")
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }
                        val sender = sms.originatingAddress ?: ""
                        val body = sms.messageBody ?: ""

                        Log.d("SmsReceiver", "SMS from $sender: $body")

                        // Process SMS in background
                        scope.launch {
                            try {
                                val db = Room.databaseBuilder(
                                    context.applicationContext,
                                    AppDatabase::class.java,
                                    "sms_overlay_db"
                                )
                                    .addMigrations(AppDatabase.MIGRATION_1_2)
                                    .build()

                                processSms(context, db, sender, body)
                                db.close()
                            } catch (e: Exception) {
                                Log.e("SmsReceiver", "Error processing SMS", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Exception in onReceive", e)
            }
        }
    }

    private suspend fun processSms(context: Context, db: AppDatabase, sender: String, body: String) {
        try {
            // Parse compact header format: @i=<MSGID>;c=<CMD>;r=<REFID> content
            if (!body.startsWith("@")) {
                // Regular SMS without overlay header - treat as normal message
                val msgId = generateShortId()
                val message = Message(msgId, sender, body, false, null, "delivered", System.currentTimeMillis(), hasOverlayHeader = false)
                db.messageDao().insert(message)
                showNotification(context, sender, body, msgId)
                return
            }

            val headerEnd = body.indexOf(' ')
            if (headerEnd == -1) return

            val header = body.substring(1, headerEnd)
            val content = body.substring(headerEnd + 1)

            var msgId: String? = null
            var cmd: String? = null
            var refId: String? = null

            header.split(";").forEach { part ->
                val keyValue = part.split("=", limit = 2)
                if (keyValue.size == 2) {
                    when (keyValue[0]) {
                        "i" -> msgId = keyValue[1]
                        "c" -> cmd = keyValue[1]
                        "r" -> refId = if (keyValue[1] == "null" || keyValue[1].isEmpty()) null else keyValue[1]
                    }
                }
            }

            if (msgId == null || cmd == null) return

            when (cmd) {
                "s" -> { // send
                    val message = Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    showNotification(context, sender, content, msgId!!)
                }
                "r" -> { // reply
                    val message = Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    showNotification(context, sender, content, msgId!!)
                }
                "e" -> { // edit
                    if (refId != null) {
                        db.messageDao().updateContent(refId!!, content)
                    }
                }
                "d" -> { // delete
                    if (refId != null) {
                        db.messageDao().deleteById(refId!!)
                    }
                }
                // Support old format for backward compatibility
                "send", "normal" -> {
                    val message = Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    showNotification(context, sender, content, msgId!!)
                }
                "reply" -> {
                    val message = Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    showNotification(context, sender, content, msgId!!)
                }
                "edit" -> {
                    if (refId != null) {
                        db.messageDao().updateContent(refId!!, content)
                    }
                }
                "delete" -> {
                    if (refId != null) {
                        db.messageDao().deleteById(refId!!)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing SMS", e)
        }
    }

    private fun showNotification(context: Context, sender: String, content: String, msgId: String) {
        try {
            // Create notification channel for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "sms_channel",
                    "SMS Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for received SMS messages"
                }
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            // Create intent to open the app and navigate to thread
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("openThreadId", sender)
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    context,
                    msgId.hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                @Suppress("DEPRECATION")
                PendingIntent.getActivity(
                    context,
                    msgId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val notification = NotificationCompat.Builder(context, "sms_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(sender)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(msgId.hashCode(), notification)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error showing notification", e)
        }
    }
}

