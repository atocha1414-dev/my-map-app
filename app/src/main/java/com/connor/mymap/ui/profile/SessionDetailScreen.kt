package com.connor.mymap.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.ui.map.MapLibreView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: PlaybackViewModel = viewModel(
        key = sessionId,
        factory = PlaybackViewModel.factory(sessionId)
    )

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val visiblePoints by viewModel.visiblePoints.collectAsStateWithLifecycle()
    val allPoints by viewModel.allPoints.collectAsStateWithLifecycle()
    val totalMs by viewModel.totalMs.collectAsStateWithLifecycle()
    val speedMultiplier by viewModel.speedMultiplier.collectAsStateWithLifecycle()
    val routeBounds by viewModel.routeBounds.collectAsStateWithLifecycle()
    // ViewModel 재생 루프가 50ms마다 직접 갱신하는 표시 전용 값
    val displayElapsedMs by viewModel.displayElapsedMs.collectAsStateWithLifecycle()
    val displayProgress by viewModel.displayProgress.collectAsStateWithLifecycle()

    val startTimestampMs by viewModel.startTimestampMs.collectAsStateWithLifecycle()

    val mapFilePath = viewModel.mapFilePath

    var isDragging by remember { mutableStateOf(false) }
    // 드래그 중 슬라이더 위치를 즉각 반영하기 위한 로컬 값
    var localDragProgress by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }

    // 화면을 벗어날 때 재생을 멈춘다.
    // ViewModel은 Activity가 살아있는 동안 유지되므로 onDispose 없이 두면
    // 목록으로 돌아온 뒤에도 코루틴이 계속 index를 증가시킨다.
    DisposableEffect(Unit) {
        onDispose { viewModel.pause() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("경로 재생") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 지도 영역 — 스타일 로딩과 포인트 로딩이 모두 비동기므로 항상 렌더링하고
            // isLoading 동안만 스피너를 오버레이한다.
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
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlaybackPathCanvas(
                        visiblePoints = visiblePoints,
                        allPoints = allPoints,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // 컨트롤 패널
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatActualTime(startTimestampMs + displayElapsedMs),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatActualTime(startTimestampMs + totalMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Slider(
                    // 드래그 중: 즉각 반영되는 로컬값 / 재생·정지 중: ViewModel이 50ms마다 갱신하는 값
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
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 처음으로
                    IconButton(
                        onClick = viewModel::reset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = "처음으로")
                    }

                    // 재생 / 일시정지
                    FilledIconButton(
                        onClick = { if (isPlaying) viewModel.pause() else viewModel.play() },
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

                    // 재생 속도 (탭할 때마다 순환)
                    TextButton(
                        onClick = viewModel::cycleSpeed,
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

private fun formatActualTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

private fun formatSpeed(multiplier: Float): String = when (multiplier) {
    1f -> "1x"
    2f -> "2x"
    5f -> "5x"
    10f -> "10x"
    else -> "${multiplier.toInt()}x"
}
