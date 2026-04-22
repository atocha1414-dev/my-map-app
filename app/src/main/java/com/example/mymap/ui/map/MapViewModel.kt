package com.example.mymap.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.mymap.data.local.MapFileStorage
import com.example.mymap.data.remote.MapDownloader
import com.example.mymap.data.repository.MapRepositoryImpl
import com.example.mymap.domain.repository.MapRepository
import com.example.mymap.util.Logger

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MapViewModel"
    }

    private val fileStorage = MapFileStorage(application)
    private val downloader = MapDownloader(fileStorage)
    private val repository: MapRepository = MapRepositoryImpl(fileStorage, downloader)

    /**
     * 로컬 지도 파일 경로
     */
    val mapFilePath: String? = repository.getLocalMapPath()

    init {
        Logger.d(TAG, "MapViewModel initialized, mapFilePath: $mapFilePath")
    }
}