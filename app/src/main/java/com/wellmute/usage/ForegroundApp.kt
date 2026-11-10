package com.wellmute.usage

/**
 * Which app is in front, worked out from Android's usage events: the last app resumed and not
 * paused since. A backup for the accessibility events, which can miss an app coming to the front
 * (Wellmute restarting while it's open, or an app that never opens a new screen).
 */
object ForegroundApp {

    fun from(events: List<UsageEvent>): String? {
        val resumed = mutableMapOf<String, Int>()
        var last: String? = null
        for (event in events.sortedBy { it.timestamp }) {
            when (event.type) {
                UsageEvent.Type.ACTIVITY_RESUMED -> {
                    resumed.merge(event.packageName, 1, Int::plus)
                    last = event.packageName
                }
                UsageEvent.Type.ACTIVITY_PAUSED -> resumed.computeIfPresent(event.packageName) { _, n -> (n - 1).takeIf { it > 0 } }
                // The screen went off: nothing is in front until something resumes again.
                UsageEvent.Type.SCREEN_NON_INTERACTIVE, UsageEvent.Type.DEVICE_SHUTDOWN -> {
                    resumed.clear()
                    last = null
                }
                else -> Unit
            }
        }
        return last?.takeIf { (resumed[it] ?: 0) > 0 }
    }
}
