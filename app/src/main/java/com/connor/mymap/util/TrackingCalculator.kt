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

        // FusedLocationProvider가 정지에 가까운 속도를 보고한 상태에서 큰 좌표 변화가 생기면
        // 실제 이동이 아니라 GPS 점프로 본다. 장시간 정지 시 오래된 앵커와 비교하면
        // 평균 속도가 낮아져 수 km 점프도 과속 필터를 통과할 수 있으므로 별도로 차단한다.
        if (
            candidateSpeedMetersPerSecond != null &&
            candidateSpeedMetersPerSecond < STATIONARY_SPEED_METERS_PER_SECOND &&
            distanceMeters >= MAX_STATIONARY_FIX_JUMP_METERS
        ) {
            return false
        }

        return isPlausibleConsecutiveFix(
            previous = previous,
            candidate = candidate,
            candidateSpeedMetersPerSecond = candidateSpeedMetersPerSecond
        )
    }

    /**
     * 연속으로 관측된 두 GPS 픽스 사이의 이동이 물리적으로 가능한지 확인한다.
     * 채택된 앵커가 아니라 최근 원시 픽스 기준으로 호출해야 장시간 정지 후 점프를 잡을 수 있다.
     */
    fun isPlausibleConsecutiveFix(
        previous: TrackingPoint,
        candidate: TrackingPoint,
        candidateSpeedMetersPerSecond: Float? = null
    ): Boolean {
        val elapsedSeconds = (candidate.timestampMillis - previous.timestampMillis) / 1_000f
        val distanceMeters = previous.distanceTo(candidate)
        return isPlausibleFixMetrics(
            distanceMeters = distanceMeters,
            elapsedSeconds = elapsedSeconds,
            candidateSpeedMetersPerSecond = candidateSpeedMetersPerSecond
        )
    }

    internal fun isPlausibleFixMetrics(
        distanceMeters: Float,
        elapsedSeconds: Float,
        candidateSpeedMetersPerSecond: Float? = null
    ): Boolean {
        if (elapsedSeconds <= 0f) return false
        if (
            candidateSpeedMetersPerSecond != null &&
            candidateSpeedMetersPerSecond < STATIONARY_SPEED_METERS_PER_SECOND &&
            distanceMeters >= MAX_STATIONARY_FIX_JUMP_METERS
        ) {
            return false
        }

        val impliedSpeedMetersPerSecond = distanceMeters / elapsedSeconds
        return impliedSpeedMetersPerSecond <= MAX_ACCEPTED_SPEED_METERS_PER_SECOND
    }

    fun isEffectivelyStationary(candidateSpeedMetersPerSecond: Float?): Boolean =
        candidateSpeedMetersPerSecond != null &&
            candidateSpeedMetersPerSecond < EFFECTIVE_STATIONARY_SPEED_METERS_PER_SECOND

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

    /** 두 위치의 정확도에 비례한 '의미 있는 이동 거리'(앵커 반경). 앵커 필터에서 재사용. */
    fun significantMovementDistance(previous: TrackingPoint, candidate: TrackingPoint): Float =
        calculateSignificantMovementDistance(previous, candidate)

    /** 두 점 사이의 평면 거리(m). */
    fun distance(a: TrackingPoint, b: TrackingPoint): Float = a.distanceTo(b)

    private const val WARMUP_DURATION_MILLIS = 15_000L
    private const val WARMUP_MAX_ACCEPTED_ACCURACY_METERS = 25f
    // 변경 이유: 40~50m 오차의 좌표가 경로를 도로 밖으로 크게 튀게 만들 수 있어
    // 일반 추적 구간에서도 정확도 반경이 30m 이하인 위치만 저장한다.
    private const val MAX_ACCEPTED_ACCURACY_METERS = 30f
    private const val MIN_ACCEPTED_DISTANCE_METERS = 8f
    private const val ACCURACY_SIGNIFICANCE_RATIO = 0.75f
    private const val MAX_STATIONARY_DRIFT_DISTANCE_METERS = 20f
    private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.7f // about 2.5 km/h
    private const val EFFECTIVE_STATIONARY_SPEED_METERS_PER_SECOND = 0.3f // about 1.1 km/h
    private const val MAX_STATIONARY_FIX_JUMP_METERS = 50f
    private const val MAX_ACCEPTED_SPEED_METERS_PER_SECOND = 55.6f // about 200 km/h
}
