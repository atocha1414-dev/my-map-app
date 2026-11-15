package com.connor.mymap.util

import com.connor.mymap.domain.model.TrackingPoint

/**
 * "종료하고 저장" 시 완료 기록의 시작 시각과 저장할 포인트를 정한다.
 *
 * 변경 이유: 기존에는 시작 시각을 `종료 시각 - 활동 시간`으로 역산했다. 활동 시간은 일시정지 구간을
 * 빼므로 일시정지가 1분(저장 시간 범위 grace)을 넘으면 앞부분 포인트가 범위 밖으로 버려졌다.
 */
object TrackingSaveWindow {

    /**
     * 실제 기록 시작 시각. 활동 시간 역산값과 첫 포인트 시각 중 이른 쪽을 쓴다.
     * 일시정지가 있으면 첫 포인트 시각이 더 이르므로 앞부분 포인트가 보존된다.
     */
    fun recordStartMillis(
        endedAtMillis: Long,
        activeDurationMillis: Long,
        points: List<TrackingPoint>
    ): Long {
        val fromDuration = (endedAtMillis - activeDurationMillis).coerceAtLeast(0L)
        val firstPointAt = points.minOfOrNull { it.timestampMillis } ?: return fromDuration
        return minOf(fromDuration, firstPointAt.coerceIn(0L, endedAtMillis))
    }

    /**
     * 디스크 포인트 뒤에 아직 파일에 쓰이지 않은 최신 인메모리 포인트를 붙인다.
     * 서비스의 포인트 쓰기는 비동기라, 기록 중 바로 저장하면 마지막 포인트가 디스크에 없을 수 있다.
     */
    fun mergePendingLivePoints(
        diskPoints: List<TrackingPoint>,
        livePoints: List<TrackingPoint>
    ): List<TrackingPoint> {
        val lastDiskAt = diskPoints.lastOrNull()?.timestampMillis ?: return livePoints
        val pending = livePoints.filter { it.timestampMillis > lastDiskAt }
        return if (pending.isEmpty()) diskPoints else diskPoints + pending
    }
}
