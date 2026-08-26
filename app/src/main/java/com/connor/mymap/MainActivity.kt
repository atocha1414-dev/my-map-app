package com.connor.mymap

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.connor.mymap.ui.navigation.AppNavigation
import com.connor.mymap.ui.theme.MyMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15부터 edge-to-edge가 강제된다. deprecated 시스템 바 색상 API를 호출하지 않고
        // 모든 버전에서 같은 레이아웃 계약을 사용하며, 각 화면은 WindowInsets로 컨트롤을 보호한다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDarkMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
        setContent {
            MyMapTheme {
                AppNavigation()
            }
        }
    }
}
