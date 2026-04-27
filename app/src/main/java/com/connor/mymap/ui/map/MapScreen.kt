package com.connor.mymap.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.ui.common.ErrorView
import com.connor.mymap.util.PermissionHelper
import com.connor.mymap.util.Logger
import com.connor.mymap.util.TrackingCalculator
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val mapFilePath = viewModel.mapFilePath

    if (mapFilePath == null) {
        ErrorView(
            title = "지도 파일 없음",
            message = "지도 파일을 찾을 수 없습니다.\n앱을 다시 실행해주세요.",
            onRetry = { }
        )
        return
    }

    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val myLocation by viewModel.myLocation.collectAsStateWithLifecycle()
    val isTracking by viewModel.isTracking.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val trackPoints by viewModel.trackPoints.collectAsStateWithLifecycle()
    val trackingStartedAtMillis by viewModel.trackingStartedAtMillis.collectAsStateWithLifecycle()
    val pausedDurationMillis by viewModel.pausedDurationMillis.collectAsStateWithLifecycle()
    val trackingStats = remember(trackPoints) {
        TrackingCalculator.calculateStats(trackPoints)
    }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isTracking) {
        while (isTracking) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 정책 반영: GPS/위치 서비스 켜기 요청은 약관 동의 직후가 아니라
    // 사용자가 지도 화면에서 "내 위치" 버튼을 누른 뒤에만 실행한다.
    // 이렇게 해야 위치 접근 요청이 실제 기능 사용 맥락 안에서 발생한다.
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onLocationSettingsReady()
        } else {
            viewModel.onLocationSettingsDenied()
        }
    }

    // 정책 반영: 시스템 위치 권한도 앱 시작/약관 화면에서 미리 요청하지 않고
    // Prominent Disclosure 확인 후 내 위치 기능 사용 시점에만 요청한다.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onPermissionResult(granted)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    val backgroundPermissionSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onBackgroundPermissionSettingsReturned(
            hasBackgroundPermission = PermissionHelper.hasBackgroundLocationPermission(context)
        )
    }

    // 안드로이드 버전별로 "항상 허용" 토글까지의 진입 단계가 다르다.
    // - API 30+ : requestPermissions가 OS의 "내 앱 위치 권한" 페이지를 직접 열어, 사용자는 토글만 누르면 된다.
    // - API 29  : 시스템 다이얼로그가 떠서 "항상 허용" 라디오를 바로 선택할 수 있다.
    // - API <29 : ACCESS_BACKGROUND_LOCATION 자체가 별도 권한이 아니므로 이 경로로 오지 않는다.
    // 그래도 사용자가 이전에 영구 거부한 경우엔 다이얼로그/페이지가 안 떠서 즉시 denied로 돌아오므로,
    // 그때만 앱 정보 deep-link로 fallback한다.
    val backgroundLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onBackgroundPermissionSettingsReturned(hasBackgroundPermission = true)
        } else {
            openPermissionSettingsSafely(
                context = context,
                launcher = backgroundPermissionSettingsLauncher
            )
        }
    }

    // 변경 이유: 같은 permissionState를 두 LaunchedEffect가 동시에 관찰하면
    // 한 쪽이 resetPermissionState()를 부를 때 다른 쪽이 잘못된 시점에 다시 트리거될 수 있다.
    // 시스템 다이얼로그/Snackbar 트리거를 하나의 when으로 합쳐 race를 제거한다.
    LaunchedEffect(permissionState) {
        when (permissionState) {
            LocationPermissionState.RequestingPermission -> {
                permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
            }
            LocationPermissionState.RequestingNotificationPermission -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LocationPermissionState.CheckLocationSettings -> {
                checkLocationSettings(
                    context = context,
                    onReady = viewModel::onLocationSettingsReady,
                    onResolutionRequired = { exception ->
                        val request = IntentSenderRequest.Builder(exception.resolution).build()
                        locationSettingsLauncher.launch(request)
                    },
                    onUnavailable = viewModel::onLocationSettingsUnavailable
                )
            }
            LocationPermissionState.Denied -> {
                snackbarHostState.showSnackbar(
                    message = "위치 권한이 필요합니다. 설정에서 허용해주세요.",
                    actionLabel = "확인"
                )
                viewModel.resetPermissionState()
            }
            LocationPermissionState.LocationSettingsDenied -> {
                snackbarHostState.showSnackbar(
                    message = "현재 위치를 보려면 기기의 위치 서비스를 켜주세요.",
                    actionLabel = "확인"
                )
                viewModel.resetPermissionState()
            }
            LocationPermissionState.LocationSettingsUnavailable -> {
                snackbarHostState.showSnackbar(
                    message = "이 기기에서는 위치 설정을 자동으로 열 수 없습니다.",
                    actionLabel = "확인"
                )
                viewModel.resetPermissionState()
            }
            LocationPermissionState.BackgroundPermissionDenied -> {
                snackbarHostState.showSnackbar(
                    message = "항상 허용을 선택해야 앱을 닫아도 경로를 기록할 수 있습니다.",
                    actionLabel = "확인"
                )
                viewModel.resetPermissionState()
            }
            LocationPermissionState.NotificationPermissionDenied -> {
                snackbarHostState.showSnackbar(
                    message = "기록 중 알림을 표시하려면 알림 권한이 필요합니다.",
                    actionLabel = "확인"
                )
                viewModel.resetPermissionState()
            }
            else -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // 지도
        MapLibreView(
            mapFilePath = mapFilePath,
            myLocation = myLocation,
            trackPoints = trackPoints,
            modifier = Modifier.fillMaxSize()
        )

        if (isTracking || isPaused || trackPoints.isNotEmpty()) {
            val displayDurationMillis = when {
                isTracking -> {
                    val startedAtMillis = trackingStartedAtMillis ?: nowMillis
                    // 이전 세션 누적 시간 + 현재 세션 경과 시간
                    pausedDurationMillis + (nowMillis - startedAtMillis).coerceAtLeast(0L)
                }
                isPaused -> pausedDurationMillis
                else -> trackingStats.durationMillis
            }
            val displayAverageSpeed = if (displayDurationMillis > 0L) {
                trackingStats.distanceMeters / (displayDurationMillis / 1_000f)
            } else {
                0f
            }

            TrackingStatsPanel(
                distanceMeters = trackingStats.distanceMeters,
                durationMillis = displayDurationMillis,
                averageSpeedMetersPerSecond = displayAverageSpeed,
                latestAccuracyMeters = trackingStats.latestAccuracyMeters,
                isTracking = isTracking,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()   // 카메라·상태바 아래부터 배치
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            when {
                isTracking -> {
                    // 기록 중: 초기화(위) + 일시정지(아래)
                    FloatingActionButton(
                        onClick = { showResetConfirm = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "이동 경로 초기화"
                        )
                    }
                    FloatingActionButton(
                        onClick = { viewModel.onStopTrackingClick() },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "이동 경로 기록 일시정지"
                        )
                    }
                }
                isPaused || trackPoints.isNotEmpty() -> {
                    // 일시정지 상태: 초기화(위) + 재시작(아래)
                    FloatingActionButton(
                        onClick = { showResetConfirm = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "이동 경로 초기화"
                        )
                    }
                    FloatingActionButton(
                        onClick = {
                            viewModel.onStartTrackingClick(
                                hasForegroundPermission = PermissionHelper.hasLocationPermission(context),
                                hasBackgroundPermission = PermissionHelper.hasBackgroundLocationPermission(context),
                                hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
                            )
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "이동 경로 기록 재시작"
                        )
                    }
                }
                else -> {
                    // 초기 상태: 시작만
                    FloatingActionButton(
                        onClick = {
                            viewModel.onStartTrackingClick(
                                hasForegroundPermission = PermissionHelper.hasLocationPermission(context),
                                hasBackgroundPermission = PermissionHelper.hasBackgroundLocationPermission(context),
                                hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "이동 경로 기록 시작"
                        )
                    }
                }
            }

            // 내 위치 버튼
            // 이 버튼이 1회성 현재 위치 확인을 위한 위치 권한 요청과 GPS 설정 팝업 진입점이다.
            FloatingActionButton(
                onClick = {
                    viewModel.onMyLocationClick(
                        hasPermission = PermissionHelper.hasLocationPermission(context)
                    )
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "내 위치"
                )
            }
        }

        if (trackPoints.isNotEmpty() && !isTracking) {
            FloatingActionButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "이동 경로 삭제"
                )
            }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("기록을 초기화할까요?") },
                text = { Text("지금까지 기록한 이동 경로와 시간이 모두 삭제됩니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        viewModel.onResetTrackingClick()
                    }) { Text("초기화") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("취소") }
                }
            )
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("경로를 지울까요?") },
                text = { Text("화면에 표시된 이동 경로가 삭제됩니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        viewModel.onClearTrackClick()
                    }) { Text("삭제") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("취소") }
                }
            )
        }

        // Prominent Disclosure 다이얼로그
        if (permissionState == LocationPermissionState.ShowDisclosure) {
            LocationDisclosureDialog(
                onAgree = { viewModel.onDisclosureAgreed() },
                onDismiss = { viewModel.onDisclosureDismissed() }
            )
        }

        if (permissionState == LocationPermissionState.BackgroundPermissionNeeded) {
            BackgroundLocationPermissionDialog(
                onOpenSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationPermissionLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    } else {
                        viewModel.onBackgroundPermissionSettingsReturned(
                            hasBackgroundPermission = true
                        )
                    }
                },
                onDismiss = { viewModel.resetPermissionState() }
            )
        }

        // Snackbar (권한 거부 안내)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun TrackingStatsPanel(
    distanceMeters: Float,
    durationMillis: Long,
    averageSpeedMetersPerSecond: Float,
    latestAccuracyMeters: Float?,
    isTracking: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isTracking) "기록 중" else "최근 기록",
                style = MaterialTheme.typography.labelLarge,
                color = if (isTracking) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = formatDistance(distanceMeters),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "시간 ${formatDuration(durationMillis)} · 평균 ${formatSpeed(averageSpeedMetersPerSecond)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            latestAccuracyMeters?.let { accuracy ->
                Text(
                    text = "GPS 정확도 ±${accuracy.toInt()}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDistance(distanceMeters: Float): String {
    return if (distanceMeters >= 1_000f) {
        "%.2f km".format(distanceMeters / 1_000f)
    } else {
        "${distanceMeters.toInt()} m"
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatSpeed(speedMetersPerSecond: Float): String {
    val kmh = speedMetersPerSecond * 3.6f
    return "%.1f km/h".format(kmh)
}

@Composable
private fun BackgroundLocationPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("항상 허용으로 바꿔주세요") },
        text = {
            Column {
                Text("앱을 닫아도 이동 경로를 계속 그리려면 위치 권한을 '항상 허용'으로 바꿔야 합니다.")

                Spacer(Modifier.height(12.dp))

                Text("'허용하기'를 누르면 위치 권한 화면이 열립니다. '항상 허용'을 선택해주세요.")

                Spacer(Modifier.height(12.dp))

                Text("기록은 사용자가 시작한 동안만 진행되고, 위치 정보는 기기 안에만 저장됩니다.")
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("허용하기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

private fun openPermissionSettingsSafely(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intents = createPermissionSettingsIntents(context)

    for (intent in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                launcher.launch(intent)
                return
            }
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "Permission settings activity not found: ${intent.action}")
        } catch (e: SecurityException) {
            Logger.w(TAG, "Permission settings activity blocked: ${intent.action}")
        } catch (e: RuntimeException) {
            Logger.w(TAG, "Permission settings activity failed: ${intent.action}")
        }
    }
}

private fun createPermissionSettingsIntents(context: Context): List<Intent> {
    // Android 공개 API만으로는 모든 기기에서 "앱 정보 > 권한 > 위치" 화면을
    // 100% 보장해서 열 수 없다. 그래서 가능한 경우에는 위치 권한 상세 화면을 먼저 시도하고,
    // 지원하지 않는 기기에서는 앱 권한 목록, 마지막으로 앱 정보 화면으로 fallback한다.
    val locationPermissionIntent = Intent(ACTION_MANAGE_APP_PERMISSION).apply {
        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(EXTRA_PERMISSION_NAME, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    val appPermissionsIntent = Intent(ACTION_MANAGE_APP_PERMISSIONS).apply {
        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
    }

    val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

    return listOf(
        locationPermissionIntent,
        appPermissionsIntent,
        appDetailsIntent
    )
}

private const val TAG = "MapScreen"
private const val ACTION_MANAGE_APP_PERMISSION = "android.intent.action.MANAGE_APP_PERMISSION"
private const val ACTION_MANAGE_APP_PERMISSIONS = "android.intent.action.MANAGE_APP_PERMISSIONS"
private const val EXTRA_PERMISSION_NAME = "android.intent.extra.PERMISSION_NAME"

private fun checkLocationSettings(
    context: Context,
    onReady: () -> Unit,
    onResolutionRequired: (ResolvableApiException) -> Unit,
    onUnavailable: () -> Unit
) {
    val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        10_000L
    ).build()

    val settingsRequest = LocationSettingsRequest.Builder()
        .addLocationRequest(locationRequest)
        .setAlwaysShow(true)
        .build()

    LocationServices.getSettingsClient(context)
        .checkLocationSettings(settingsRequest)
        .addOnSuccessListener { onReady() }
        .addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                onResolutionRequired(exception)
            } else {
                onUnavailable()
            }
        }
}
