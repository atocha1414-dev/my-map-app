package com.example.mymap.data.repository


import com.example.mymap.data.local.MapFileStorage
import com.example.mymap.data.remote.MapDownloader
import com.example.mymap.domain.model.DownloadState
import com.example.mymap.domain.repository.MapRepository
import com.example.mymap.util.Constants
import com.example.mymap.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MapRepositoryImpl(
    private val fileStorage: MapFileStorage,
    private val downloader: MapDownloader
) : MapRepository {

    companion object {
        private const val TAG = "MapRepository"
    }

    private val defaultFilename = Constants.Map.DEFAULT_MAP_FILENAME

    override suspend fun checkMapStatus(): DownloadState {
        return if (fileStorage.isMapFileValid(defaultFilename)) {
            Logger.d(TAG, "Map already downloaded")
            DownloadState.Ready
        } else {
            Logger.d(TAG, "Map needs download")
            DownloadState.NeedsDownload
        }
    }

    override fun downloadMap(): Flow<DownloadState> = flow {
        emit(DownloadState.InProgress(0, 0))

        // 진행률은 MutableStateFlow를 통해 방출하기보다 직접 emit
        // 하지만 flow 안에서 콜백으로 emit 못하므로 wrapper 필요
        //
        // 간단히 하려고 callback 결과를 emit 없이 저장하고, 마지막에만 emit
        // → 실제로는 더 나은 방법이 있어서 아래처럼 구현

        val result = downloader.download(
            filename = defaultFilename
        ) { progress ->
            // 콜백에서는 emit 불가
            // 대신 다음 Step의 ViewModel에서 처리
        }

        result.fold(
            onSuccess = {
                Logger.i(TAG, "Download success: $it")
                emit(DownloadState.Ready)
            },
            onFailure = { throwable ->
                Logger.e(TAG, "Download failed", throwable)
                emit(
                    DownloadState.Error(
                        message = throwable.message ?: "알 수 없는 오류가 발생했습니다",
                        cause = throwable
                    )
                )
            }
        )
    }

    override suspend fun deleteMap(): Boolean {
        return fileStorage.deleteMapFile(defaultFilename)
    }

    override fun getLocalMapPath(): String? {
        val file = fileStorage.getMapFile(defaultFilename)
        return if (file.exists()) file.absolutePath else null
    }
}