package com.example.mymap.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymap.ui.common.ErrorView
import com.example.mymap.util.PermissionHelper

@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val mapFilePath = viewModel.mapFilePath

    if (mapFilePath == null) {
        ErrorView(
            title = "지도 파일 없음",
            message = "지도 파일을 찾을 수 없습니다.\n앱을 다시 실행해주세요.",
            onRetry = { }
        )
        return
    }

    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val myLocation by viewModel.myLocation.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 시스템 권한 요청 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onPermissionResult(granted)
    }

    // permissionState가 RequestingPermission이 되면 실제 시스템 다이얼로그 띄움
    LaunchedEffect(permissionState) {
        if (permissionState == LocationPermissionState.RequestingPermission) {
            permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
        }
    }

    // 권한 거부 시 Snackbar 표시
    LaunchedEffect(permissionState) {
        if (permissionState == LocationPermissionState.Denied) {
            snackbarHostState.showSnackbar(
                message = "위치 권한이 필요합니다. 설정에서 허용해주세요.",
                actionLabel = "확인"
            )
            viewModel.resetPermissionState()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // 지도
        MapLibreView(
            mapFilePath = mapFilePath,
            myLocation = myLocation,
            modifier = Modifier.fillMaxSize()
        )

        // 내 위치 버튼 (우하단)
        FloatingActionButton(
            onClick = {
                viewModel.onMyLocationClick(
                    hasPermission = PermissionHelper.hasLocationPermission(context)
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "내 위치"
            )
        }

        // Prominent Disclosure 다이얼로그
        if (permissionState == LocationPermissionState.ShowDisclosure) {
            LocationDisclosureDialog(
                onAgree = { viewModel.onDisclosureAgreed() },
                onDismiss = { viewModel.onDisclosureDismissed() }
            )
        }

        // Snackbar (권한 거부 안내)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}