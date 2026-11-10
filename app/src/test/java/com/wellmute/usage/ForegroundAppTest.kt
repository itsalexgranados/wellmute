package com.wellmute.usage

import com.wellmute.usage.UsageEvent.Type.ACTIVITY_PAUSED
import com.wellmute.usage.UsageEvent.Type.ACTIVITY_RESUMED
import com.wellmute.usage.UsageEvent.Type.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppTest {

    private fun event(at: Long, type: UsageEvent.Type, pkg: String = "") = UsageEvent(at, type, pkg)

    @Test
    fun `the last app resumed and not paused is in front`() {
        val events = listOf(
            event(1, ACTIVITY_RESUMED, "launcher"),
            event(2, ACTIVITY_PAUSED, "launcher"),
            event(3, ACTIVITY_RESUMED, "linkedin"),
        )
        assertEquals("linkedin", ForegroundApp.from(events))
    }

    @Test
    fun `moving between screens of one app keeps it in front`() {
        val events = listOf(
            event(1, ACTIVITY_RESUMED, "linkedin"),
            event(2, ACTIVITY_PAUSED, "linkedin"),
            event(2, ACTIVITY_RESUMED, "linkedin"),
        )
        assertEquals("linkedin", ForegroundApp.from(events))
    }

    @Test
    fun `nothing is in front after the screen goes off or the app is paused`() {
        assertNull(ForegroundApp.from(listOf(event(1, ACTIVITY_RESUMED, "a"), event(2, SCREEN_NON_INTERACTIVE))))
        assertNull(ForegroundApp.from(listOf(event(1, ACTIVITY_RESUMED, "a"), event(2, ACTIVITY_PAUSED, "a"))))
    }
}
