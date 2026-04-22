package com.example.mymap.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mymap.ui.download.DownloadScreen
import com.example.mymap.ui.map.MapScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Download.route
    ) {
        // 다운로드 화면
        composable(Screen.Download.route) {
            DownloadScreen(
                onMapReady = {
                    // 지도 준비되면 Map 화면으로 이동
                    navController.navigate(Screen.Map.route) {
                        // Download 화면을 백스택에서 제거 (뒤로가기 방지)
                        popUpTo(Screen.Download.route) { inclusive = true }
                    }
                }
            )
        }

        // 지도 화면
        composable(Screen.Map.route) {
            MapScreen()
        }
    }
}