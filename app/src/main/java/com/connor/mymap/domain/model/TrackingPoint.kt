package com.connor.mymap.domain.model

/**
 * 백그라운드 이동 경로 기록용 위치 점
 */
data class TrackingPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestampMillis: Long
)
