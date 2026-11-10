package com.wellmute.focus

import com.wellmute.usage.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusDayStatsTest {

    private val minute = 60_000L
    private fun m(n: Int) = n * minute

    @Test
    fun `no Focus that day means no stats`() {
        assertNull(FocusDayStats.of(listOf(Session("a", 0, m(10))), emptyList()))
    }

    @Test
    fun `apps used during Focus, clipped to it, with opens and phone-free time`() {
        // Focus from minute 60 to 120.
        val focus = listOf(m(60) until m(120))
        val sessions = listOf(
            Session("a", m(50), m(70)), // started before Focus: 10 min during it, not an open
            Session("b", m(80), m(85)), // an open
            Session("b", m(100), m(110)), // another open
        )
        val stats = FocusDayStats.of(sessions, focus)!!
        assertEquals(m(60), stats.focusMillis)
        assertEquals(listOf(FocusAppUse("b", m(15), 2), FocusAppUse("a", m(10), 0)), stats.apps)
        // Free: 70–80, 85–100, 110–120.
        assertEquals(m(35), stats.idleMillis)
        assertEquals(m(15), stats.longestIdleMillis)
    }

    @Test
    fun `moving between screens of one app is a single open`() {
        val focus = listOf(0L until m(60))
        val sessions = listOf(Session("a", m(10), m(20)), Session("a", m(20) + 1_000, m(30)))
        assertEquals(1, FocusDayStats.of(sessions, focus)!!.apps.single().opens)
    }
}
