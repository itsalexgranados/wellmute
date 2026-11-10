package com.wellmute.usage

import com.wellmute.usage.UsageEvent.Type.ACTIVITY_PAUSED
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_RESUMED
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_STOPPED
import com.wellmute.usage.UsageEvent.Type.DEVICE_SHUTDOWN
import com.wellmute.usage.UsageEvent.Type.KEYGUARD_HIDDEN
import com.wellmute.usage.UsageEvent.Type.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageCalculatorTest {

    private val minute = 60_000L
    private fun at(minutes: Int) = minutes * minute

    private fun resumed(minutes: Int, pkg: String, cls: String = "$pkg.Main") =
        UsageEvent(at(minutes), ACTIVITY_RESUMED, pkg, cls)

    private fun paused(minutes: Int, pkg: String, cls: String = "$pkg.Main") =
        UsageEvent(at(minutes), ACTIVITY_PAUSED, pkg, cls)

    private fun UsageSummary.minutesOf(pkg: String) = (apps.find { it.packageName == pkg }?.foregroundMillis ?: 0L) / minute
    private fun UsageSummary.opensOf(pkg: String) = apps.find { it.packageName == pkg }?.opens ?: 0

    @Test
    fun `simple session counts time and one open`() {
        val summary = UsageCalculator.calculate(listOf(resumed(10, "a"), paused(25, "a")), start = 0, end = at(60))
        assertEquals(15, summary.minutesOf("a"))
        assertEquals(1, summary.opensOf("a"))
    }

    @Test
    fun `switching between activities of the same app is one session`() {
        val events = listOf(
            resumed(0, "a", "a.List"),
            paused(5, "a", "a.List"),
            resumed(5, "a", "a.Detail"),
            paused(12, "a", "a.Detail"),
        )
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(12, summary.minutesOf("a"))
        assertEquals(1, summary.opensOf("a"))
    }

    @Test
    fun `leaving an app and coming back later counts two opens`() {
        val events = listOf(resumed(0, "a"), paused(5, "a"), resumed(5, "b"), paused(9, "b"), resumed(9, "a"), paused(12, "a"))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(8, summary.minutesOf("a"))
        assertEquals(2, summary.opensOf("a"))
    }

    @Test
    fun `new activity resumed before the old one pauses does not double count`() {
        val events = listOf(
            resumed(0, "a", "a.List"),
            resumed(5, "a", "a.Detail"),
            paused(5, "a", "a.List"),
            UsageEvent(at(6), ACTIVITY_STOPPED, "a", "a.List"),
            paused(10, "a", "a.Detail"),
        )
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(10, summary.minutesOf("a"))
        assertEquals(1, summary.opensOf("a"))
    }

    @Test
    fun `split screen counts both apps`() {
        val events = listOf(resumed(0, "a"), resumed(0, "b"), paused(20, "a"), paused(30, "b"))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(20, summary.minutesOf("a"))
        assertEquals(30, summary.minutesOf("b"))
    }

    @Test
    fun `screen off ends the session even without a pause event`() {
        val events = listOf(resumed(0, "video"), UsageEvent(at(8), SCREEN_NON_INTERACTIVE))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(8, summary.minutesOf("video"))
    }

    @Test
    fun `shutdown ends every session`() {
        val events = listOf(resumed(0, "a"), resumed(0, "b"), UsageEvent(at(4), DEVICE_SHUTDOWN))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(4, summary.minutesOf("a"))
        assertEquals(4, summary.minutesOf("b"))
    }

    @Test
    fun `session crossing midnight is split between days`() {
        val events = listOf(resumed(50, "a"), paused(70, "a"))
        val yesterday = UsageCalculator.calculate(events, start = 0, end = at(60))
        val today = UsageCalculator.calculate(events, start = at(60), end = at(120))
        assertEquals(10, yesterday.minutesOf("a"))
        assertEquals(10, today.minutesOf("a"))
        // The open happened before midnight, so it belongs to yesterday only.
        assertEquals(1, yesterday.opensOf("a"))
        assertEquals(0, today.opensOf("a"))
    }

    @Test
    fun `app still open counts up to the end of the window`() {
        val summary = UsageCalculator.calculate(listOf(resumed(40, "a")), start = 0, end = at(60))
        assertEquals(20, summary.minutesOf("a"))
    }

    @Test
    fun `pause without a known resume is ignored`() {
        val summary = UsageCalculator.calculate(listOf(paused(5, "a")), start = 0, end = at(60))
        assertEquals(0, summary.minutesOf("a"))
    }

    @Test
    fun `dialog from another app pauses the app underneath`() {
        val events = listOf(
            resumed(0, "contacts"),
            paused(1, "contacts"),
            resumed(1, "permissions", "permissions.Grant"),
            paused(10, "permissions", "permissions.Grant"),
            UsageEvent(at(10), ACTIVITY_STOPPED, "permissions", "permissions.Grant"),
            resumed(10, "contacts"),
            paused(15, "contacts"),
            UsageEvent(at(15), ACTIVITY_STOPPED, "contacts", "contacts.Main"),
        )

        val resumed = UsageCalculator.calculate(events, start = 0, end = at(60), mode = CountingMode.RESUMED)
        assertEquals(6, resumed.minutesOf("contacts"))
        assertEquals(9, resumed.minutesOf("permissions"))

        // Contacts stayed visible behind the dialog, so it counts the whole time and opened once.
        val visible = UsageCalculator.calculate(events, start = 0, end = at(60), mode = CountingMode.VISIBLE)
        assertEquals(15, visible.minutesOf("contacts"))
        assertEquals(1, visible.opensOf("contacts"))
        assertEquals(9, visible.minutesOf("permissions"))
    }

    @Test
    fun `several screens sharing one class name stay one session`() {
        // Seen on a real phone: Settings opens a new SubSettings instance before the old one stops.
        val events = listOf(
            resumed(0, "settings", "settings.Sub"),
            paused(5, "settings", "settings.Sub"),
            resumed(5, "settings", "settings.Sub"),
            UsageEvent(at(6), ACTIVITY_STOPPED, "settings", "settings.Sub"),
            paused(10, "settings", "settings.Sub"),
            UsageEvent(at(10), ACTIVITY_STOPPED, "settings", "settings.Sub"),
        )
        for (mode in CountingMode.entries) {
            val summary = UsageCalculator.calculate(events, start = 0, end = at(60), mode = mode)
            assertEquals("$mode", 10, summary.minutesOf("settings"))
            assertEquals("$mode", 1, summary.opensOf("settings"))
        }
    }

    @Test
    fun `a stop with no pause before it ends the session`() {
        // Seen on a moto g54: Notion went from resumed straight to stopped when going home.
        val events = listOf(
            resumed(0, "notion"),
            resumed(5, "launcher"),
            UsageEvent(at(5), ACTIVITY_STOPPED, "notion", "notion.Main"),
            paused(20, "launcher"),
        )
        for (mode in CountingMode.entries) {
            val summary = UsageCalculator.calculate(events, start = 0, end = at(60), mode = mode)
            assertEquals("$mode", 5, summary.minutesOf("notion"))
            assertEquals("$mode", 1, summary.opensOf("notion"))
        }
    }

    @Test
    fun `an activity stopping while another of the app is resumed keeps the session`() {
        // Seen on a moto g54: VLC's start screen hands over to its main screen.
        val events = listOf(
            resumed(0, "vlc", "vlc.Start"),
            paused(1, "vlc", "vlc.Start"),
            resumed(1, "vlc", "vlc.Main"),
            UsageEvent(at(1), ACTIVITY_STOPPED, "vlc", "vlc.Start"),
            UsageEvent(at(8), ACTIVITY_STOPPED, "vlc", "vlc.Main"),
        )
        for (mode in CountingMode.entries) {
            val summary = UsageCalculator.calculate(events, start = 0, end = at(60), mode = mode)
            assertEquals("$mode", 8, summary.minutesOf("vlc"))
            assertEquals("$mode", 1, summary.opensOf("vlc"))
        }
    }

    @Test
    fun `visible mode ends a session when the app stops`() {
        val events = listOf(resumed(0, "a"), paused(5, "a"), resumed(5, "b"), UsageEvent(at(6), ACTIVITY_STOPPED, "a", "a.Main"))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60), mode = CountingMode.VISIBLE)
        assertEquals(6, summary.minutesOf("a"))
    }

    @Test
    fun `unlocks are counted only inside the window`() {
        val events = listOf(
            UsageEvent(at(5), KEYGUARD_HIDDEN),
            UsageEvent(at(65), KEYGUARD_HIDDEN),
            UsageEvent(at(70), KEYGUARD_HIDDEN),
        )
        assertEquals(2, UsageCalculator.calculate(events, start = at(60), end = at(120)).unlocks)
    }

    @Test
    fun `excluded packages are left out of the list and the total`() {
        val events = listOf(resumed(0, "launcher"), paused(10, "launcher"), resumed(10, "a"), paused(15, "a"))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60), excludedPackages = setOf("launcher"))
        assertEquals(listOf("a"), summary.apps.map { it.packageName })
        assertEquals(5 * minute, summary.totalMillis)
    }

    @Test
    fun `apps are sorted by time`() {
        val events = listOf(resumed(0, "short"), paused(2, "short"), resumed(2, "long"), paused(30, "long"))
        val summary = UsageCalculator.calculate(events, start = 0, end = at(60))
        assertEquals(listOf("long", "short"), summary.apps.map { it.packageName })
    }

    @Test
    fun `sessions are kept, clipped to the window and in order`() {
        val events = listOf(resumed(-5, "a"), paused(10, "a"), resumed(20, "b"), paused(50, "b"), resumed(55, "a"))
        val sessions = UsageCalculator.calculate(events, start = 0, end = at(60)).sessions
        assertEquals(listOf(Session("a", 0, at(10)), Session("b", at(20), at(50)), Session("a", at(55), at(60))), sessions)
    }
}
