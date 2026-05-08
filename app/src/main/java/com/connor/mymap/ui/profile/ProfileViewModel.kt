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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProfileViewModel"
        private const val INITIAL_PREVIEW_COUNT = 60
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

    private val _sessionGroups = MutableStateFlow<List<SessionGroup>>(emptyList())
    val sessionGroups: StateFlow<List<SessionGroup>> = _sessionGroups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()
    private val _collapsedGroupLabels = MutableStateFlow<Set<String>>(emptySet())
    val collapsedGroupLabels: StateFlow<Set<String>> = _collapsedGroupLabels.asStateFlow()
    private var refreshJob: Job? = null

    fun selectSession(id: String) { _selectedSessionId.value = id }
    fun clearSelection() { _selectedSessionId.value = null }
    fun toggleGroupCollapsed(label: String) {
        _collapsedGroupLabels.value = if (label in _collapsedGroupLabels.value) {
            _collapsedGroupLabels.value - label
        } else {
            _collapsedGroupLabels.value + label
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                // 변경 이유: 첫 진입에서는 전체 파일을 전부 열기 전에 최근 N개만 먼저 보여줘
                // 로딩 스피너 체류 시간을 줄이고 목록을 빠르게 표시한다.
                if (_sessions.value.isEmpty()) {
                    val recent = historyStorage.listRecent(INITIAL_PREVIEW_COUNT)
                    if (recent.isNotEmpty()) {
                        _sessions.value = recent
                        _sessionGroups.value = groupSessionsByDate(recent)
                        reconcileCollapsedLabels(_sessionGroups.value)
                        _isLoading.value = false
                    }
                }

                val full = historyStorage.list()
                _sessions.value = full
                _sessionGroups.value = groupSessionsByDate(full)
                reconcileCollapsedLabels(_sessionGroups.value)
            } catch (e: CancellationException) {
                throw e
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
    suspend fun getThumbnailFileIfExists(id: String): File? =
        try {
            if (thumbnailStorage.exists(id)) thumbnailStorage.getFile(id) else null
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to check thumbnail existence", e)
            null
        }

    /**
     * 변경 이유: 첫 진입에서 썸네일 생성이 Mutex로 직렬화될 때도
     * UI는 폴백(경로 라인)을 먼저 보여줄 수 있게 생성 API를 분리해둔다.
     */
    suspend fun ensureThumbnailFile(id: String, points: List<TrackingPoint>): File? {
        if (thumbnailStorage.exists(id)) return thumbnailStorage.getFile(id)
        if (points.isEmpty()) return null

        val generator = thumbnailGenerator ?: return null
        return generator.generate(id, points)
    }

    suspend fun getThumbnailFile(id: String): File? {
        val points = loadPoints(id)
        return ensureThumbnailFile(id, points)
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                historyStorage.delete(id)
                thumbnailStorage.delete(id)
                _sessions.value = _sessions.value.filterNot { it.id == id }
                _sessionGroups.value = removeSessionsFromGroups(_sessionGroups.value, setOf(id))
                reconcileCollapsedLabels(_sessionGroups.value)
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
                _sessionGroups.value = removeSessionsFromGroups(_sessionGroups.value, ids)
                reconcileCollapsedLabels(_sessionGroups.value)
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

    /**
     * 변경 이유: 단건/소량 삭제마다 전체 세션을 다시 날짜 그룹핑하지 않고,
     * 기존 그룹에서 삭제 대상만 빼서 증분 갱신한다.
     */
    private fun removeSessionsFromGroups(
        groups: List<SessionGroup>,
        ids: Set<String>
    ): List<SessionGroup> {
        if (groups.isEmpty() || ids.isEmpty()) return groups

        return groups.mapNotNull { group ->
            val remaining = group.sessions.filterNot { it.id in ids }
            if (remaining.isEmpty()) null else group.copy(sessions = remaining)
        }
    }

    private fun reconcileCollapsedLabels(groups: List<SessionGroup>) {
        val valid = groups.asSequence().map { it.label }.toSet()
        _collapsedGroupLabels.value = _collapsedGroupLabels.value.intersect(valid)
    }
}

data class SessionGroup(
    val label: String,
    val sessions: List<TrackingSession>
)
