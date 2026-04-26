package com.connor.mymap.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    Scaffold(
        bottomBar = {
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
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // MapScreen은 항상 컴포지션에 유지 — AndroidView 인스턴스(지도 상태)를 보존한다.
            MapScreen(
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
    Profile(label = "프로필", icon = Icons.Default.Person)
}
