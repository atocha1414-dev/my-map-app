package com.connor.mymap.ui.footprints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "나의 발자취" — 저장된 모든 이동 세션의 경로를 한 지도 위에 겹쳐 보여주기 위한 상태.
 *
 * 메모리 보호:
 *  - 세션당 [PER_SESSION_POINTS] 개로 다운샘플(loadPoints가 처리)
 *  - 전체 [MAX_TOTAL_POINTS] 개를 넘으면 더 이상 경로를 적재하지 않는다(거리 합계는 계속 누적).
 */
class FootprintsViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStorage = TrackingHistoryStorage(application)

    /** 오프라인 MBTiles 경로. 없으면 null(지도 미준비). */
    val mapFilePath: String? =
        MapFileStorage(application)
            .getMapFile(Constants.Map.DEFAULT_MAP_FILENAME)
            .takeIf { it.exists() }
            ?.absolutePath

    data class UiState(
        val isLoading: Boolean = true,
        /** 세션별 경로(점 목록). 라인을 세션 경계 너머로 잇지 않기 위해 분리해서 보관. */
        val routes: List<List<TrackingPoint>> = emptyList(),
        val sessionCount: Int = 0,
        val totalDistanceMeters: Float = 0f,
        val totalPoints: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val sessions = historyStorage.list()
                val routes = ArrayList<List<TrackingPoint>>(sessions.size)
                var totalDistance = 0f
                var totalPoints = 0
                for (s in sessions) {
                    totalDistance += s.distanceMeters
                    if (totalPoints >= MAX_TOTAL_POINTS) continue
                    val pts = historyStorage.loadPoints(s.id, PER_SESSION_POINTS)
                    if (pts.size >= 2) {
                        routes.add(pts)
                        totalPoints += pts.size
                    }
                }
                _uiState.value = UiState(
                    isLoading = false,
                    routes = routes,
                    sessionCount = sessions.size,
                    totalDistanceMeters = totalDistance,
                    totalPoints = totalPoints,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load footprints", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    companion object {
        private const val TAG = "FootprintsViewModel"
        private const val PER_SESSION_POINTS = 150
        private const val MAX_TOTAL_POINTS = 20_000
    }
}
