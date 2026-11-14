package com.wellmute.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.data.Period
import com.wellmute.data.PeriodType
import com.wellmute.data.percentChange
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * At the start of each week (the phone's first day of the week, at noon), sums up the week before:
 * screen time, unlocks and the most used app. Then plans the next one.
 */
class WeeklySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as WellmuteApp).container
        try {
            if (container.notifications.prefs.value.weeklySummary) {
                container.history.syncRecentDays()
                val lastWeek = Period.containing(PeriodType.WEEK, LocalDate.now().minusWeeks(1), firstDayOfWeek())
                val summary = container.history.observePeriod(lastWeek).first()
                if (summary.totalMillis > 0) {
                    val res = applicationContext.resources
                    val title = res.getString(R.string.notif_weekly_title, duration(summary.totalMillis))
                    val parts = buildList {
                        add(res.getString(R.string.notif_weekly_average, duration(summary.averageMillis)))
                        add(res.getQuantityString(R.plurals.notif_weekly_unlocks, summary.unlocks, summary.unlocks))
                        summary.apps.firstOrNull { it.foregroundMillis > 0 }?.let { top ->
                            add(res.getString(R.string.notif_weekly_top_app, container.appInfo.labelFor(top.packageName), duration(top.foregroundMillis)))
                        }
                        summary.previousAverageMillis?.let { percentChange(it, summary.averageMillis) }?.let { change ->
                            add(
                                when {
                                    change < 0 -> res.getString(R.string.notif_weekly_less, abs(change))
                                    change > 0 -> res.getString(R.string.notif_weekly_more, change)
                                    else -> res.getString(R.string.notif_weekly_same)
                                },
                            )
                        }
                    }
                    container.notifications.postWeeklySummary(title, parts.joinToString(", "))
                }
            }
        } finally {
            // A test run (Settings → Developer) leaves the planned summary alone.
            if (!inputData.getBoolean(ONE_OFF, false)) schedule(applicationContext, replace = true)
        }
        return Result.success()
    }

    /** "1 h 10 min", like the rest of the app (the shared formatter needs a screen). */
    private fun duration(millis: Long): String {
        val res = applicationContext.resources
        val minutes = millis / 60_000
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> res.getString(R.string.duration_minutes, rest)
            rest == 0L -> res.getString(R.string.duration_hours, hours)
            else -> res.getString(R.string.duration_hours_minutes, hours, rest)
        }
    }

    companion object {
        private const val WORK_NAME = "weekly_summary_noon"

        private const val ONE_OFF = "one_off"

        /** Sends a summary of last week now, for testing; doesn't change when the next one comes. */
        fun sendNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<WeeklySummaryWorker>().setInputData(workDataOf(ONE_OFF to true)).build(),
            )
        }
        private val SEND_AT: LocalTime = LocalTime.of(12, 0)

        private fun firstDayOfWeek() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

        /** Plans the next summary for the coming first day of the week at 12:00; kept if one is already planned. */
        fun schedule(context: Context, replace: Boolean = false) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(firstDayOfWeek())).atTime(SEND_AT)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            val request = OneTimeWorkRequestBuilder<WeeklySummaryWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager
                // From the worker itself, queue behind it: replacing would cancel the run in progress.
                .enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
