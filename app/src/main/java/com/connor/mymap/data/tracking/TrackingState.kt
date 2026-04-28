package com.connor.mymap.data.tracking

import com.connor.mymap.domain.model.TrackingPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱 프로세스 안에서 트래킹 상태를 공유한다.
 * 변경 이유: ForegroundService가 백그라운드에서 받은 위치를 저장하면서
 * 지도 화면이 열려 있을 때는 같은 데이터를 즉시 화면에 그릴 수 있게 한다.
 */
object TrackingState {
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<TrackingPoint>>(emptyList())
    val trackPoints: StateFlow<List<TrackingPoint>> = _trackPoints.asStateFlow()

    private val _trackingStartedAtMillis = MutableStateFlow<Long?>(null)
    val trackingStartedAtMillis: StateFlow<Long?> = _trackingStartedAtMillis.asStateFlow()

    fun setTracking(isTracking: Boolean) {
        _isTracking.value = isTracking
        if (!isTracking) {
            _trackingStartedAtMillis.value = null
        }
    }

    fun startTracking(startedAtMillis: Long = System.currentTimeMillis()) {
        _trackingStartedAtMillis.value = startedAtMillis
        _isTracking.value = true
    }

    fun setTrackPoints(points: List<TrackingPoint>) {
        // 앱 재시작 시 복구: 최근 MAX_LIVE_POINTS개만 인메모리에 유지
        _trackPoints.value = if (points.size <= MAX_LIVE_POINTS) points
                             else points.takeLast(MAX_LIVE_POINTS)
    }

    fun addTrackPoint(point: TrackingPoint) {
        val current = _trackPoints.value
        // 인메모리 포인트는 최대 MAX_LIVE_POINTS개 (지도 렌더링용).
        // 전체 데이터는 디스크(current_track.csv)에 스트리밍 보관 → 저장 시 손실 없음.
        _trackPoints.value = if (current.size < MAX_LIVE_POINTS) {
            current + point
        } else {
            current.drop(1) + point
        }
    }

    fun clearTrackPoints() {
        _trackPoints.value = emptyList()
    }

    const val MAX_LIVE_POINTS = 2_000
}
