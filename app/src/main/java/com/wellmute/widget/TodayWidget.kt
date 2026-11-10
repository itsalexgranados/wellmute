package com.wellmute.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wellmute.MainActivity
import com.wellmute.R
import com.wellmute.WellmuteApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Home screen widget: today's screen time and the three most used apps. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val container = (context.applicationContext as WellmuteApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                update(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val APP_ROWS = listOf(R.id.widget_app1, R.id.widget_app2, R.id.widget_app3)

        /** Redraws every placed widget with the latest saved usage; cheap when there are none. */
        suspend fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TodayWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return

            val container = (context.applicationContext as WellmuteApp).container
            val today = container.history.observeDay(LocalDate.now()).first()
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widget_total, formatMinutes(context, today.totalMillis))
            val top = today.apps.filter { it.foregroundMillis >= 60_000 }.take(APP_ROWS.size)
            APP_ROWS.forEachIndexed { index, row ->
                val app = top.getOrNull(index)
                views.setTextViewText(row, app?.let { "${container.appInfo.labelFor(it.packageName)} · ${formatMinutes(context, it.foregroundMillis)}" } ?: "")
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(component, views)
        }

        /** Same wording as the app: "2 h 5 min", "2 h", "12 min". */
        private fun formatMinutes(context: Context, millis: Long): String {
            val totalMinutes = millis / 60_000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes == 0L -> context.getString(R.string.duration_hours, hours)
                hours > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
                else -> context.getString(R.string.duration_minutes, minutes)
            }
        }
    }
}
