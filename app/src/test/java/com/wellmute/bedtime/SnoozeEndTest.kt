package com.wellmute.bedtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SnoozeEndTest {

    private val now = Instant.parse("2026-11-25T22:00:00Z")
    private val minute = 60_000L

    @Test
    fun `comes back 30 minutes later inside the scheduled hours`() {
        val end = snoozeEnd(now, windowEnd = Instant.parse("2026-11-26T07:00:00Z"))
        assertEquals(BedtimeSnooze(now.toEpochMilli() + 30 * minute, resumes = true), end)
    }

    @Test
    fun `turned on by hand outside the scheduled hours, it still comes back 30 minutes later`() {
        assertEquals(BedtimeSnooze(now.toEpochMilli() + 30 * minute, resumes = true), snoozeEnd(now, windowEnd = null))
    }

    @Test
    fun `never runs past the end of the night, and then stays off`() {
        val windowEnd = now.plusSeconds(10 * 60)
        assertEquals(BedtimeSnooze(windowEnd.toEpochMilli(), resumes = false), snoozeEnd(now, windowEnd))
        // Exactly 30 minutes left: Bedtime would only come back as its hours end, so it stays off.
        val exactly = now.plusSeconds(30 * 60)
        assertEquals(BedtimeSnooze(exactly.toEpochMilli(), resumes = false), snoozeEnd(now, exactly))
    }
}
