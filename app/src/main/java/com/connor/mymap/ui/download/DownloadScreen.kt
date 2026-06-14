package com.connor.mymap.ui.download

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.connor.mymap.R
import com.connor.mymap.domain.model.DownloadState
import com.connor.mymap.ui.common.ErrorView
import com.connor.mymap.ui.common.LoadingIndicator

@Composable
fun DownloadScreen(
    onMapReady: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is DownloadState.Checking -> {
            LoadingIndicator(message = "지도 확인 중...")
        }
        is DownloadState.NeedsDownload -> {
            InitialDownloadView(onStart = viewModel::startDownload)
        }
        is DownloadState.InProgress -> {
            DownloadingView(state = current)
        }
        is DownloadState.Ready -> {
            LaunchedEffect(Unit) { onMapReady() }
        }
        is DownloadState.Error -> {
            ErrorView(
                title = "다운로드 실패",
                message = current.message,
                onRetry = viewModel::retryDownload
            )
        }
    }
}

/**
 * 지도 준비/다운로드 화면 공통 배경.
 * 변경 이유: 단색 배경이 밋밋해, 앱의 오프라인 지도(서울 중심부)를 배경으로 깔고
 * 브랜드 그라디언트 스크림을 덮어 텍스트 가독성을 확보한다.
 */
@Composable
private fun MapBackground(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_map_seoul),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xDB0A2540),
                        0.45f to Color(0x990E6E8C),
                        1f to Color(0xE6081826)
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun InitialDownloadView(onStart: () -> Unit) {
    MapBackground {
        Text(
            text = "🗺️",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "지도 준비하기",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "처음 실행 시 지도 파일을 다운로드합니다",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.88f)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "약 160MB • Wi-Fi 환경 권장",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f)
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "다운로드 시작",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DownloadingView(state: DownloadState.InProgress) {
    MapBackground {
        Text(
            text = "지도 다운로드 중",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )

        Spacer(Modifier.height(32.dp))

        LinearProgressIndicator(
            progress = { state.percentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "${state.percentageInt}%",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "%.1f / %.1f MB".format(state.downloadedMb, state.totalMb),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "앱을 닫지 말고 기다려주세요",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}
