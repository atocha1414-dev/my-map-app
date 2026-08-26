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

    // 홈 시트와 ForegroundService 알림이 같은 일시정지/누적 시간을 사용한다.
    // ViewModel에만 값을 두면 알림의 중지 버튼이 ViewModel을 거치지 않아 두 시간이 달라진다.
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pausedDurationMillis = MutableStateFlow(0L)
    val pausedDurationMillis: StateFlow<Long> = _pausedDurationMillis.asStateFlow()

    fun setTracking(isTracking: Boolean) {
        _isTracking.value = isTracking
        if (!isTracking) {
            _trackingStartedAtMillis.value = null
        }
    }

    fun startTracking(startedAtMillis: Long = System.currentTimeMillis()) {
        _trackingStartedAtMillis.value = startedAtMillis
        _isPaused.value = false
        _isTracking.value = true
    }

    fun pauseTracking(pausedAtMillis: Long = System.currentTimeMillis()) {
        if (_isTracking.value) {
            val startedAtMillis = _trackingStartedAtMillis.value
            if (startedAtMillis != null) {
                _pausedDurationMillis.value +=
                    (pausedAtMillis - startedAtMillis).coerceAtLeast(0L)
            }
        }
        _isTracking.value = false
        _trackingStartedAtMillis.value = null
        _isPaused.value = true
    }

    fun elapsedDurationMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val activeDurationMillis = if (_isTracking.value) {
            _trackingStartedAtMillis.value
                ?.let { startedAt -> (nowMillis - startedAt).coerceAtLeast(0L) }
                ?: 0L
        } else {
            0L
        }
        return _pausedDurationMillis.value + activeDurationMillis
    }

    fun resetTracking() {
        _isTracking.value = false
        _trackingStartedAtMillis.value = null
        _isPaused.value = false
        _pausedDurationMillis.value = 0L
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
