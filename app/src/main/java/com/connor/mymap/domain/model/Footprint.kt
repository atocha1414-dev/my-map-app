package com.connor.mymap.domain.model

/**
 * 사용자가 선택한 여러 이동 기록(TrackingSession)을 하나로 묶은 "발자취".
 * 실제 경로 데이터는 들고 있지 않고, 참조하는 세션 id 목록만 보관한다.
 */
data class Footprint(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val sessionIds: List<String>,
)
