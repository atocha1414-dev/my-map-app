package com.example.mymap.ui.navigation

/**
 * 앱의 모든 화면 라우트 정의
 */
sealed class Screen(val route: String) {
    data object Download : Screen("download")
    data object Map : Screen("map")
}