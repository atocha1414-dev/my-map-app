package com.connor.mymap.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.connor.mymap.ui.map.MapScreen
import com.connor.mymap.ui.profile.ProfileScreen

/**
 * 홈(지도)과 프로필(이동 기록 목록)을 하단 네비로 묶는 메인 셸.
 * MapScreen은 AndroidView(MapLibre)를 포함하므로 탭 전환 시 컴포지션에서 제거되면
 * 카메라 위치·레이어·포인터가 모두 초기화된다.
 * → Box 안에 항상 유지하고 alpha로만 표시/숨김 처리한다.
 */
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    // 지도 단일 탭으로 토글되는 몰입 모드 — 탭바·트래킹 패널·FAB 모두 숨김
    var isImmersive by rememberSaveable { mutableStateOf(false) }

    // 프로필 탭으로 이동하면 몰입 모드를 해제한다(프로필에서 탭바가 보여야 다시 홈으로 갈 수 있다).
    LaunchedEffect(selectedTab) {
        if (selectedTab != MainTab.Home) isImmersive = false
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // 상단 padding은 적용하지 않는다 — 지도(MapScreen)가 상태바·카메라 노치까지 가득 채워야 한다.
        // 하단 padding만 적용해 탭바와 시스템 네비게이션 바 위까지만 콘텐츠가 올라오게 한다.
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(bottom = padding.calculateBottomPadding())
        ) {
            // MapScreen은 항상 컴포지션에 유지 — AndroidView 인스턴스(지도 상태)를 보존한다.
            MapScreen(
                isImmersive = isImmersive,
                onMapTap = { isImmersive = !isImmersive },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (selectedTab == MainTab.Home) 1f else 0f }
            )
            // ProfileScreen은 상태가 ViewModel/스토리지에 있으므로 재생성해도 무방하다.
            if (selectedTab == MainTab.Profile) {
                ProfileScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

enum class MainTab(val label: String, val icon: ImageVector) {
    Home(label = "홈", icon = Icons.Default.Map),
    Profile(label = "목록", icon = Icons.AutoMirrored.Filled.List)
}
