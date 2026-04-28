package com.connor.mymap.ui.profile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.TrackingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val selectedSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Box(modifier = modifier.fillMaxSize()) {
        SessionListContent(
            sessions = sessions,
            isLoading = isLoading,
            onCardClick = { viewModel.selectSession(it) },
            onDeleteSession = { viewModel.deleteSession(it) },
            onDeleteSessions = { viewModel.deleteSessions(it) },
            loadThumbnailFile = { viewModel.getThumbnailFile(it) },
            loadPoints = { viewModel.loadPoints(it) }
        )

        if (selectedSessionId != null) {
            BackHandler { viewModel.clearSelection() }
            SessionDetailScreen(
                sessionId = selectedSessionId!!,
                onBack = { viewModel.clearSelection() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessions: List<TrackingSession>,
    isLoading: Boolean,
    onCardClick: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    loadThumbnailFile: suspend (String) -> File?,
    loadPoints: suspend (String) -> List<TrackingPoint>
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.List) }
    val bgColor = MaterialTheme.colorScheme.background

    BackHandler(enabled = isEditMode) {
        isEditMode = false
        selectedIds = emptySet()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        when {
            isLoading && sessions.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            sessions.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "아직 저장된 이동 기록이 없습니다.\n홈에서 ▶ 시작 후 ↻ 초기화를 누르면\n여기에 기록이 저장됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val groupedSessions = remember(sessions) {
                    sessions
                        .sortedByDescending { it.startedAtMillis }
                        .groupBy { formatSectionDate(it.startedAtMillis) }
                        .entries.toList()
                }

                if (viewMode == ViewMode.List) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 72.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (isEditMode) 80.dp else 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        groupedSessions.forEach { (dateLabel, daySessions) ->
                            stickyHeader(key = "header_$dateLabel") {
                                DateSectionHeader(label = dateLabel, bgColor = bgColor)
                            }
                            items(daySessions, key = { it.id }) { session ->
                                SessionCard(
                                    session = session,
                                    onCardClick = { onCardClick(session.id) },
                                    onDeleteClick = { pendingDeleteId = session.id },
                                    loadThumbnailFile = { loadThumbnailFile(session.id) },
                                    loadPoints = { loadPoints(session.id) },
                                    isEditMode = isEditMode,
                                    isSelected = session.id in selectedIds,
                                    onToggleSelect = {
                                        selectedIds = if (session.id in selectedIds)
                                            selectedIds - session.id
                                        else
                                            selectedIds + session.id
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 72.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (isEditMode) 80.dp else 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedSessions.forEach { (dateLabel, daySessions) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DateSectionHeader(label = dateLabel, bgColor = bgColor)
                            }
                            gridItems(daySessions, key = { it.id }) { session ->
                                ThumbnailGridItem(
                                    session = session,
                                    onCardClick = { onCardClick(session.id) },
                                    loadThumbnailFile = { loadThumbnailFile(session.id) },
                                    loadPoints = { loadPoints(session.id) },
                                    isEditMode = isEditMode,
                                    isSelected = session.id in selectedIds,
                                    onToggleSelect = {
                                        selectedIds = if (session.id in selectedIds)
                                            selectedIds - session.id
                                        else
                                            selectedIds + session.id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 반투명 그라디언트 헤더 오버레이
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0.0f to bgColor.copy(alpha = 0.97f),
                        0.75f to bgColor.copy(alpha = 0.85f),
                        1.0f to bgColor.copy(alpha = 0f)
                    )
                )
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditMode) {
                    val allSelected = sessions.isNotEmpty() && selectedIds.size == sessions.size
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = {
                            selectedIds = if (allSelected) emptySet()
                            else sessions.map { it.id }.toSet()
                        }
                    )
                }
                Text(
                    text = when {
                        !isEditMode -> "이동 기록"
                        selectedIds.isEmpty() -> "항목 선택"
                        else -> "${selectedIds.size}개 선택됨"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (sessions.isNotEmpty() && !isEditMode) {
                    IconButton(onClick = {
                        viewMode = if (viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List
                    }) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.List) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = if (viewMode == ViewMode.List) "그리드 보기" else "목록 보기"
                        )
                    }
                }
                if (sessions.isNotEmpty()) {
                    TextButton(onClick = {
                        if (isEditMode) {
                            isEditMode = false
                            selectedIds = emptySet()
                        } else {
                            isEditMode = true
                        }
                    }) {
                        Text(if (isEditMode) "완료" else "편집")
                    }
                }
            }
        }

        // 편집 모드 하단 삭제 바
        AnimatedVisibility(
            visible = isEditMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedIds.isEmpty()) "항목을 선택해주세요"
                               else "${selectedIds.size}개 선택됨",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedIds.isEmpty())
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = { showBulkDeleteConfirm = true },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("삭제")
                    }
                }
            }
        }
    }

    // 단건 삭제 확인
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("기록을 삭제할까요?") },
            text = { Text("이 이동 기록이 영구적으로 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(id)
                    pendingDeleteId = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("취소") }
            }
        )
    }

    // 일괄 삭제 확인
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("기록을 삭제할까요?") },
            text = { Text("선택한 ${selectedIds.size}개의 이동 기록이 영구적으로 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSessions(selectedIds)
                    showBulkDeleteConfirm = false
                    isEditMode = false
                    selectedIds = emptySet()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun DateSectionHeader(label: String, bgColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(top = 8.dp, bottom = 6.dp)
    )
}

@Composable
private fun SessionCard(
    session: TrackingSession,
    onCardClick: () -> Unit,
    onDeleteClick: () -> Unit,
    loadThumbnailFile: suspend () -> File?,
    loadPoints: suspend () -> List<TrackingPoint>,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (isEditMode) onToggleSelect else onCardClick,
        colors = if (isSelected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackingThumbnail(
                loadThumbnailFile = loadThumbnailFile,
                loadPoints = loadPoints,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDate(session.startedAtMillis),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isEditMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect() }
                        )
                    } else {
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "기록 삭제"
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Stat(label = "거리", value = formatDistance(session.distanceMeters))
                    Stat(label = "시간", value = formatDuration(session.durationMillis))
                    Stat(label = "포인트", value = "${session.pointCount}")
                }
            }
        }
    }
}

@Composable
private fun ThumbnailGridItem(
    session: TrackingSession,
    onCardClick: () -> Unit,
    loadThumbnailFile: suspend () -> File?,
    loadPoints: suspend () -> List<TrackingPoint>,
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        onClick = if (isEditMode) onToggleSelect else onCardClick,
        colors = if (isSelected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TrackingThumbnail(
                loadThumbnailFile = loadThumbnailFile,
                loadPoints = loadPoints,
                modifier = Modifier.fillMaxSize()
            )
            // 하단 정보 오버레이
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = formatDate(session.startedAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    Text(
                        text = formatDistance(session.distanceMeters),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // 편집 모드 체크박스 오버레이
            if (isEditMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

/**
 * 세션 썸네일.
 * 1순위: ViewModel이 만들어준 PNG (지도 + 경로)
 * 폴백: PNG가 없거나 디코딩 실패 시 경로 라인만 Canvas로 그린다.
 */
@Composable
private fun TrackingThumbnail(
    loadThumbnailFile: suspend () -> File?,
    loadPoints: suspend () -> List<TrackingPoint>,
    modifier: Modifier = Modifier
) {
    val state by produceState<ThumbnailState>(ThumbnailState.Loading) {
        val file = loadThumbnailFile()
        val bitmap = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
                    .getOrNull()
            }
        } else null

        value = if (bitmap != null) {
            ThumbnailState.MapImage(bitmap)
        } else {
            val points = loadPoints()
            if (points.isNotEmpty()) ThumbnailState.PathFallback(points)
            else ThumbnailState.Empty
        }
    }

    val bgColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .semantics { contentDescription = "이동 경로 미리보기" },
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            ThumbnailState.Loading,
            ThumbnailState.Empty -> Unit
            is ThumbnailState.MapImage -> Image(
                bitmap = s.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            is ThumbnailState.PathFallback -> PathOnlyDrawing(points = s.points)
        }
    }
}

@Composable
private fun PathOnlyDrawing(points: List<TrackingPoint>) {
    val pathColor = MaterialTheme.colorScheme.primary

    if (points.size == 1) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = pathColor, radius = 4.dp.toPx())
        }
        return
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latRange = (maxLat - minLat).let { if (it == 0.0) 1.0 else it }
        val lonRange = (maxLon - minLon).let { if (it == 0.0) 1.0 else it }

        fun toX(lon: Double) = pad + ((lon - minLon) / lonRange * w).toFloat()
        fun toY(lat: Double) = pad + ((maxLat - lat) / latRange * h).toFloat()

        points
            .groupBy { it.segmentIndex }
            .values
            .filter { segment -> segment.isNotEmpty() }
            .forEach { segment ->
                val path = Path()
                segment.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(toX(p.longitude), toY(p.latitude))
                    else path.lineTo(toX(p.longitude), toY(p.latitude))
                }

                drawPath(
                    path = path,
                    color = pathColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
    }
}

private sealed class ThumbnailState {
    data object Loading : ThumbnailState()
    data object Empty : ThumbnailState()
    data class MapImage(val bitmap: ImageBitmap) : ThumbnailState()
    data class PathFallback(val points: List<TrackingPoint>) : ThumbnailState()
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private enum class ViewMode { List, Grid }

private fun formatSectionDate(millis: Long): String {
    fun dayStart(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val today = dayStart(System.currentTimeMillis())
    val sessionDay = dayStart(millis)
    val diffDays = (today - sessionDay) / (24 * 60 * 60 * 1000)

    return when (diffDays) {
        0L -> "오늘"
        1L -> "어제"
        else -> SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(millis))
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))

private fun formatDistance(meters: Float): String =
    if (meters >= 1_000f) "%.2f km".format(meters / 1_000f) else "${meters.toInt()} m"

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000L
    val h = totalSeconds / 3_600L
    val m = (totalSeconds % 3_600L) / 60L
    val s = totalSeconds % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
