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
    fun budgetFloorsAtZero() {
        assertEquals(0, minutesLeft(30, 30 * 60_000L))
        assertEquals(0, minutesLeft(30, 90 * 60_000L))     // way over, never negative
        assertEquals(0, minutesLeft(0, 0))
    }
}
