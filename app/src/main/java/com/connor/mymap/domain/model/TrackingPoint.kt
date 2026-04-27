package com.connor.mymap.domain.model

/**
 * 백그라운드 이동 경로 기록용 위치 점
 */
data class TrackingPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestampMillis: Long,
    // 변경 이유: 일시정지 후 재시작할 때 정지 중 이동한 구간이 직선거리로 합산되지 않도록
    // 같은 segmentIndex 안의 포인트끼리만 경로/거리 계산에 사용한다.
    val segmentIndex: Int = 0
)
