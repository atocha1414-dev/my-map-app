package com.connor.mymap.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.local.ThumbnailStorage
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.data.thumbnail.ThumbnailGenerator
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.TrackingSession
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val historyStorage = TrackingHistoryStorage(application)
    private val thumbnailStorage = ThumbnailStorage(application)

    // 변경 이유: 썸네일 생성에 오프라인 MBTiles 경로가 필요하다.
    // 지도 파일이 아직 없거나 사용자가 지웠다면 generator는 null로 두고
    // ProfileScreen에서 경로 라인만 그리는 폴백으로 떨어뜨린다.
    private val thumbnailGenerator: ThumbnailGenerator? =
        MapFileStorage(application)
            .getMapFile(Constants.Map.DEFAULT_MAP_FILENAME)
            .takeIf { it.exists() }
            ?.absolutePath
            ?.let { mapPath ->
                ThumbnailGenerator(application, mapPath, thumbnailStorage)
            }

    private val _sessions = MutableStateFlow<List<TrackingSession>>(emptyList())
    val sessions: StateFlow<List<TrackingSession>> = _sessions.asStateFlow()

    /**
     * 날짜별로 그룹핑된 세션 목록.
     * 변경 이유: 세션이 수천 개 쌓이면 ProfileScreen의 remember{...} 블록에서
     * Calendar/SimpleDateFormat을 매 세션마다 생성하면서 메인 스레드가 막혀 ANR 위험이 있다.
     * 그룹핑을 Default 디스패처에서 수행하고, Calendar/포매터를 1회만 생성해 재사용한다.
     */
    val sessionGroups: StateFlow<List<SessionGroup>> = _sessions
        .map { groupSessionsByDate(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    fun selectSession(id: String) { _selectedSessionId.value = id }
    fun clearSelection() { _selectedSessionId.value = null }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _sessions.value = historyStorage.list()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load tracking history", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun loadPoints(id: String, maxPoints: Int = 300): List<TrackingPoint> =
        try {
            historyStorage.loadPoints(id, maxPoints)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load tracking points", e)
            emptyList()
        }

    /**
     * 썸네일 PNG 파일을 반환한다.
     * 이미 있으면 그대로, 없으면 즉시 생성. 지도 파일이 없거나 생성 실패 시 null.
     * null이면 호출부에서 경로 라인만 그리는 폴백으로 떨어진다.
     */
    suspend fun getThumbnailFile(id: String): File? {
        if (thumbnailStorage.exists(id)) return thumbnailStorage.getFile(id)

        val generator = thumbnailGenerator ?: return null
        val points = loadPoints(id)
        if (points.isEmpty()) return null
        return generator.generate(id, points)
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                historyStorage.delete(id)
                thumbnailStorage.delete(id)
                _sessions.value = _sessions.value.filterNot { it.id == id }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete tracking session", e)
            }
        }
    }

    fun deleteSessions(ids: Set<String>) {
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    historyStorage.delete(id)
                    thumbnailStorage.delete(id)
                }
                _sessions.value = _sessions.value.filterNot { it.id in ids }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete tracking sessions", e)
            }
        }
    }

    private fun groupSessionsByDate(sessions: List<TrackingSession>): List<SessionGroup> {
        if (sessions.isEmpty()) return emptyList()

        val cal = Calendar.getInstance()
        fun dayStart(ms: Long): Long {
            cal.timeInMillis = ms
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val todayStart = dayStart(System.currentTimeMillis())
        val dayMs = 24L * 60L * 60L * 1000L
        val dateFormat = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA)

        return sessions.sortedByDescending { it.startedAtMillis }
            .groupBy { session ->
                val diffDays = (todayStart - dayStart(session.startedAtMillis)) / dayMs
                when (diffDays) {
                    0L -> "오늘"
                    1L -> "어제"
                    else -> dateFormat.format(Date(session.startedAtMillis))
                }
            }
            .map { (label, items) -> SessionGroup(label, items) }
    }
}

data class SessionGroup(
    val label: String,
    val sessions: List<TrackingSession>
)
