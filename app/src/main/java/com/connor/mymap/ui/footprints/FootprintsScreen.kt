package com.connor.mymap.ui.footprints

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.ui.common.ErrorView
import com.connor.mymap.ui.theme.BrandGradient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "발자취" 섹션. 만든 발자취들의 목록을 보여주고,
 * 하나를 열면 그 발자취에 묶인 경로들을 한 지도에 겹쳐(글로우) 표시한다.
 */
@Composable
fun FootprintsScreen(
    modifier: Modifier = Modifier,
    viewModel: FootprintsViewModel = viewModel()
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val mapFilePath = viewModel.mapFilePath

    LaunchedEffect(Unit) { viewModel.refreshList() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (selectedId != null) {
            BackHandler { viewModel.closeDetail() }
            FootprintDetail(
                mapFilePath = mapFilePath,
                state = detailState,
                onBack = { viewModel.closeDetail() }
            )
        } else {
            FootprintList(
                state = listState,
                onOpen = { viewModel.openDetail(it) },
                onDelete = { viewModel.deleteFootprint(it) }
            )
        }
    }
}

@Composable
private fun FootprintList(
    state: FootprintsViewModel.ListState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    when {
        state.isLoading && state.items.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        state.items.isEmpty() -> EmptyFootprints()

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.items, key = { it.id }) { item ->
                FootprintRow(
                    item = item,
                    onOpen = { onOpen(item.id) },
                    onDelete = { onDelete(item.id) }
                )
            }
        }
    }
}

@Composable
private fun FootprintRow(
    item: FootprintsViewModel.FootprintListItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BrandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Timeline, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${item.sessionCount}개 기록 · ${"%.1f".format(item.totalDistanceMeters / 1000f)} km",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatFootprintDate(item.createdAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "발자취 삭제")
            }
        }
    }
}

@Composable
private fun FootprintDetail(
    mapFilePath: String?,
    state: FootprintsViewModel.DetailState,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        when {
            mapFilePath == null -> ErrorView(
                title = "지도 파일 없음",
                message = "지도 파일을 찾을 수 없습니다.",
                onRetry = { }
            )

            state.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            state.routes.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "이 발자취에는 표시할 경로가 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            else -> FootprintsMapView(
                mapFilePath = mapFilePath,
                routes = state.routes,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 상단: 뒤로가기 + 발자취 이름/요약
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                shadowElevation = 6.dp
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(BrandGradient)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = state.name.ifEmpty { "발자취" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "${state.sessionCount}개 기록 · ${"%.1f".format(state.totalDistanceMeters / 1000f)} km",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFootprints() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Timeline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "아직 만든 발자취가 없어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "‘기록’ 탭에서 편집 → 항목 선택 후\n‘발자취 만들기’로 모아보세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatFootprintDate(millis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))
