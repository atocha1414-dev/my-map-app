package com.connor.mymap.ui.footprints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.FootprintStorage
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
 * "발자취" — 사용자가 선택한 기록들을 묶은 발자취의 목록과,
 * 하나를 열었을 때 그 발자취에 포함된 경로들을 한 지도에 겹쳐 보기 위한 상태.
 */
class FootprintsViewModel(application: Application) : AndroidViewModel(application) {

    private val footprintStorage = FootprintStorage(application)
    private val historyStorage = TrackingHistoryStorage(application)

    /** 오프라인 MBTiles 경로. 없으면 null(지도 미준비). */
    val mapFilePath: String? =
        MapFileStorage(application)
            .getMapFile(Constants.Map.DEFAULT_MAP_FILENAME)
            .takeIf { it.exists() }
            ?.absolutePath

    data class FootprintListItem(
        val id: String,
        val name: String,
        val createdAtMillis: Long,
        val sessionCount: Int,
        val totalDistanceMeters: Float,
    )

    data class ListState(
        val isLoading: Boolean = true,
        val items: List<FootprintListItem> = emptyList(),
    )

    data class DetailState(
        val isLoading: Boolean = false,
        val name: String = "",
        val routes: List<List<TrackingPoint>> = emptyList(),
        val sessionCount: Int = 0,
        val totalDistanceMeters: Float = 0f,
        val totalPoints: Int = 0,
    )

    private val _listState = MutableStateFlow(ListState())
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _detailState = MutableStateFlow(DetailState())
    val detailState: StateFlow<DetailState> = _detailState.asStateFlow()

    private var listJob: Job? = null
    private var detailJob: Job? = null

    fun refreshList() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _listState.value = _listState.value.copy(isLoading = true)
            try {
                val footprints = footprintStorage.list()
                val distanceById = historyStorage.list().associate { it.id to it.distanceMeters }
                val items = footprints.map { fp ->
                    val present = fp.sessionIds.filter { distanceById.containsKey(it) }
                    FootprintListItem(
                        id = fp.id,
                        name = fp.name,
                        createdAtMillis = fp.createdAtMillis,
                        sessionCount = present.size,
                        totalDistanceMeters = present.sumOf { (distanceById[it] ?: 0f).toDouble() }.toFloat(),
                    )
                }
                _listState.value = ListState(isLoading = false, items = items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "refreshList failed", e)
                _listState.value = _listState.value.copy(isLoading = false)
            }
        }
    }

    fun create(name: String, sessionIds: Collection<String>) {
        viewModelScope.launch {
            try {
                footprintStorage.save(name, sessionIds.toList())
                refreshList()
            } catch (e: Exception) {
                Logger.e(TAG, "create footprint failed", e)
            }
        }
    }

    fun deleteFootprint(id: String) {
        viewModelScope.launch {
            try {
                footprintStorage.delete(id)
                if (_selectedId.value == id) closeDetail()
                refreshList()
            } catch (e: Exception) {
                Logger.e(TAG, "delete footprint failed", e)
            }
        }
    }

    fun openDetail(id: String) {
        _selectedId.value = id
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _detailState.value = DetailState(isLoading = true)
            try {
                val fp = footprintStorage.get(id) ?: run {
                    _detailState.value = DetailState(isLoading = false)
                    return@launch
                }
                val distanceById = historyStorage.list().associate { it.id to it.distanceMeters }
                val routes = ArrayList<List<TrackingPoint>>()
                var totalDistance = 0f
                var totalPoints = 0
                for (sid in fp.sessionIds) {
                    totalDistance += distanceById[sid] ?: 0f
                    if (totalPoints >= MAX_TOTAL_POINTS) continue
                    val pts = historyStorage.loadPoints(sid, PER_SESSION_POINTS)
                    if (pts.size >= 2) {
                        routes.add(pts)
                        totalPoints += pts.size
                    }
                }
                _detailState.value = DetailState(
                    isLoading = false,
                    name = fp.name,
                    routes = routes,
                    sessionCount = fp.sessionIds.size,
                    totalDistanceMeters = totalDistance,
                    totalPoints = totalPoints,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "openDetail failed", e)
                _detailState.value = DetailState(isLoading = false)
            }
        }
    }

    fun closeDetail() {
        _selectedId.value = null
        _detailState.value = DetailState()
    }

    companion object {
        private const val TAG = "FootprintsViewModel"
        private const val PER_SESSION_POINTS = 150
        private const val MAX_TOTAL_POINTS = 20_000
    }
}
