package com.connor.mymap.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.ui.map.MapLibreView

@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onImmersiveChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 변경 이유: 기본 viewModel()은 Activity의 ViewModelStore를 공유하므로
    // 사용자가 여러 세션을 차례로 보면 PlaybackViewModel이 store에 누적되어 메모리 누수.
    // 화면별 전용 ViewModelStore를 만들어 dispose 시 onCleared가 호출되게 한다.
    val application = LocalContext.current.applicationContext as Application
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }

    // 커스텀 owner는 HasDefaultViewModelProviderFactory가 아니라서
    // factory가 사용하는 APPLICATION_KEY를 직접 extras에 넣어줘야 한다.
    val extras = remember(application) {
        MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
        }
    }

    val viewModel: PlaybackViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = PlaybackViewModel.factory(sessionId),
        extras = extras
    )

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val canPlay by viewModel.canPlay.collectAsStateWithLifecycle()
    val visiblePoints by viewModel.visiblePoints.collectAsStateWithLifecycle()
    val allPoints by viewModel.allPoints.collectAsStateWithLifecycle()
    val totalMs by viewModel.totalMs.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()
    val routeBounds by viewModel.routeBounds.collectAsStateWithLifecycle()
    val displayElapsedMs by viewModel.displayElapsedMs.collectAsStateWithLifecycle()
    val displayProgress by viewModel.displayProgress.collectAsStateWithLifecycle()

    val mapFilePath = viewModel.mapFilePath

    var isDragging by remember { mutableStateOf(false) }
    var localDragProgress by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    var isImmersive by rememberSaveable { mutableStateOf(false) }

    // 몰입 모드 중 뒤로가기 → 몰입 해제, 일반 상태 → 화면 종료
    BackHandler(enabled = isImmersive) { isImmersive = false }

    // 변경 이유: 상세 재생 화면 몰입 모드일 때는 하단 탭바도 함께 숨겨야
    // 홈 화면 몰입 모드와 동일한 UX를 제공할 수 있다.
    LaunchedEffect(isImmersive) {
        onImmersiveChange(isImmersive)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.pause()
            onImmersiveChange(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 변경 이유: 컨트롤 페이드/슬라이드 애니메이션 중 투명 구간에서
            // 아래의 이동기록 화면이 비쳐 보이는 현상을 막기 위해 상세 화면 루트를 불투명 배경으로 고정.
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 지도 영역 — 상단 바·컨트롤이 사라지면 weight(1f)로 전체 화면을 채움
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (mapFilePath != null) {
                MapLibreView(
                    mapFilePath = mapFilePath,
                    myLocation = null,
                    trackPoints = visiblePoints,
                    fitBounds = routeBounds,
                    onMapClick = { isImmersive = !isImmersive },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isImmersive = !isImmersive }
                ) {
                    PlaybackPathCanvas(
                        visiblePoints = visiblePoints,
                        allPoints = allPoints,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // 상단 바 — 지도 위 오버레이 (위로 슬라이드 아웃)
            // 변경 이유: 상단 그라디언트를 Column 상단에 두면 뒤의 "이동 기록" 화면이 비쳐 겹쳐 보일 수 있다.
            // 지도 Box 안에 올리면 투명 구간 뒤에는 지도만 보이므로 겹침 현상이 사라진다.
            androidx.compose.animation.AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                val headerBaseColor = MaterialTheme.colorScheme.surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0.0f to headerBaseColor.copy(alpha = 0.97f),
                                0.75f to headerBaseColor.copy(alpha = 0.85f),
                                1.0f to headerBaseColor.copy(alpha = 0f)
                            )
                        )
                        .statusBarsPadding()
                        .padding(end = 16.dp, bottom = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                        Text(
                            text = "경로 재생",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }

        // 하단 컨트롤 패널 — 아래로 슬라이드 아웃
        AnimatedVisibility(
            visible = !isImmersive,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(displayElapsedMs),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDuration(totalMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!isLoading && !canPlay) {
                    Text(
                        text = "포인트가 1개뿐이어서 재생할 수 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Slider(
                    value = if (isDragging) localDragProgress else displayProgress,
                    onValueChange = { fraction ->
                        if (!isDragging) {
                            isDragging = true
                            wasPlayingBeforeDrag = isPlaying
                            viewModel.pause()
                        }
                        localDragProgress = fraction
                        viewModel.seekTo(fraction)
                    },
                    onValueChangeFinished = {
                        if (isDragging) {
                            isDragging = false
                            if (wasPlayingBeforeDrag) viewModel.play()
                        }
                    },
                    enabled = canPlay,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = viewModel::reset,
                        enabled = canPlay,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = "처음으로")
                    }

                    FilledIconButton(
                        onClick = { if (isPlaying) viewModel.pause() else viewModel.play() },
                        enabled = canPlay,
                        modifier = Modifier
                            .weight(1f)
                            .size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "일시정지" else "재생",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    TextButton(
                        onClick = viewModel::cycleSpeed,
                        enabled = canPlay,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = formatSpeed(speedMultiplier),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 지도 파일이 없을 때 재생 경로를 Canvas로만 표시하는 폴백.
 * allPoints 기준으로 좌표 범위를 고정해 재생 중 경로가 튀지 않는다.
 */
@Composable
private fun PlaybackPathCanvas(
    visiblePoints: List<TrackingPoint>,
    allPoints: List<TrackingPoint>,
    modifier: Modifier = Modifier
) {
    val pathColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = modifier.background(bgColor)) {
        if (allPoints.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 24.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad

                val minLat = allPoints.minOf { it.latitude }
                val maxLat = allPoints.maxOf { it.latitude }
                val minLon = allPoints.minOf { it.longitude }
                val maxLon = allPoints.maxOf { it.longitude }
                val latRange = (maxLat - minLat).let { if (it == 0.0) 1.0 else it }
                val lonRange = (maxLon - minLon).let { if (it == 0.0) 1.0 else it }

                fun toX(lon: Double) = pad + ((lon - minLon) / lonRange * w).toFloat()
                fun toY(lat: Double) = pad + ((maxLat - lat) / latRange * h).toFloat()

                visiblePoints.groupBy { it.segmentIndex }.values.forEach { segment ->
                    if (segment.size < 2) return@forEach
                    val path = Path()
                    segment.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(toX(p.longitude), toY(p.latitude))
                        else path.lineTo(toX(p.longitude), toY(p.latitude))
                    }
                    drawPath(
                        path = path,
                        color = pathColor,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                visiblePoints.lastOrNull()?.let { p ->
                    drawCircle(
                        color = pathColor,
                        radius = 6.dp.toPx(),
                        center = Offset(toX(p.longitude), toY(p.latitude))
                    )
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val s = millis / 1_000L
    val h = s / 3_600L
    val m = (s % 3_600L) / 60L
    val sec = s % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private fun formatSpeed(multiplier: Float): String = when (multiplier) {
    1f -> "1x"
    2f -> "2x"
    5f -> "5x"
    10f -> "10x"
    else -> "${multiplier.toInt()}x"
}
