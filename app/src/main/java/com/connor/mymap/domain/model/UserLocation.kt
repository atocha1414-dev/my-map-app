package com.connor.mymap.domain.model

/**
 * 사용자 위치 정보
 */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f // 정확도 (미터)
)