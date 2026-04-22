package com.example.mymap.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymap.ui.common.ErrorView

@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel()
) {
    val mapFilePath = viewModel.mapFilePath

    if (mapFilePath == null) {
        // 지도 파일이 없는 비정상 상태
        ErrorView(
            title = "지도 파일 없음",
            message = "지도 파일을 찾을 수 없습니다.\n앱을 다시 실행해주세요.",
            onRetry = { /* TODO: Download 화면으로 돌아가기 */ }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapLibreView(
            mapFilePath = mapFilePath,
            modifier = Modifier.fillMaxSize()
        )
    }
}