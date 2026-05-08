package com.connor.mymap.util

import android.location.Location
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.TrackingStats

object TrackingCalculator {

    fun calculateStats(points: List<TrackingPoint>): TrackingStats {
        if (points.isEmpty()) return TrackingStats()

        val distanceMeters = points
            .zipWithNext()
            .filter { (from, to) -> from.segmentIndex == to.segmentIndex }
            .sumOf { (from, to) -> from.distanceTo(to).toDouble() }
            .toFloat()

        val durationMillis = (points.last().timestampMillis - points.first().timestampMillis)
            .coerceAtLeast(0L)

        val averageSpeed = if (durationMillis > 0L) {
            distanceMeters / (durationMillis / 1_000f)
        } else {
            0f
        }

        return TrackingStats(
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            averageSpeedMetersPerSecond = averageSpeed,
            latestAccuracyMeters = points.last().accuracy,
            pointCount = points.size
        )
    }

    fun shouldAcceptPoint(
        previous: TrackingPoint?,
        candidate: TrackingPoint,
        elapsedSinceStartMillis: Long = Long.MAX_VALUE
    ): Boolean {
        val isWarmup = elapsedSinceStartMillis in 0 until WARMUP_DURATION_MILLIS
        val accuracyThreshold = if (isWarmup) {
            WARMUP_MAX_ACCEPTED_ACCURACY_METERS
        } else {
            MAX_ACCEPTED_ACCURACY_METERS
        }

        if (candidate.accuracy > accuracyThreshold) {
            return false
        }

        if (previous == null) return true

        val elapsedSeconds = (candidate.timestampMillis - previous.timestampMillis) / 1_000f
        if (elapsedSeconds <= 0f) return false

        val distanceMeters = previous.distanceTo(candidate)

        // 변경 이유: 실내/도심에서 GPS가 몇 미터씩 흔들리면 실제 이동 없이도 거리가 누적된다.
        // 아주 작은 이동은 노이즈로 보고 버려 거리 계산 신뢰도를 우선한다.
        if (distanceMeters < MIN_ACCEPTED_DISTANCE_METERS) {
            return false
        }

        val speedMetersPerSecond = distanceMeters / elapsedSeconds
        return speedMetersPerSecond <= MAX_ACCEPTED_SPEED_METERS_PER_SECOND
    }

    fun TrackingPoint.distanceTo(other: TrackingPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            latitude,
            longitude,
            other.latitude,
            other.longitude,
            results
        )
        return results[0]
    }

    private const val WARMUP_DURATION_MILLIS = 15_000L
    private const val WARMUP_MAX_ACCEPTED_ACCURACY_METERS = 25f
    private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
    private const val MIN_ACCEPTED_DISTANCE_METERS = 2f
    private const val MAX_ACCEPTED_SPEED_METERS_PER_SECOND = 55.6f // about 200 km/h
}
