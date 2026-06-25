package com.connor.mymap.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.remote.ManifestRepository
import com.connor.mymap.data.remote.MapDownloader
import com.connor.mymap.data.repository.MapRepositoryImpl
import com.connor.mymap.domain.model.DownloadState
import com.connor.mymap.domain.model.MapCatalog
import com.connor.mymap.domain.model.MapRegion
import com.connor.mymap.domain.repository.MapRepository
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import com.connor.mymap.util.RegionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DownloadViewModel"
    }

    /** 지도 카탈로그(manifest) 로드 상태 */
    sealed interface CatalogState {
        data object Loading : CatalogState
        data class Loaded(val catalog: MapCatalog) : CatalogState
        data object Error : CatalogState
    }

    private val fileStorage = MapFileStorage(application)
    private val downloader = MapDownloader(fileStorage)
    private val repository: MapRepository = MapRepositoryImpl(fileStorage, downloader)
    private val manifestRepo = ManifestRepository()

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Checking)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // ── 지역 선택 단계 상태 ──
    private val _catalog = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private val _selectedRegion = MutableStateFlow<MapRegion?>(null)
    val selectedRegion: StateFlow<MapRegion?> = _selectedRegion.asStateFlow()

    private val _detecting = MutableStateFlow(false)
    val detecting: StateFlow<Boolean> = _detecting.asStateFlow()

    // 위치 자동 감지 실패/거부 → UI가 "직접 선택"을 안내
    private val _detectFailed = MutableStateFlow(false)
    val detectFailed: StateFlow<Boolean> = _detectFailed.asStateFlow()

    init {
        checkMapStatus()
    }

    private fun checkMapStatus() {
        viewModelScope.launch {
            val s = repository.checkMapStatus()
            _state.value = s
            Logger.d(TAG, "Initial state: $s")
            if (s is DownloadState.NeedsDownload) loadCatalog()
        }
    }

    /** 카탈로그(manifest.json) 로드. */
    fun loadCatalog() {
        viewModelScope.launch {
            _catalog.value = CatalogState.Loading
            manifestRepo.fetch().fold(
                onSuccess = { _catalog.value = CatalogState.Loaded(it) },
                onFailure = { _catalog.value = CatalogState.Error }
            )
        }
    }

    /** 위치 권한이 허용된 뒤 호출. 현재 위치 → 국가(미국이면 주) 자동 선택. 실패 시 detectFailed=true. */
    fun detectRegion() {
        val cat = (_catalog.value as? CatalogState.Loaded)?.catalog ?: return
        viewModelScope.launch {
            _detecting.value = true
            _detectFailed.value = false
            val region = RegionDetector.detect(getApplication(), cat)
            _detecting.value = false
            if (region != null) _selectedRegion.value = region
            else _detectFailed.value = true
        }
    }

    /** 위치 권한 거부 시 호출 → 직접 선택 안내. */
    fun onLocationDenied() {
        _detectFailed.value = true
    }

    /** 직접 선택. */
    fun selectRegion(region: MapRegion) {
        _selectedRegion.value = region
    }

    /** 선택된 지역을 단일 활성 슬롯(map.mbtiles)으로 다운로드. */
    fun startDownload() {
        val region = _selectedRegion.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = DownloadState.InProgress(0, 0)
            val result = downloader.download(
                url = region.url,
                filename = Constants.Map.DEFAULT_MAP_FILENAME,
                onProgress = { progress ->
                    _state.value = DownloadState.InProgress(progress.downloadedBytes, progress.totalBytes)
                }
            )
            _state.value = result.fold(
                onSuccess = {
                    Logger.i(TAG, "Download success: ${region.id}")
                    DownloadState.Ready
                },
                onFailure = { throwable ->
                    Logger.e(TAG, "Download failed", throwable)
                    DownloadState.Error(throwable.message ?: "다운로드 중 오류가 발생했습니다", throwable)
                }
            )
        }
    }

    fun retryDownload() {
        startDownload()
    }

    fun resetMap() {
        viewModelScope.launch {
            repository.deleteMap()
            _selectedRegion.value = null
            _state.value = DownloadState.NeedsDownload
            loadCatalog()
            Logger.d(TAG, "Map reset")
        }
    }
}
