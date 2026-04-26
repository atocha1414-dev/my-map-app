package com.connor.mymap.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.remote.MapDownloader
import com.connor.mymap.data.repository.MapRepositoryImpl
import com.connor.mymap.domain.model.DownloadState
import com.connor.mymap.domain.repository.MapRepository
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DownloadViewModel"
    }

    private val fileStorage = MapFileStorage(application)
    private val downloader = MapDownloader(fileStorage)
    private val repository: MapRepository = MapRepositoryImpl(fileStorage, downloader)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Checking)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    init {
        checkMapStatus()
    }

    private fun checkMapStatus() {
        viewModelScope.launch {
            _state.value = repository.checkMapStatus()
            Logger.d(TAG, "Initial state: ${_state.value}")
        }
    }

    fun startDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = DownloadState.InProgress(0, 0)

            val result = downloader.download(
                url = Constants.Map.DEFAULT_MAP_URL,
                filename = Constants.Map.DEFAULT_MAP_FILENAME,
                onProgress = { progress ->
                    _state.value = DownloadState.InProgress(
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes
                    )
                }
            )

            _state.value = result.fold(
                onSuccess = {
                    Logger.i(TAG, "Download success")
                    DownloadState.Ready
                },
                onFailure = { throwable ->
                    Logger.e(TAG, "Download failed", throwable)
                    DownloadState.Error(
                        message = throwable.message ?: "다운로드 중 오류가 발생했습니다",
                        cause = throwable
                    )
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
            _state.value = DownloadState.NeedsDownload
            Logger.d(TAG, "Map reset")
        }
    }
}