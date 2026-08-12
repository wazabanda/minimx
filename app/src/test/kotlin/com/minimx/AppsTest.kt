package com.minimx

import org.junit.Assert.assertEquals
import org.junit.Test

// ponytail: one test over the only branching pure logic in the app. The rest is a UI
// shell over platform APIs — verified on device, not here.
class AppsTest {

    @Test
    fun budgetCountsDown() {
        assertEquals(30, minutesLeft(30, 0))
        assertEquals(30, minutesLeft(30, 59_999))          // partial minute is not spent yet
        assertEquals(29, minutesLeft(30, 60_000))
        assertEquals(1, minutesLeft(30, 29 * 60_000L))
    }

    @Test
    fun mascotDecaysWithScreenTime() {
        assertEquals(0, mascotStage(0))
        assertEquals(0, mascotStage(59))
        assertEquals(1, mascotStage(60))      // each boundary belongs to the worse stage
        assertEquals(2, mascotStage(120))
        assertEquals(3, mascotStage(180))
        assertEquals(4, mascotStage(240))
        assertEquals(5, mascotStage(300))
        assertEquals(5, mascotStage(10_000))  // no frame past the last one
    }

    @Test
    fun countdownRoundsUp() {
        // A session started this instant must read as its full length, not one second short.
        assertEquals("25:00", formatCountdown(25 * 60_000L))
        assertEquals("25:00", formatCountdown(25 * 60_000L - 1))   // still the same second
        assertEquals("24:59", formatCountdown(25 * 60_000L - 1000)) // one whole second gone
        assertEquals("0:01", formatCountdown(1))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("1:00:00", formatCountdown(60 * 60_000L))
    }

    @Test
    fun budgetFloorsAtZero() {
        assertEquals(0, minutesLeft(30, 30 * 60_000L))
        assertEquals(0, minutesLeft(30, 90 * 60_000L))     // way over, never negative
        assertEquals(0, minutesLeft(0, 0))
    }
}
