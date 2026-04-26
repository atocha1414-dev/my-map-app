package com.connor.mymap.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.data.local.TrackingStorage
import com.connor.mymap.data.remote.LocationProvider
import com.connor.mymap.data.remote.MapDownloader
import com.connor.mymap.data.repository.MapRepositoryImpl
import com.connor.mymap.data.tracking.TrackingService
import com.connor.mymap.data.tracking.TrackingState
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.UserLocation
import com.connor.mymap.domain.repository.MapRepository
import com.connor.mymap.util.Logger
import com.connor.mymap.util.PermissionHelper
import com.connor.mymap.util.TrackingCalculator
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
    data object RequestingNotificationPermission : LocationPermissionState()
    data object CheckLocationSettings : LocationPermissionState()
    data object Granted : LocationPermissionState()
    data object Denied : LocationPermissionState()
    data object NotificationPermissionDenied : LocationPermissionState()
    data object BackgroundPermissionNeeded : LocationPermissionState()
    data object BackgroundPermissionDenied : LocationPermissionState()
    data object LocationSettingsDenied : LocationPermissionState()
    data object LocationSettingsUnavailable : LocationPermissionState()
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MapViewModel"
        private const val MIN_SAVED_DURATION_MILLIS = 10_000L
    }

    private val fileStorage = MapFileStorage(application)
    private val downloader = MapDownloader(fileStorage)
    private val repository: MapRepository = MapRepositoryImpl(fileStorage, downloader)
    private val locationProvider = LocationProvider(application)
    private val trackingStorage = TrackingStorage(application)
    private val historyStorage = TrackingHistoryStorage(application)

    val mapFilePath: String? = repository.getLocalMapPath()

    // 위치 권한 상태
    private val _permissionState = MutableStateFlow<LocationPermissionState>(LocationPermissionState.Idle)
    val permissionState: StateFlow<LocationPermissionState> = _permissionState.asStateFlow()

    // 현재 내 위치
    private val _myLocation = MutableStateFlow<UserLocation?>(null)
    val myLocation: StateFlow<UserLocation?> = _myLocation.asStateFlow()

    // 일시정지 상태: 기록이 진행됐으나 사용자가 정지한 경우 true.
    // GPS 포인트가 하나도 없어도 정지 vs 초기화를 UI에서 구분하기 위해 별도 관리한다.
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // 일시정지 시점까지의 경과 시간(ms). GPS 포인트 유무와 무관하게 실제 타이머를 보존한다.
    private val _pausedDurationMillis = MutableStateFlow(0L)
    val pausedDurationMillis: StateFlow<Long> = _pausedDurationMillis.asStateFlow()

    val isTracking: StateFlow<Boolean> = TrackingState.isTracking
    val trackPoints: StateFlow<List<TrackingPoint>> = TrackingState.trackPoints
    val trackingStartedAtMillis: StateFlow<Long?> = TrackingState.trackingStartedAtMillis

    private var pendingTrackingStart = false

    init {
        // 변경 이유: 기록 파일이 커지면 메인 스레드 동기 IO가 ANR을 유발할 수 있어
        // 초기 로딩을 viewModelScope로 옮긴다.
        // 더불어 디스크에 세션 시작 시각이 남아 있다면 기록이 진행 중이던 상태로 간주하고
        // ForegroundService를 다시 띄워 사용자가 앱을 재진입했을 때도 동일한 경험을 보장한다.
        viewModelScope.launch {
            val points = trackingStorage.readPoints()
            TrackingState.setTrackPoints(points)

            val sessionStart = trackingStorage.readSessionStart()
            if (sessionStart != null) {
                TrackingState.startTracking(sessionStart)
                if (PermissionHelper.hasLocationPermission(getApplication())) {
                    TrackingService.start(getApplication())
                    Logger.d(TAG, "Resumed tracking session started at $sessionStart")
                } else {
                    // 권한이 사라진 채 앱이 재진입한 경우, 디스크의 세션 메타만 정리한다.
                    trackingStorage.clearSession()
                    TrackingState.setTracking(false)
                    Logger.w(TAG, "Session marker found but location permission missing, cleared")
                }
            }
        }
        Logger.d(TAG, "MapViewModel initialized, mapFilePath: $mapFilePath")
    }

    /**
     * 내 위치 버튼 탭 시 호출
     * - 권한 있으면 GPS/위치 서비스 활성화 여부 확인
     * - 권한 없으면 Disclosure 다이얼로그 띄움
     */
    fun onMyLocationClick(hasPermission: Boolean) {
        if (hasPermission) {
            _permissionState.value = LocationPermissionState.CheckLocationSettings
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
            continueTrackingPermissionFlow()
        } else {
            pendingTrackingStart = false
            _permissionState.value = LocationPermissionState.Denied
            Logger.w(TAG, "Location permission denied")
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            continueTrackingPermissionFlow()
        } else {
            pendingTrackingStart = false
            _permissionState.value = LocationPermissionState.NotificationPermissionDenied
            Logger.w(TAG, "Notification permission denied")
        }
    }

    /**
     * GPS/위치 서비스가 켜져 있거나 사용자가 시스템 다이얼로그에서 켠 경우
     */
    fun onLocationSettingsReady() {
        _permissionState.value = LocationPermissionState.Granted
        if (pendingTrackingStart) {
            pendingTrackingStart = false
            _isPaused.value = false
            TrackingService.start(getApplication())
            // 변경 이유: 기록 시작 버튼은 서비스만 켜고 지도 화면의 현재 위치 상태를 갱신하지 않아
            // 파란 위치 포인터와 카메라 이동이 즉시 보이지 않았다.
            // 기록 시작 직후에도 현재 위치를 한 번 가져와 지도 초기 위치를 맞춘다.
            fetchLocation()
        } else {
            fetchLocation()
        }
    }

    /**
     * 사용자가 GPS/위치 서비스 켜기를 취소한 경우
     */
    fun onLocationSettingsDenied() {
        pendingTrackingStart = false
        _permissionState.value = LocationPermissionState.LocationSettingsDenied
        Logger.w(TAG, "Location settings change denied")
    }

    /**
     * 이 기기에서 위치 설정 해결 다이얼로그를 띄울 수 없는 경우
     */
    fun onLocationSettingsUnavailable() {
        pendingTrackingStart = false
        _permissionState.value = LocationPermissionState.LocationSettingsUnavailable
        Logger.w(TAG, "Location settings are unavailable")
    }

    fun onStartTrackingClick(
        hasForegroundPermission: Boolean,
        hasBackgroundPermission: Boolean,
        hasNotificationPermission: Boolean
    ) {
        pendingTrackingStart = true

        when {
            !hasForegroundPermission -> {
                _permissionState.value = LocationPermissionState.ShowDisclosure
            }
            !hasNotificationPermission -> {
                _permissionState.value = LocationPermissionState.RequestingNotificationPermission
            }
            !hasBackgroundPermission -> {
                _permissionState.value = LocationPermissionState.BackgroundPermissionNeeded
            }
            else -> {
                _permissionState.value = LocationPermissionState.CheckLocationSettings
            }
        }
    }

    fun onBackgroundPermissionSettingsReturned(hasBackgroundPermission: Boolean) {
        if (hasBackgroundPermission) {
            _permissionState.value = LocationPermissionState.CheckLocationSettings
        } else {
            pendingTrackingStart = false
            _permissionState.value = LocationPermissionState.BackgroundPermissionDenied
        }
    }

    fun onStopTrackingClick() {
        pendingTrackingStart = false
        _isPaused.value = true
        val startedAt = TrackingState.trackingStartedAtMillis.value
        val sessionDuration =
            if (startedAt != null) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            else 0L
        // 이전 세션 누적 시간 위에 현재 세션을 더해 보존한다.
        _pausedDurationMillis.value = _pausedDurationMillis.value + sessionDuration
        TrackingService.stop(getApplication())
    }

    fun onResetTrackingClick() {
        pendingTrackingStart = false

        // 클리어 전에 현재 누적 데이터를 캡처해 히스토리에 남긴다.
        val startedAt = TrackingState.trackingStartedAtMillis.value
        val currentSessionDuration =
            if (TrackingState.isTracking.value && startedAt != null)
                (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            else 0L
        val totalDurationMillis = _pausedDurationMillis.value + currentSessionDuration
        val pointsSnapshot = TrackingState.trackPoints.value

        _isPaused.value = false
        _pausedDurationMillis.value = 0L
        TrackingService.stop(getApplication())

        viewModelScope.launch {
            // 의미 있는 기록만 저장: 10초 이상 진행했거나 GPS 포인트가 1개 이상.
            if (totalDurationMillis >= MIN_SAVED_DURATION_MILLIS || pointsSnapshot.isNotEmpty()) {
                val endedAt = System.currentTimeMillis()
                val distance = TrackingCalculator.calculateStats(pointsSnapshot).distanceMeters
                historyStorage.save(
                    startedAtMillis = (endedAt - totalDurationMillis).coerceAtLeast(0L),
                    endedAtMillis = endedAt,
                    durationMillis = totalDurationMillis,
                    distanceMeters = distance,
                    points = pointsSnapshot
                )
            }
            trackingStorage.clearPoints()
            TrackingState.clearTrackPoints()
        }
    }

    fun onClearTrackClick() {
        viewModelScope.launch {
            trackingStorage.clearPoints()
            trackingStorage.clearSession()
            TrackingState.clearTrackPoints()
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

    private fun continueTrackingPermissionFlow() {
        if (!pendingTrackingStart) {
            _permissionState.value = LocationPermissionState.CheckLocationSettings
            return
        }

        when {
            !PermissionHelper.hasNotificationPermission(getApplication()) -> {
                _permissionState.value = LocationPermissionState.RequestingNotificationPermission
            }
            !PermissionHelper.hasBackgroundLocationPermission(getApplication()) -> {
                _permissionState.value = LocationPermissionState.BackgroundPermissionNeeded
            }
            else -> {
                _permissionState.value = LocationPermissionState.CheckLocationSettings
            }
        }
    }
}
