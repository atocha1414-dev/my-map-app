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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
}
