package com.connor.mymap.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds

class PlaybackViewModel(
    application: Application,
    val sessionId: String
) : AndroidViewModel(application) {

    private val historyStorage = TrackingHistoryStorage(getApplication())

    val mapFilePath: String? =
        MapFileStorage(getApplication())
            .getMapFile(Constants.Map.DEFAULT_MAP_FILENAME)
            .takeIf { it.exists() }
            ?.absolutePath

    private val _allPoints = MutableStateFlow<List<TrackingPoint>>(emptyList())
    private val _currentIndex = MutableStateFlow(0)
    private val _isPlaying = MutableStateFlow(false)
    private val _speedMultiplier = MutableStateFlow(1f)
    private val _isLoading = MutableStateFlow(true)
    private val _routeBounds = MutableStateFlow<LatLngBounds?>(null)

    // UI 표시 전용: 재생 루프 내부에서 50ms마다 직접 갱신.
    // elapsedMs/progress(이산)와 분리해 점프 없이 부드럽게 올라간다.
    private val _displayElapsedMs = MutableStateFlow(0L)
    private val _displayProgress = MutableStateFlow(0f)

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()
    val routeBounds: StateFlow<LatLngBounds?> = _routeBounds.asStateFlow()
    val allPoints: StateFlow<List<TrackingPoint>> = _allPoints.asStateFlow()
    val displayElapsedMs: StateFlow<Long> = _displayElapsedMs.asStateFlow()
    val displayProgress: StateFlow<Float> = _displayProgress.asStateFlow()

    val startTimestampMs: StateFlow<Long> = _allPoints
        .map { points -> if (points.isEmpty()) 0L else points.first().timestampMillis }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val visiblePoints: StateFlow<List<TrackingPoint>> =
        combine(_allPoints, _currentIndex) { points, index ->
            if (points.isEmpty()) emptyList()
            else points.subList(0, index + 1)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalMs: StateFlow<Long> = _allPoints
        .map { points ->
            if (points.size < 2) 0L
            else points.last().timestampMillis - points.first().timestampMillis
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // 포인트가 2개 이상이어야 재생 가능 (1개면 시작·끝이 같아 재생 의미 없음)
    val canPlay: StateFlow<Boolean> = _allPoints
        .map { it.size >= 2 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var playbackJob: Job? = null
    // 레이스 컨디션 방지: pause 후 즉시 play 시 이전 코루틴이 _isPlaying=false를 덮어쓰지 않도록
    private var playbackGeneration = 0

    init {
        viewModelScope.launch { loadPoints() }
    }

    private suspend fun loadPoints() {
        _isLoading.value = true
        try {
            val points = historyStorage.loadPoints(sessionId, maxPoints = 0)
            _allPoints.value = points
            if (points.isNotEmpty()) {
                _routeBounds.value = computeBounds(points)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load points for $sessionId", e)
        } finally {
            _isLoading.value = false
        }
    }

    fun play() {
        if (_isPlaying.value) return
        val points = _allPoints.value
        if (points.isEmpty()) return
        if (_currentIndex.value >= points.lastIndex) {
            _currentIndex.value = 0
            _displayElapsedMs.value = 0L
            _displayProgress.value = 0f
        }
        _isPlaying.value = true
        val gen = ++playbackGeneration

        playbackJob = viewModelScope.launch {
            while (_isPlaying.value && _currentIndex.value < _allPoints.value.lastIndex) {
                val pts = _allPoints.value
                val idx = _currentIndex.value
                val total = totalMs.value.toFloat()

                val baseElapsed = pts[idx].timestampMillis - pts[0].timestampMillis
                val targetElapsed = pts[idx + 1].timestampMillis - pts[0].timestampMillis
                val gapElapsed = (targetElapsed - baseElapsed).coerceAtLeast(1L)
                val speed = _speedMultiplier.value

                val realDelayMs = (gapElapsed / speed).toLong().coerceAtLeast(MIN_DELAY_MS)

                val alreadyInGap = (_displayElapsedMs.value - baseElapsed).coerceIn(0L, gapElapsed)
                val alreadyRealMs = (alreadyInGap / speed).toLong().coerceIn(0L, realDelayMs)
                val startWall = System.currentTimeMillis() - alreadyRealMs

                while (_isPlaying.value) {
                    val wallDelta = (System.currentTimeMillis() - startWall).coerceAtLeast(0L)
                    if (wallDelta >= realDelayMs) break
                    val fraction = wallDelta.toFloat() / realDelayMs.toFloat()
                    val elapsedMs = baseElapsed + (gapElapsed * fraction).toLong()
                    _displayElapsedMs.value = elapsedMs
                    // 시간 기반 비율: 경과시간 / 전체시간
                    _displayProgress.value = if (total > 0f) elapsedMs.toFloat() / total else 0f
                    delay(DISPLAY_STEP_MS)
                }

                if (_isPlaying.value && _currentIndex.value == idx) {
                    _displayElapsedMs.value = targetElapsed
                    _displayProgress.value = if (total > 0f) targetElapsed.toFloat() / total else 0f
                    _currentIndex.value = idx + 1
                }
            }
            // 이 코루틴이 현재 유효한 재생인 경우에만 상태 변경
            if (gen == playbackGeneration) _isPlaying.value = false
        }
    }

    fun pause() {
        playbackGeneration++
        _isPlaying.value = false
        playbackJob?.cancel()
    }

    fun reset() {
        pause()
        _currentIndex.value = 0
        _displayElapsedMs.value = 0L
        _displayProgress.value = 0f
    }

    fun seekTo(fraction: Float) {
        val points = _allPoints.value
        if (points.isEmpty()) return
        val total = totalMs.value
        if (total <= 0L) {
            _displayProgress.value = fraction
            return
        }
        // 시간 기반 탐색: fraction이 나타내는 목표 시간에 가장 가까운 포인트로 이동
        val targetElapsed = (fraction * total).toLong()
        val t0 = points.first().timestampMillis
        val newIndex = points.indexOfLast { it.timestampMillis - t0 <= targetElapsed }
            .coerceAtLeast(0)
        _currentIndex.value = newIndex
        // 슬라이더·시간 표시는 포인트에 스냅되지 않고 정확한 목표값 유지
        _displayElapsedMs.value = targetElapsed
        _displayProgress.value = fraction
    }

    fun cycleSpeed() {
        _speedMultiplier.value = when (_speedMultiplier.value) {
            1f -> 2f
            2f -> 5f
            5f -> 10f
            else -> 1f
        }

        // 변경 이유: 기존 구현은 속도 변경 시 현재 포인트 구간(realDelay)이 고정되어
        // 다음 포인트로 넘어가야 체감 속도가 바뀌었다.
        // 재생 중에는 코루틴을 현재 지점에서 즉시 재시작해 변경 속도를 바로 반영한다.
        if (_isPlaying.value) {
            playbackGeneration++
            playbackJob?.cancel()
            _isPlaying.value = false
            play()
        }
    }

    private fun computeBounds(points: List<TrackingPoint>): LatLngBounds {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latPad = (maxLat - minLat).coerceAtLeast(0.001) * 0.2
        val lonPad = (maxLon - minLon).coerceAtLeast(0.001) * 0.2
        return LatLngBounds.from(
            maxLat + latPad,
            maxLon + lonPad,
            minLat - latPad,
            minLon - lonPad
        )
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }

    companion object {
        private const val TAG = "PlaybackViewModel"
        private const val MIN_DELAY_MS = 16L
        private const val DISPLAY_STEP_MS = 50L

        fun factory(sessionId: String) = viewModelFactory {
            initializer {
                val app = checkNotNull(get(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY))
                PlaybackViewModel(app, sessionId)
            }
        }
    }
}
