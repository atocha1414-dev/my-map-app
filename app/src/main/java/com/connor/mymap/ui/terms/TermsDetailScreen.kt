package com.connor.mymap.ui.terms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.connor.mymap.ui.common.ErrorView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsDetailScreen(
    typeKey: String?,
    onBack: () -> Unit
) {
    val type = TermsType.fromRouteKey(typeKey)

    if (type == null) {
        ErrorView(
            title = "약관을 찾을 수 없습니다",
            message = "잘못된 약관 종류입니다.",
            onRetry = onBack
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(type.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .padding(padding)
            .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = TermsContent.bodyOf(type),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
