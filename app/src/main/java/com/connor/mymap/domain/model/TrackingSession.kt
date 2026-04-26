package com.connor.mymap.domain.model

/**
 * 사용자가 한 번의 기록을 마치고 저장한 이동 세션.
 * id는 저장 시점의 millis를 그대로 쓴다(파일명과 1:1 매칭).
 */
data class TrackingSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val distanceMeters: Float,
    val durationMillis: Long,
    val pointCount: Int
)
