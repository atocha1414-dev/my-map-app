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
import androidx.compose.material.icons.filled.Timeline
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
import com.connor.mymap.ui.footprints.FootprintsScreen
import com.connor.mymap.ui.map.MapScreen
import com.connor.mymap.ui.profile.ProfileScreen

/**
 * 홈(지도)과 프로필(이동 기록 목록)을 하단 네비로 묶는 메인 셸.
 * MapScreen은 AndroidView(MapLibre)를 포함하므로 탭 전환 시 컴포지션에서 제거되면
 * 카메라 위치·레이어·포인터가 모두 초기화된다.
 * → 기본적으로는 Box 안에 유지하고 alpha로만 표시/숨김 처리한다.
 * 단, 프로필 상세 재생 화면이 열려 두 번째 MapLibreView가 생기는 순간에는
 * 홈 지도를 잠시 언마운트해 네이티브 메모리 급증을 막는다.
 */
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    // 지도 단일 탭으로 토글되는 몰입 모드 — 탭바·트래킹 패널·FAB 모두 숨김
    var isImmersive by rememberSaveable { mutableStateOf(false) }
    var isProfileDetailVisible by rememberSaveable { mutableStateOf(false) }
    var isProfileDetailImmersive by rememberSaveable { mutableStateOf(false) }

    // 프로필 탭으로 이동하면 몰입 모드를 해제한다(프로필에서 탭바가 보여야 다시 홈으로 갈 수 있다).
    LaunchedEffect(selectedTab) {
        if (selectedTab != MainTab.Home) isImmersive = false
        if (selectedTab != MainTab.Profile) {
            isProfileDetailVisible = false
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
            // 변경 이유: 발자취 탭은 자체 MapLibreView(히트맵)를 띄우므로,
            // 그 순간 홈 지도까지 유지하면 네이티브 지도 인스턴스가 2개가 된다.
            // 발자취 탭과 프로필 상세에서는 홈 지도를 잠시 언마운트해 동시 2개를 피한다.
            val keepHomeMapComposed = selectedTab == MainTab.Home ||
                (selectedTab == MainTab.Profile && !isProfileDetailVisible)

            // 변경 이유: 프로필 상세 재생 화면(SessionDetailScreen)도 MapLibreView를 사용하므로,
            // 그 순간 홈 MapLibreView까지 유지하면 네이티브 메모리가 2배 가까이 튈 수 있다.
            // 상세 노출 중에는 홈 지도를 잠시 언마운트해 동시 2개 인스턴스를 피한다.
            if (keepHomeMapComposed) {
                MapScreen(
                    isImmersive = isImmersive,
                    onMapTap = { isImmersive = !isImmersive },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (selectedTab == MainTab.Home) 1f else 0f }
                )
            }
            // 발자취(히트맵) 탭. 상태는 ViewModel에 있으므로 탭 전환 시 재생성해도 무방하다.
            if (selectedTab == MainTab.Footprints) {
                FootprintsScreen(modifier = Modifier.fillMaxSize())
            }
            // ProfileScreen은 상태가 ViewModel/스토리지에 있으므로 재생성해도 무방하다.
            if (selectedTab == MainTab.Profile) {
                ProfileScreen(
                    modifier = Modifier.fillMaxSize(),
                    onSessionDetailVisibleChange = { isVisible ->
                        isProfileDetailVisible = isVisible
                    },
                    onSessionDetailImmersiveChange = { isImmersiveInDetail ->
                        isProfileDetailImmersive = isImmersiveInDetail
                    },
                )
            }
        }
    }
}

enum class MainTab(val label: String, val icon: ImageVector) {
    Home(label = "홈", icon = Icons.Default.Map),
    Footprints(label = "발자취", icon = Icons.Default.Timeline),
    Profile(label = "목록", icon = Icons.AutoMirrored.Filled.List)
}
