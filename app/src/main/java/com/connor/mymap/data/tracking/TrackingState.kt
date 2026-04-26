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
        _trackPoints.value = points
    }

    fun addTrackPoint(point: TrackingPoint) {
        _trackPoints.value = _trackPoints.value + point
    }

    fun clearTrackPoints() {
        _trackPoints.value = emptyList()
    }
}
