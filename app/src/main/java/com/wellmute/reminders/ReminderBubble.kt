package com.wellmute.reminders

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wellmute.R

/**
 * A small pill that floats at the top of the screen for a few seconds, drawn by the
 * accessibility service over whatever app is open. Not a notification: nothing in the shade,
 * no sound, untouched by Do Not Disturb. Taps go straight through it to the app underneath.
 */
class ReminderBubble(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var shown: View? = null

    fun show(text: String) {
        hide()
        val view = build(text)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8)
        }
        runCatching { windowManager.addView(view, params) }.onFailure {
            Log.w(TAG, "Could not show the reminder bubble", it)
            return
        }
        shown = view
        view.alpha = 0f
        view.translationY = -dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(FADE_MILLIS).start()
        view.postDelayed({
            view.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(FADE_MILLIS)
                .withEndAction { remove(view) }.start()
        }, SHOW_MILLIS)
    }

    fun hide() {
        shown?.let(::remove)
    }

    private fun remove(view: View) {
        if (shown === view) shown = null
        if (view.isAttachedToWindow) runCatching { windowManager.removeView(view) }
    }

    /**
     * Always dark, tinted with the wallpaper's colours: readable over any app without the glare of
     * a light pill over a dark screen. Same in light and dark theme.
     */
    private fun build(text: String): View {
        val background = service.getColor(BACKGROUND)
        val foreground = service.getColor(TEXT)
        val accent = service.getColor(ICON)

        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            this.background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(background)
            }
            elevation = dp(2).toFloat()
            addView(
                ImageView(service).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    setColorFilter(accent)
                    // The launcher drawing has wide margins; scale it so the ring fills the space.
                    scaleX = 2.2f
                    scaleY = 2.2f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) },
            )
            addView(
                TextView(service).apply {
                    this.text = text
                    setTextColor(foreground)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = service.resources.displayMetrics.widthPixels - dp(96)
                    // Screen readers announce it once, without moving focus.
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                },
            )
        }
    }

    private fun statusBarHeight(): Int =
        windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ReminderBubble"
        private const val SHOW_MILLIS = 4_000L
        private const val FADE_MILLIS = 200L

        // Shared with the preview in RemindersScreen, so it looks the same.
        const val BACKGROUND = android.R.color.system_accent2_800
        const val TEXT = android.R.color.system_accent1_100
        const val ICON = android.R.color.system_accent1_200
    }
}
