package com.wellmute.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wellmute.WellmuteApp
import com.wellmute.widget.TodayWidget
import java.util.concurrent.TimeUnit

/** Saves recent usage into our history every few hours, before Android discards the events. */
class HistorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        (applicationContext as WellmuteApp).container.history.syncRecentDays()
        TodayWidget.update(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "history_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HistorySyncWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
