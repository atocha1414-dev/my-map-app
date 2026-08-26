package com.connor.mymap.util

import com.connor.mymap.domain.model.TrackingPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingCalculatorTest {

    @Test
    fun normalTrackingAcceptsAccuracyAtThirtyMeters() {
        assertTrue(
            TrackingCalculator.shouldAcceptPoint(
                previous = null,
                candidate = point(accuracy = 30f),
                elapsedSinceStartMillis = 20_000L
            )
        )
    }

    @Test
    fun normalTrackingRejectsAccuracyOverThirtyMeters() {
        assertFalse(
            TrackingCalculator.shouldAcceptPoint(
                previous = null,
                candidate = point(accuracy = 30.1f),
                elapsedSinceStartMillis = 20_000L
            )
        )
    }

    @Test
    fun consecutiveFixRejectsMultiKilometerJump() {
        assertFalse(
            TrackingCalculator.isPlausibleFixMetrics(
                distanceMeters = 6_820f,
                elapsedSeconds = 3f,
                candidateSpeedMetersPerSecond = 0f
            )
        )
    }

    @Test
    fun nearZeroReportedSpeedIsStationary() {
        assertTrue(TrackingCalculator.isEffectivelyStationary(0.1f))
        assertFalse(TrackingCalculator.isEffectivelyStationary(0.5f))
        assertFalse(TrackingCalculator.isEffectivelyStationary(null))
    }

    private fun point(accuracy: Float) = TrackingPoint(
        latitude = 37.5665,
        longitude = 126.9780,
        accuracy = accuracy,
        timestampMillis = 20_000L
    )
}
