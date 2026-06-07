package com.connor.mymap.ui.footprints

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
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
import com.connor.mymap.ui.common.LoadingIndicator
import com.connor.mymap.ui.theme.BrandGradient

/**
 * "나의 발자취" — 저장된 모든 이동 경로를 한 지도에 밀도 히트맵으로 겹쳐 보여준다.
 */
@Composable
fun FootprintsScreen(
    modifier: Modifier = Modifier,
    viewModel: FootprintsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapFilePath = viewModel.mapFilePath

    // 탭에 들어올 때마다 최신 기록을 다시 모은다.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            mapFilePath == null -> ErrorView(
                title = "지도 파일 없음",
                message = "지도 파일을 찾을 수 없습니다.\n앱을 다시 실행해주세요.",
                onRetry = { }
            )

            state.isLoading && state.routes.isEmpty() ->
                LoadingIndicator(message = "발자취 모으는 중...")

            state.routes.isEmpty() -> EmptyFootprints()

            else -> FootprintsMapView(
                mapFilePath = mapFilePath,
                routes = state.routes,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (mapFilePath != null && state.routes.isNotEmpty()) {
            FootprintsHeader(
                sessionCount = state.sessionCount,
                totalKm = state.totalDistanceMeters / 1000f,
                pointCount = state.totalPoints,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun FootprintsHeader(
    sessionCount: Int,
    totalKm: Float,
    pointCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(BrandGradient)
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            Text(
                text = "나의 발자취",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "기록 ${sessionCount}개 · 합계 ${"%.1f".format(totalKm)} km · 포인트 ${pointCount}개",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun EmptyFootprints(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Timeline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "아직 발자취가 없어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "이동을 기록하면 모든 경로가\n이 지도 위에 한눈에 쌓입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
