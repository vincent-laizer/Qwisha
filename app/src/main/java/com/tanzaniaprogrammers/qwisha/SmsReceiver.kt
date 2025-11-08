package com.tanzaniaprogrammers.qwisha

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            try {
                val pdus = bundle?.get("pdus") as? Array<*>
                if (pdus != null) {
                    Log.d("SmsReceiver", "Received ${pdus.size} PDU(s)")

                    // Handle concatenated SMS (multiple parts)
                    val messages = mutableListOf<SmsMessage>()
                    var fullBody = StringBuilder()

                    for (pdu in pdus) {
                        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val format = bundle.getString("format")
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }
                        messages.add(sms)
                        fullBody.append(sms.messageBody ?: "")
                    }

                    // Use the first message for sender (should be same for all parts)
                    val sender = messages.firstOrNull()?.originatingAddress ?: ""
                    val body = fullBody.toString()

                    Log.d("SmsReceiver", "SMS from $sender: body='$body' (${body.length} chars)")

                    // Process SMS in background
                    scope.launch {
                        try {
                            // Use singleton database instance to ensure Flow observers are notified
                            val db = AppDatabase.getDatabase(context.applicationContext)
                            processSms(context, db, sender, body)
                            // Database instance is managed by singleton - no need to close
                            // This ensures all Flow observers (including in MainActivity) get updates
                        } catch (e: Exception) {
                            Log.e("SmsReceiver", "Error processing SMS: ${e.message}", e)
                            e.printStackTrace()
                        }
                    }
                } else {
                    Log.e("SmsReceiver", "No PDUs found in SMS_RECEIVED intent")
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Exception in onReceive: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    private suspend fun processSms(context: Context, db: AppDatabase, sender: String, body: String) {
        try {
            Log.d("SmsReceiver", "Processing SMS from $sender: body length=${body.length}, starts with @=${body.startsWith("@")}")

            // Parse compact header format: @i=<MSGID>;c=<CMD>;r=<REFID> content
            if (!body.startsWith("@")) {
                // Regular SMS without overlay header - treat as normal message
                Log.d("SmsReceiver", "Regular SMS (no overlay header)")
                val msgId = generateShortId()
                val message = Message(msgId, sender, body, false, null, "delivered", System.currentTimeMillis(), hasOverlayHeader = false)
                db.messageDao().insert(message)
                val contactName = getContactName(context, sender)
                showNotification(context, contactName, body, msgId, sender)
                return
            }

            // Find the first space after the header
            val headerEnd = body.indexOf(' ')
            if (headerEnd == -1) {
                // No space found - might be header only (like delete command)
                // Try to parse just the header
                val header = body.substring(1)
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

                if (cmd == "d" && refId != null) {
                    // Delete command without content
                    Log.d("SmsReceiver", "Delete command for refId=$refId")
                    db.messageDao().deleteById(refId!!)
                    return
                }

                Log.e("SmsReceiver", "Invalid header format (no space, not delete): $body")
                return
            }

            val header = body.substring(1, headerEnd)
            val content = body.substring(headerEnd + 1)

            Log.d("SmsReceiver", "Parsing header: $header, content length: ${content.length}")

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

            Log.d("SmsReceiver", "Parsed: msgId=$msgId, cmd=$cmd, refId=$refId")

            if (msgId == null || cmd == null) {
                Log.e("SmsReceiver", "Missing required fields: msgId=$msgId, cmd=$cmd, header='$header', full body='$body'")
                // Fallback: treat as regular message if parsing fails
                val fallbackMsgId = generateShortId()
                val fallbackMessage = Message(fallbackMsgId, sender, body, false, null, "delivered", System.currentTimeMillis(), hasOverlayHeader = false)
                db.messageDao().insert(fallbackMessage)
                val contactName = getContactName(context, sender)
                showNotification(context, contactName, body, fallbackMsgId, sender)
                return
            }

            when (cmd) {
                "s" -> { // send
                    Log.d("SmsReceiver", "Inserting send message: msgId=$msgId")
                    val message = Message(msgId, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    val contactName = getContactName(context, sender)
                    showNotification(context, contactName, content, msgId, sender)
                }
                "r" -> { // reply
                    Log.d("SmsReceiver", "Inserting reply message: msgId=$msgId, refId=$refId")
                    val message = Message(msgId, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    val contactName = getContactName(context, sender)
                    showNotification(context, contactName, content, msgId, sender)
                }
                "e" -> { // edit
                    Log.d("SmsReceiver", "Updating message content: refId=$refId")
                    if (refId != null) {
                        db.messageDao().updateContent(refId, content)
                    } else {
                        Log.e("SmsReceiver", "Edit command missing refId")
                    }
                }
                "d" -> { // delete
                    Log.d("SmsReceiver", "Deleting message: refId=$refId")
                    if (refId != null) {
                        db.messageDao().deleteById(refId)
                    } else {
                        Log.e("SmsReceiver", "Delete command missing refId")
                    }
                }
                // Support old format for backward compatibility
                "send", "normal" -> {
                    Log.d("SmsReceiver", "Inserting send message (old format): msgId=$msgId")
                    val message = Message(msgId, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    val contactName = getContactName(context, sender)
                    showNotification(context, contactName, content, msgId, sender)
                }
                "reply" -> {
                    Log.d("SmsReceiver", "Inserting reply message (old format): msgId=$msgId")
                    val message = Message(msgId, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true)
                    db.messageDao().insert(message)
                    val contactName = getContactName(context, sender)
                    showNotification(context, contactName, content, msgId, sender)
                }
                "edit" -> {
                    Log.d("SmsReceiver", "Updating message content (old format): refId=$refId")
                    if (refId != null) {
                        db.messageDao().updateContent(refId, content)
                    }
                }
                "delete" -> {
                    Log.d("SmsReceiver", "Deleting message (old format): refId=$refId")
                    if (refId != null) {
                        db.messageDao().deleteById(refId)
                    }
                }
                else -> {
                    Log.e("SmsReceiver", "Unknown command: $cmd")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing SMS: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private suspend fun getContactName(context: Context, phoneNumber: String): String = withContext(Dispatchers.IO) {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
            val selectionArgs = arrayOf(phoneNumber)

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        return@withContext name
                    }
                }
            }

            // Try with cleaned number (without formatting)
            val cleanNumber = phoneNumber.replace(Regex("[^+0-9]"), "")
            context.contentResolver.query(uri, projection, selection, arrayOf(cleanNumber), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        return@withContext name
                    }
                }
            }

            // Try matching by number ending (for cases where country code differs)
            val numberSuffix = cleanNumber.takeLast(9) // Last 9 digits
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex) ?: ""
                    val cleanStoredNumber = number.replace(Regex("[^+0-9]"), "")
                    if (cleanStoredNumber.endsWith(numberSuffix) && numberSuffix.length >= 7) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            return@withContext name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error getting contact name: ${e.message}", e)
        }
        phoneNumber // Return phone number if contact not found
    }

    private fun showNotification(context: Context, displayName: String, content: String, msgId: String, phoneNumber: String) {
        try {
            Log.d("SmsReceiver", "Showing notification for displayName=$displayName, phoneNumber=$phoneNumber, msgId=$msgId")

            // Create notification channel for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "sms_channel",
                    "SMS Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for received SMS messages"
                    enableVibration(true)
                    enableLights(true)
                }
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
                Log.d("SmsReceiver", "Notification channel created")
            }

            // Create intent to open the app and navigate to thread (use phone number for navigation)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("openThreadId", phoneNumber)
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    context,
                    phoneNumber.hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                @Suppress("DEPRECATION")
                PendingIntent.getActivity(
                    context,
                    phoneNumber.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            // Create intent for "Mark as Read" action
            val markReadIntent = Intent(context, MarkReadReceiver::class.java).apply {
                action = "MARK_AS_READ"
                putExtra("threadId", phoneNumber)
                putExtra("notificationId", phoneNumber.hashCode())
            }
            val markReadPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(
                    context,
                    phoneNumber.hashCode() + 1000,
                    markReadIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                @Suppress("DEPRECATION")
                PendingIntent.getBroadcast(
                    context,
                    phoneNumber.hashCode() + 1000,
                    markReadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val notification = NotificationCompat.Builder(context, "sms_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(displayName)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "Mark as Read",
                    markReadPendingIntent
                )
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Check if notifications are enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!notificationManager.areNotificationsEnabled()) {
                    Log.e("SmsReceiver", "Notifications are disabled for this app")
                    return
                }
            }

            notificationManager.notify(phoneNumber.hashCode(), notification)
            Log.d("SmsReceiver", "Notification shown successfully")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error showing notification: ${e.message}", e)
            e.printStackTrace()
        }
    }
}

