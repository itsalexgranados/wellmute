package com.wellmute

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.wellmute.data.HistorySyncWorker
import com.wellmute.notifications.WeeklySummaryWorker
import kotlinx.coroutines.launch

class WellmuteApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        HistorySyncWorker.schedule(this)
        WeeklySummaryWorker.schedule(this)
        listenForCharging()
    }

    /**
     * Bedtime's "only while charging" needs to know when the charger is plugged in or out. Android
     * only tells running apps, so listen for as long as the process lives (the notification
     * listener and accessibility service usually keep it running).
     */
    private fun listenForCharging() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.appScope.launch { container.bedtime.onChargingChanged() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
