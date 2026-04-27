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

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()
    val routeBounds: StateFlow<LatLngBounds?> = _routeBounds.asStateFlow()
    val allPoints: StateFlow<List<TrackingPoint>> = _allPoints.asStateFlow()

    val visiblePoints: StateFlow<List<TrackingPoint>> =
        combine(_allPoints, _currentIndex) { points, index ->
            if (points.isEmpty()) emptyList()
            else points.subList(0, index + 1)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val progress: StateFlow<Float> =
        combine(_allPoints, _currentIndex) { points, index ->
            if (points.size <= 1) 0f
            else index.toFloat() / (points.size - 1).toFloat()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val elapsedMs: StateFlow<Long> =
        combine(_allPoints, _currentIndex) { points, index ->
            if (points.isEmpty()) 0L
            else points[index].timestampMillis - points[0].timestampMillis
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val totalMs: StateFlow<Long> = _allPoints
        .map { points ->
            if (points.size < 2) 0L
            else points.last().timestampMillis - points.first().timestampMillis
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private var playbackJob: Job? = null

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
        }
        _isPlaying.value = true
        playbackJob = viewModelScope.launch {
            while (_isPlaying.value && _currentIndex.value < _allPoints.value.lastIndex) {
                val pts = _allPoints.value
                val idx = _currentIndex.value
                val gapMs = if (idx < pts.lastIndex) {
                    pts[idx + 1].timestampMillis - pts[idx].timestampMillis
                } else 0L
                val delayMs = (gapMs / _speedMultiplier.value)
                    .toLong()
                    .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
                delay(delayMs)
                // 딜레이 중 사용자가 seekTo로 이동했으면 덮어쓰지 않는다.
                if (_isPlaying.value && _currentIndex.value == idx) {
                    _currentIndex.value = idx + 1
                }
            }
            _isPlaying.value = false
        }
    }

    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
    }

    fun reset() {
        pause()
        _currentIndex.value = 0
    }

    fun seekTo(fraction: Float) {
        val points = _allPoints.value
        if (points.isEmpty()) return
        _currentIndex.value = (fraction * points.lastIndex).toInt().coerceIn(0, points.lastIndex)
    }

    fun cycleSpeed() {
        _speedMultiplier.value = when (_speedMultiplier.value) {
            1f -> 2f
            2f -> 5f
            5f -> 10f
            else -> 1f
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
        private const val MAX_DELAY_MS = 5_000L

        fun factory(sessionId: String) = viewModelFactory {
            initializer {
                val app = checkNotNull(get(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY))
                PlaybackViewModel(app, sessionId)
            }
        }
    }
}
