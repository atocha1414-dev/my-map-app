package com.connor.mymap.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.connor.mymap.data.local.TermsPreferences
import com.connor.mymap.ui.common.LoadingIndicator
import com.connor.mymap.ui.download.DownloadScreen
import com.connor.mymap.ui.main.MainScreen
import com.connor.mymap.ui.terms.TermsDetailScreen
import com.connor.mymap.ui.terms.TermsScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.TermsGate.route
    ) {
        // 출시 대비 변경: 최초 실행 시 약관/개인정보/위치기반서비스 동의 여부를 먼저 확인한다.
        // 이미 필수 약관에 동의한 사용자는 기존처럼 다운로드 화면으로 바로 보낸다.
        composable(Screen.TermsGate.route) {
            TermsGateScreen(
                onAccepted = {
                    navController.navigate(Screen.Download.route) {
                        popUpTo(Screen.TermsGate.route) { inclusive = true }
                    }
                },
                onNotAccepted = {
                    navController.navigate(Screen.Terms.route) {
                        popUpTo(Screen.TermsGate.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Terms.route) {
            TermsScreen(
                onTermsAccepted = {
                    navController.navigate(Screen.Download.route) {
                        popUpTo(Screen.Terms.route) { inclusive = true }
                    }
                },
                onShowTermsDetail = { typeKey ->
                    navController.navigate(Screen.TermsDetail.createRoute(typeKey))
                }
            )
        }

        composable(
            route = Screen.TermsDetail.route,
            arguments = listOf(navArgument(Screen.TermsDetail.ARG_TYPE) { type = NavType.StringType })
        ) { backStackEntry ->
            val typeKey = backStackEntry.arguments?.getString(Screen.TermsDetail.ARG_TYPE)
            TermsDetailScreen(
                typeKey = typeKey,
                onBack = { navController.popBackStack() }
            )
        }

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

        // 홈(지도) + 프로필을 묶은 메인 셸
        composable(Screen.Map.route) {
            MainScreen()
        }
    }
}

@Composable
private fun TermsGateScreen(
    onAccepted: () -> Unit,
    onNotAccepted: () -> Unit
) {
    val context = LocalContext.current
    val termsPreferences = remember(context) { TermsPreferences(context) }
    val hasAcceptedRequiredTerms by produceState<Boolean?>(initialValue = null) {
        termsPreferences.hasAcceptedRequiredTerms.collect { accepted ->
            value = accepted
        }
    }

    LaunchedEffect(hasAcceptedRequiredTerms) {
        when (hasAcceptedRequiredTerms) {
            true -> onAccepted()
            false -> onNotAccepted()
            null -> Unit
        }
    }

    LoadingIndicator(message = "서비스 동의 확인 중...")
}
