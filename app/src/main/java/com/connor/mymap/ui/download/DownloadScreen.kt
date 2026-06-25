package com.connor.mymap.ui.download

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.connor.mymap.domain.model.MapCountry
import com.connor.mymap.domain.model.MapRegion
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
            RegionSelectionView(viewModel)
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
 * 지도 준비/다운로드 화면 공통 배경(서울 지도 + 브랜드 그라디언트 스크림).
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
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/** 최초 실행: 위치로 지역 자동 감지 또는 직접 선택 → 해당 지역 지도 다운로드. */
@Composable
private fun RegionSelectionView(viewModel: DownloadViewModel) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val selected by viewModel.selectedRegion.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val detectFailed by viewModel.detectFailed.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.detectRegion() else viewModel.onLocationDenied()
    }

    when (val cat = catalog) {
        DownloadViewModel.CatalogState.Loading -> MapBackground {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("지도 목록 불러오는 중...", color = Color.White.copy(alpha = 0.85f))
        }
        DownloadViewModel.CatalogState.Error -> MapBackground {
            Text("🗺️", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text("지도 목록을 불러오지 못했어요", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "인터넷 연결을 확인해주세요",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = { viewModel.loadCatalog() }, modifier = Modifier.fillMaxWidth()) {
                Text("다시 시도", modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        is DownloadViewModel.CatalogState.Loaded -> RegionPicker(
            countries = cat.catalog.countries,
            selected = selected,
            detecting = detecting,
            detectFailed = detectFailed,
            onDetect = { permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            onSelect = viewModel::selectRegion,
            onDownload = viewModel::startDownload
        )
    }
}

@Composable
private fun RegionPicker(
    countries: List<MapCountry>,
    selected: MapRegion?,
    detecting: Boolean,
    detectFailed: Boolean,
    onDetect: () -> Unit,
    onSelect: (MapRegion) -> Unit,
    onDownload: () -> Unit
) {
    var chosenCountry by remember { mutableStateOf<MapCountry?>(null) }

    MapBackground {
        Text("🗺️", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("지도 준비하기", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "내 위치로 찾거나 직접 선택하세요",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        // 위치 자동 감지
        Button(
            onClick = onDetect,
            enabled = !detecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (detecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.size(10.dp))
                Text("위치 확인 중...", modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Text("📍 내 위치로 지역 찾기", modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        if (detectFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "위치를 찾지 못했어요. 아래에서 직접 선택하세요.",
                color = Color(0xFFFFD27A),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(16.dp))

        // 국가 선택 칩
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            countries.forEach { country ->
                Chip(
                    label = country.name,
                    selectedChip = chosenCountry?.id == country.id,
                    modifier = Modifier.weight(1f)
                ) {
                    chosenCountry = country
                    // 지역이 하나면 바로 선택(예: 한국)
                    if (country.regions.size == 1) onSelect(country.regions.first())
                }
            }
        }

        // 지역(주) 목록 — 여러 개일 때만
        val regions = chosenCountry?.regions.orEmpty()
        if (regions.size > 1) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.height(220.dp).fillMaxWidth()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regions, key = { it.id }) { region ->
                        RegionRow(
                            region = region,
                            selectedRow = selected?.id == region.id,
                            onClick = { onSelect(region) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 선택 결과 + 다운로드
        Text(
            text = selected?.let { "선택: ${it.name} · 약 ${it.sizeMB}MB" } ?: "지역을 선택하세요",
            color = if (selected != null) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected != null) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDownload,
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("다운로드 시작", modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selectedChip: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = if (selectedChip) Color(0xFF16C9A6) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = if (selectedChip) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RegionRow(
    region: MapRegion,
    selectedRow: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selectedRow) Color(0x3316C9A6) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selectedRow) "✓ ${region.name}" else region.name,
            color = Color.White,
            fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${region.sizeMB}MB",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )
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
