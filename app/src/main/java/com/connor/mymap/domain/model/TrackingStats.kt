package com.connor.mymap.domain.model

/**
 * 이동 경로 기록 요약 정보
 */
data class TrackingStats(
    val distanceMeters: Float = 0f,
    val durationMillis: Long = 0L,
    val averageSpeedMetersPerSecond: Float = 0f,
    val latestAccuracyMeters: Float? = null,
    val pointCount: Int = 0
)
