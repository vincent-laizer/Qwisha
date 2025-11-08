package com.tanzaniaprogrammers.qwisha

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tanzaniaprogrammers.qwisha.ui.theme.SmsOverlayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private var smsSentReceiver: BroadcastReceiver? = null
    private var smsDeliveredReceiver: BroadcastReceiver? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(applicationContext)

        // Create notification channel
        createNotificationChannel()

        // Request permissions
        val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            // handle result if needed
        }
        permissionsLauncher.launch(arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        // Register SMS delivery receivers
        smsSentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msgId = intent?.getStringExtra("msgId") ?: return
                val resultCode = resultCode
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val status = when (resultCode) {
                            RESULT_OK -> "sent"
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "failed"
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "failed"
                            SmsManager.RESULT_ERROR_NULL_PDU -> "failed"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "failed"
                            else -> "pending"
                        }
                        db.messageDao().updateStatus(msgId, status)
                    }
                }
            }
        }

        smsDeliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msgId = intent?.getStringExtra("msgId") ?: return
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.messageDao().updateStatus(msgId, "delivered")
                    }
                }
            }
        }

        // Use RECEIVER_NOT_EXPORTED for app-local broadcasts (Android 13+)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }

        registerReceiver(smsSentReceiver, IntentFilter("SMS_SENT"), flag)
        registerReceiver(smsDeliveredReceiver, IntentFilter("SMS_DELIVERED"), flag)

        // Note: SMS reception is handled by SmsReceiver declared in AndroidManifest.xml
        // This ensures SMS are received even when the app is closed

        setContent {
            SmsOverlayTheme {
                val navController = rememberNavController()
                val openThreadId = intent.getStringExtra("openThreadId")

                // Navigate to thread if opened from notification
                LaunchedEffect(openThreadId) {
                    if (openThreadId != null) {
                        navController.navigate("chat/$openThreadId")
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "threads") {
                        composable("threads") { ConversationsScreen(navController, db) }
                        composable("chat/{threadId}") { backStackEntry ->
                            val threadId = backStackEntry.arguments?.getString("threadId") ?: ""
                            ThreadScreen(threadId, db) { navController.popBackStack() }
                        }
                        composable("settings") { SettingsScreen(navController) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsSentReceiver?.let { unregisterReceiver(it) }
        smsDeliveredReceiver?.let { unregisterReceiver(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sms_channel",
                "SMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for received SMS messages"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
