package com.connor.mymap.ui.navigation

/**
 * 앱의 모든 화면 라우트 정의
 */
sealed class Screen(val route: String) {
    data object TermsGate : Screen("terms_gate")
    data object Terms : Screen("terms")
    data object Download : Screen("download")
    data object Map : Screen("map")

    data object TermsDetail : Screen("terms_detail/{type}") {
        const val ARG_TYPE = "type"
        fun createRoute(typeKey: String): String = "terms_detail/$typeKey"
    }
}
