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
        elapsedSinceStartMillis: Long = Long.MAX_VALUE,
        candidateSpeedMetersPerSecond: Float? = null
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
        val significantMovementDistance = calculateSignificantMovementDistance(previous, candidate)

        // 변경 이유: 사용자가 실제로 멈춰 있어도 GPS 좌표는 정확도 반경 안에서 계속 흔들릴 수 있다.
        // 고정 2m 기준은 이 흔들림을 이동으로 오인하므로, 두 위치의 GPS 정확도에 비례해
        // "의미 있는 이동 거리"를 동적으로 계산하고 그보다 작은 변화는 포인트로 저장하지 않는다.
        if (distanceMeters < significantMovementDistance) {
            return false
        }

        // 변경 이유: FusedLocationProvider가 속도를 제공하고 그 값이 정지에 가까우면
        // 순간적인 좌표 튐을 한 번 더 보수적으로 걸러 정지 중 포인트 생성을 줄인다.
        if (
            candidateSpeedMetersPerSecond != null &&
            candidateSpeedMetersPerSecond < STATIONARY_SPEED_METERS_PER_SECOND &&
            distanceMeters < significantMovementDistance * STATIONARY_DISTANCE_MULTIPLIER
        ) {
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

    private fun calculateSignificantMovementDistance(
        previous: TrackingPoint,
        candidate: TrackingPoint
    ): Float {
        val accuracyBasedDistance =
            maxOf(previous.accuracy, candidate.accuracy) * ACCURACY_SIGNIFICANCE_RATIO
        return accuracyBasedDistance
            .coerceIn(MIN_ACCEPTED_DISTANCE_METERS, MAX_STATIONARY_DRIFT_DISTANCE_METERS)
    }

    private const val WARMUP_DURATION_MILLIS = 15_000L
    private const val WARMUP_MAX_ACCEPTED_ACCURACY_METERS = 25f
    private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
    private const val MIN_ACCEPTED_DISTANCE_METERS = 8f
    private const val ACCURACY_SIGNIFICANCE_RATIO = 0.75f
    private const val MAX_STATIONARY_DRIFT_DISTANCE_METERS = 20f
    private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.7f // about 2.5 km/h
    private const val STATIONARY_DISTANCE_MULTIPLIER = 1.5f
    private const val MAX_ACCEPTED_SPEED_METERS_PER_SECOND = 55.6f // about 200 km/h
}
