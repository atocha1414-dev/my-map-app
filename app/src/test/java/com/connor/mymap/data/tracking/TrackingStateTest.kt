package com.connor.mymap.data.tracking

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStateTest {

    @After
    fun tearDown() {
        TrackingState.resetTracking()
        TrackingState.clearTrackPoints()
    }

    @Test
    fun pauseKeepsElapsedTimeAndStopsActiveTimer() {
        TrackingState.startTracking(startedAtMillis = 1_000L)

        TrackingState.pauseTracking(pausedAtMillis = 4_500L)

        assertFalse(TrackingState.isTracking.value)
        assertTrue(TrackingState.isPaused.value)
        assertEquals(3_500L, TrackingState.pausedDurationMillis.value)
        assertEquals(3_500L, TrackingState.elapsedDurationMillis(nowMillis = 9_000L))
    }

    @Test
    fun resumeAddsNewActiveSegmentToPausedDuration() {
        TrackingState.startTracking(startedAtMillis = 1_000L)
        TrackingState.pauseTracking(pausedAtMillis = 4_000L)

        TrackingState.startTracking(startedAtMillis = 10_000L)

        assertEquals(5_500L, TrackingState.elapsedDurationMillis(nowMillis = 12_500L))
    }

    @Test
    fun duplicatePauseDoesNotAccumulateTimeTwice() {
        TrackingState.startTracking(startedAtMillis = 1_000L)

        TrackingState.pauseTracking(pausedAtMillis = 4_000L)
        TrackingState.pauseTracking(pausedAtMillis = 8_000L)

        assertEquals(3_000L, TrackingState.pausedDurationMillis.value)
    }
}
