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
 * 홈(지도)과 목록(이동 기록 + 발자취)을 하단 네비로 묶는 메인 셸.
 * MapScreen은 AndroidView(MapLibre)를 포함하므로 탭 전환 시 컴포지션에서 제거되면
 * 카메라 위치·레이어·포인터가 모두 초기화된다.
 * → 기본적으로는 Box 안에 유지하고 alpha로만 표시/숨김 처리한다.
 * 단, 목록 탭에서 세션 상세(재생) 또는 발자취가 두 번째 MapLibreView를 띄우는 순간에는
 * 홈 지도를 잠시 언마운트해 네이티브 메모리 급증을 막는다.
 */
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    // 지도 단일 탭으로 토글되는 몰입 모드 — 탭바·트래킹 패널·FAB 모두 숨김
    var isImmersive by rememberSaveable { mutableStateOf(false) }
    // 목록 탭에서 두 번째 MapLibreView(세션 상세 재생 또는 발자취)가 떠 있는지.
    var isProfileSecondaryMapVisible by rememberSaveable { mutableStateOf(false) }
    var isProfileDetailImmersive by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab != MainTab.Home) isImmersive = false
        if (selectedTab != MainTab.Profile) {
            isProfileSecondaryMapVisible = false
            isProfileDetailImmersive = false
        }
    }

    val shouldHideBottomBar = isImmersive || (selectedTab == MainTab.Profile && isProfileDetailImmersive)

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !shouldHideBottomBar,
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
            // 세션 상세 재생 또는 발자취가 자체 MapLibreView를 띄우는 동안에는
            // 홈 지도를 잠시 언마운트해 동시 2개 인스턴스를 피한다.
            val keepHomeMapComposed = selectedTab == MainTab.Home ||
                (selectedTab == MainTab.Profile && !isProfileSecondaryMapVisible)

            if (keepHomeMapComposed) {
                MapScreen(
                    isImmersive = isImmersive,
                    onMapTap = { isImmersive = !isImmersive },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (selectedTab == MainTab.Home) 1f else 0f }
                )
            }
            // ProfileScreen(목록)은 상태가 ViewModel/스토리지에 있으므로 재생성해도 무방하다.
            if (selectedTab == MainTab.Profile) {
                ProfileScreen(
                    modifier = Modifier.fillMaxSize(),
                    onSecondaryMapVisibleChange = { isProfileSecondaryMapVisible = it },
                    onSessionDetailImmersiveChange = { isProfileDetailImmersive = it },
                )
            }
        }
    }
}

enum class MainTab(val label: String, val icon: ImageVector) {
    Home(label = "홈", icon = Icons.Default.Map),
    Profile(label = "목록", icon = Icons.AutoMirrored.Filled.List)
}
