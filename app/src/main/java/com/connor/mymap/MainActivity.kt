package com.connor.mymap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.connor.mymap.ui.navigation.AppNavigation
import com.connor.mymap.ui.theme.MyMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 지도가 상태바·네비게이션바 뒤까지 가득 채우도록 edge-to-edge를 활성화.
        // 각 화면이 WindowInsets(statusBarsPadding 등)으로 컨트롤 위치를 직접 조정한다.
        enableEdgeToEdge()
        setContent {
            MyMapTheme {
                AppNavigation()
            }
        }
    }
}