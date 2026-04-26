package com.connor.mymap.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
            MapReadyView(
                onNavigate = onMapReady,
                onReset = viewModel::resetMap
            )
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

@Composable
private fun InitialDownloadView(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🗺️",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "지도 준비하기",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "처음 실행 시 지도 파일을 다운로드합니다",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "약 400MB • Wi-Fi 환경 권장",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "지도 다운로드 중",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
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
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "%.1f / %.1f MB".format(state.downloadedMb, state.totalMb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "앱을 닫지 말고 기다려주세요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MapReadyView(
    onNavigate: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "✅",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "지도 준비 완료!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "다음 단계에서 지도를 표시할 거예요",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onNavigate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("지도 열기 (아직 준비 중)")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("지도 삭제하고 다시 다운로드")
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "(테스트용 버튼)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}