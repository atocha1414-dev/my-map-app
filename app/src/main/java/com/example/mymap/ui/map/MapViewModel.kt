package com.example.mymap.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymap.data.local.MapFileStorage
import com.example.mymap.data.remote.LocationProvider
import com.example.mymap.data.remote.MapDownloader
import com.example.mymap.data.repository.MapRepositoryImpl
import com.example.mymap.domain.model.UserLocation
import com.example.mymap.domain.repository.MapRepository
import com.example.mymap.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 위치 권한 상태
 */
sealed class LocationPermissionState {
    data object Idle : LocationPermissionState()
    data object ShowDisclosure : LocationPermissionState()
    data object RequestingPermission : LocationPermissionState()
    data object Granted : LocationPermissionState()
    data object Denied : LocationPermissionState()
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MapViewModel"
    }

    private val fileStorage = MapFileStorage(application)
    private val downloader = MapDownloader(fileStorage)
    private val repository: MapRepository = MapRepositoryImpl(fileStorage, downloader)
    private val locationProvider = LocationProvider(application)

    val mapFilePath: String? = repository.getLocalMapPath()

    // 위치 권한 상태
    private val _permissionState = MutableStateFlow<LocationPermissionState>(LocationPermissionState.Idle)
    val permissionState: StateFlow<LocationPermissionState> = _permissionState.asStateFlow()

    // 현재 내 위치
    private val _myLocation = MutableStateFlow<UserLocation?>(null)
    val myLocation: StateFlow<UserLocation?> = _myLocation.asStateFlow()

    init {
        Logger.d(TAG, "MapViewModel initialized, mapFilePath: $mapFilePath")
    }

    /**
     * 내 위치 버튼 탭 시 호출
     * - 권한 있으면 바로 위치 요청
     * - 권한 없으면 Disclosure 다이얼로그 띄움
     */
    fun onMyLocationClick(hasPermission: Boolean) {
        if (hasPermission) {
            fetchLocation()
        } else {
            _permissionState.value = LocationPermissionState.ShowDisclosure
        }
    }

    /**
     * Disclosure에서 "계속" 누름 → 시스템 권한 다이얼로그로
     */
    fun onDisclosureAgreed() {
        _permissionState.value = LocationPermissionState.RequestingPermission
    }

    /**
     * Disclosure 취소
     */
    fun onDisclosureDismissed() {
        _permissionState.value = LocationPermissionState.Idle
    }

    /**
     * 시스템 권한 다이얼로그 결과 처리
     */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            _permissionState.value = LocationPermissionState.Granted
            fetchLocation()
        } else {
            _permissionState.value = LocationPermissionState.Denied
            Logger.w(TAG, "Location permission denied")
        }
    }

    /**
     * 실제로 위치 가져오기
     */
    private fun fetchLocation() {
        viewModelScope.launch {
            val location = locationProvider.getCurrentLocation()
            if (location != null) {
                _myLocation.value = location
                Logger.d(TAG, "Location updated: $location")
            } else {
                Logger.w(TAG, "Could not fetch location")
            }
        }
    }

    /**
     * 권한 거부 상태 초기화 (사용자가 다시 시도할 때)
     */
    fun resetPermissionState() {
        _permissionState.value = LocationPermissionState.Idle
    }
}