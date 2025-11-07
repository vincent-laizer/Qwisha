// app/src/main/java/com/tanzaniaprogrammers/qwisha/SmsReceiver.kt
package com.tanzaniaprogrammers.qwisha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            try {
                val pdus = bundle?.get("pdus") as Array<*>
                for (pdu in pdus) {
                    val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = sms.originatingAddress
                    val body = sms.messageBody
                    Log.d("SmsReceiver", "SMS from $sender: $body")
                    // TODO: pass SMS to your app database / overlay logic
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Exception in onReceive", e)
            }
        }
    }
}
