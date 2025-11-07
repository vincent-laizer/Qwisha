package com.tanzaniaprogrammers.qwisha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
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

                                processSms(db, sender, body)
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

    private suspend fun processSms(db: AppDatabase, sender: String, body: String) {
        try {
            // Parse compact header format: @i=<MSGID>;c=<CMD>;r=<REFID> content
            if (!body.startsWith("@")) {
                // Regular SMS without overlay header - treat as normal message
                val msgId = generateShortId()
                db.messageDao().insert(Message(msgId, sender, body, false, null, "delivered", System.currentTimeMillis(), hasOverlayHeader = false))
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
                "send", "normal" -> {
                    db.messageDao().insert(Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true))
                }
                "reply" -> {
                    db.messageDao().insert(Message(msgId!!, sender, content, false, refId, "delivered", System.currentTimeMillis(), hasOverlayHeader = true))
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
}

