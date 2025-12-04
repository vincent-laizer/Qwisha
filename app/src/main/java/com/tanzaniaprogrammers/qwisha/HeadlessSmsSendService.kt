package com.tanzaniaprogrammers.qwisha

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
This service is used when a user tries to send an SMS without opening the app's UI.
The most common scenario is declining an incoming phone call with a quick response
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}