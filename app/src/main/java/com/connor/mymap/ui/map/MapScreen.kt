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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.ui.common.ErrorView
import com.connor.mymap.ui.theme.BrandGradient
import com.connor.mymap.ui.theme.BrandTeal
import com.connor.mymap.ui.theme.RecordingCoral
import com.connor.mymap.util.PermissionHelper
import com.connor.mymap.util.Formats
import com.connor.mymap.util.Logger
import com.connor.mymap.util.TrackingCalculator
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    isImmersive: Boolean = false,
    showTrackingSheet: Boolean = true,
    onMapTap: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
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
    val scope = rememberCoroutineScope()
    var showSaveConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val trackingSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = trackingSheetState
    )

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
                // Android 13(API 33)+에서만 알림 런타임 권한이 존재한다.
                // 하위 버전은 권한 상수가 없어도 알림 표시가 가능하므로 바로 허용 처리한다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.onNotificationPermissionResult(granted = true)
                }
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

    val displayDurationMillis = when {
        isTracking -> {
            val startedAtMillis = trackingStartedAtMillis ?: nowMillis
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
    val hasCurrentRecord = isTracking || isPaused || trackPoints.isNotEmpty()
    val sheetPeekHeight = 116.dp

    // 기록을 시작하면 통계가 바로 보이도록 펼치고, 기록을 저장/삭제하면 시작 버튼만 보이게 접는다.
    // 몰입 모드에서는 시트를 0dp까지 내려 지도만 남긴다.
    LaunchedEffect(
        isImmersive,
        showTrackingSheet,
        isTracking,
        isPaused,
        trackPoints.isNotEmpty()
    ) {
        when {
            isImmersive || !showTrackingSheet -> trackingSheetState.partialExpand()
            isTracking || isPaused -> trackingSheetState.expand()
            trackPoints.isEmpty() -> trackingSheetState.partialExpand()
        }
    }

    BottomSheetScaffold(
        scaffoldState = bottomSheetScaffoldState,
        // 홈 지도는 카메라 상태 보존을 위해 다른 탭에서도 컴포지션에 남아 있다.
        // 이동 기록 탭에서는 시트 높이·내용·드래그를 모두 제거해 목록 위에 나타나지 않게 한다.
        sheetPeekHeight = if (isImmersive || !showTrackingSheet) 0.dp else sheetPeekHeight,
        sheetSwipeEnabled = showTrackingSheet && !isImmersive,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetTonalElevation = 3.dp,
        sheetShadowElevation = 10.dp,
        sheetDragHandle = {
            if (showTrackingSheet && !isImmersive) BottomSheetDefaults.DragHandle()
        },
        sheetContent = {
            if (showTrackingSheet) {
                TrackingControlSheet(
                    isTracking = isTracking,
                    isPaused = isPaused,
                    hasCurrentRecord = hasCurrentRecord,
                    distanceMeters = trackingStats.distanceMeters,
                    durationMillis = displayDurationMillis,
                    averageSpeedMetersPerSecond = displayAverageSpeed,
                    latestAccuracyMeters = trackingStats.latestAccuracyMeters,
                    onPrimaryAction = {
                        if (isTracking) {
                            viewModel.onStopTrackingClick()
                        } else {
                            viewModel.onStartTrackingClick(
                                hasForegroundPermission = PermissionHelper.hasLocationPermission(context),
                                hasBackgroundPermission = PermissionHelper.hasBackgroundLocationPermission(context),
                                hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
                            )
                        }
                    },
                    onFinish = { showSaveConfirm = true },
                    onDiscard = { showDiscardConfirm = true }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
    Box(modifier = Modifier.fillMaxSize()) {

        // 지도
        MapLibreView(
            mapFilePath = mapFilePath,
            myLocation = myLocation,
            trackPoints = trackPoints,
            onMapClick = onMapTap,
            modifier = Modifier.fillMaxSize()
        )

        // 몰입 모드에서는 Bottom Sheet가 숨겨지므로 최소한의 기록 상태만 지도 위에 남긴다.
        if (isImmersive) {
            RecordingStatusBadge(
                isTracking = isTracking,
                isPaused = isPaused,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
            )
        }

        // 내 위치는 Bottom Sheet에 포함하지 않고 지도 위의 독립된 버튼으로 유지한다.
        AnimatedVisibility(
            visible = showTrackingSheet && !isImmersive,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    viewModel.onMyLocationClick(
                        hasPermission = PermissionHelper.hasLocationPermission(context)
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(
                    end = 16.dp,
                    bottom = sheetPeekHeight + 16.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "내 위치"
                )
            }
        }

        if (showSaveConfirm) {
            AlertDialog(
                onDismissRequest = { showSaveConfirm = false },
                title = { Text("기록을 종료하고 저장할까요?") },
                text = { Text("지금까지의 이동 경로가 '이동 기록' 탭에 저장되고, 지도의 경로는 초기화됩니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSaveConfirm = false
                        // 초기화 전에 거리 문구를 캡처(onFinishAndSaveClick 이후 trackPoints가 비워짐).
                        val savedDistanceText = Formats.distance(trackingStats.distanceMeters)
                        viewModel.onFinishAndSaveClick()
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "$savedDistanceText 기록이 저장됐어요",
                                actionLabel = "보기",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) onNavigateToProfile()
                        }
                    }) { Text("저장") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveConfirm = false }) { Text("취소") }
                }
            )
        }

        if (showDiscardConfirm) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                title = { Text("저장하지 않고 삭제할까요?") },
                text = { Text("이번 기록이 저장되지 않고 사라집니다. 되돌릴 수 없습니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardConfirm = false
                        viewModel.onClearTrackClick()
                    }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirm = false }) { Text("취소") }
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

        // Snackbar — 저장 완료(축하: 브랜드 그라데이션)와 권한 안내(기본)를 구분해 렌더한다.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = sheetPeekHeight + 16.dp
                )
        ) { data ->
            if (data.visuals.actionLabel == "보기") {
                // 저장 완료 축하 — 그라데이션은 이 축하 순간에만(절제 원칙).
                Snackbar(
                    containerColor = Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(BrandGradient)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                data.visuals.message,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "이동 기록 탭에서 확인할 수 있습니다",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { data.performAction() }) {
                            Text("보기", color = BrandTeal)
                        }
                    }
                }
            } else {
                Snackbar(snackbarData = data)
            }
        }
    }
}
}

@Composable
private fun TrackingControlSheet(
    isTracking: Boolean,
    isPaused: Boolean,
    hasCurrentRecord: Boolean,
    distanceMeters: Float,
    durationMillis: Long,
    averageSpeedMetersPerSecond: Float,
    latestAccuracyMeters: Float?,
    onPrimaryAction: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "sheetRecordingPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sheetDotAlpha"
    )
    val statusTitle = when {
        isTracking -> "실시간 기록 중"
        isPaused -> "기록 일시정지"
        else -> "이동 기록"
    }
    val statusMessage = when {
        isTracking -> "앱을 닫아도 계속 기록합니다"
        isPaused -> "현재 위치 수집을 잠시 멈췄습니다"
        else -> "준비되면 시작 버튼을 눌러주세요"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        // 접힌 상태에서도 기록 상태와 주 동작이 한눈에 보이는 고정 헤더다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = when {
                            isTracking -> RecordingCoral.copy(alpha = dotAlpha)
                            isPaused -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.primary
                        },
                        shape = CircleShape
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isTracking) RecordingCoral else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onPrimaryAction,
                shape = RoundedCornerShape(8.dp),
                colors = if (isTracking) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = if (isTracking) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        isTracking -> "일시정지"
                        isPaused -> "계속"
                        else -> "시작"
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            TrackingMetric(
                label = "거리",
                value = formatDistance(distanceMeters),
                modifier = Modifier.weight(1f)
            )
            TrackingMetric(
                label = "시간",
                value = formatDuration(durationMillis),
                modifier = Modifier.weight(1f)
            )
            TrackingMetric(
                label = "평균 속도",
                value = formatSpeed(averageSpeedMetersPerSecond),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = latestAccuracyMeters?.let { "GPS 정확도 ±${it.toInt()}m" }
                ?: "GPS 위치를 기다리는 중",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        if (hasCurrentRecord) {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RecordingCoral)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("종료하고 저장")
            }

            if (!isTracking) {
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("저장하지 않고 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            OutlinedButton(
                onClick = onPrimaryAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("새 이동 기록 시작")
            }
        }
    }
}

@Composable
private fun TrackingMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatDistance(distanceMeters: Float): String = Formats.distance(distanceMeters)

private fun formatDuration(durationMillis: Long): String = Formats.duration(durationMillis)

private fun formatSpeed(speedMetersPerSecond: Float): String = Formats.speed(speedMetersPerSecond)

/** 좌상단 고정 상태 배지: 기록 중(코랄 점 펄스) / 일시정지(회색 점) / 없으면 미표시. */
@Composable
private fun RecordingStatusBadge(
    isTracking: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isTracking && !isPaused) return

    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isTracking) RecordingCoral.copy(alpha = dotAlpha)
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
            )
            Text(
                text = if (isTracking) "실시간 기록 중" else "일시정지됨",
                style = MaterialTheme.typography.labelLarge,
                color = if (isTracking) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
            launcher.launch(intent)
            return
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
        // Android 10(API 29)+에서만 백그라운드 위치가 별도 권한으로 분리된다.
        // 이 함수는 보통 API 29+에서 호출되지만, 릴리즈 lint와 구형 기기 안전성을 위해 가드한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            putExtra(EXTRA_PERMISSION_NAME, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
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
