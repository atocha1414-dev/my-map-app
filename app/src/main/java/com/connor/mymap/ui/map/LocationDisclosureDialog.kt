package com.connor.mymap.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Play Store 정책을 준수하는 위치 권한 사전 공지 다이얼로그
 *
 * 필수 요소:
 * - 어떤 데이터를 수집하는지 명시
 * - 왜 필요한지 명시
 * - 어떻게 사용되는지 명시
 */
@Composable
fun LocationDisclosureDialog(
    onAgree: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "위치 정보 이용 안내",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "이 앱은 지도 위에 현재 위치를 표시하고, 사용자가 시작한 이동 경로를 기록하기 위해 위치 정보에 접근합니다.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(16.dp))

                DisclosureItem(
                    emoji = "📍",
                    title = "수집하는 정보",
                    description = "기기의 GPS 위치 (위도/경도)"
                )

                Spacer(Modifier.height(12.dp))

                DisclosureItem(
                    emoji = "🎯",
                    title = "사용 목적",
                    description = "지도 위에 내 위치 표시 및 이동 경로 기록"
                )

                Spacer(Modifier.height(12.dp))

                DisclosureItem(
                    emoji = "🔒",
                    title = "정보 보호",
                    description = "위치 정보는 기기 내에서만 사용되며, " +
                            "외부 서버로 전송되지 않습니다"
                )

                Spacer(Modifier.height(12.dp))

                DisclosureItem(
                    emoji = "⏱️",
                    title = "사용 시점",
                    description = "내 위치 확인 시점 또는 사용자가 기록 시작을 누른 동안 사용"
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "다음 화면에서 Android 시스템이 위치 권한을 요청할 거예요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text("계속")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun DisclosureItem(
    emoji: String,
    title: String,
    description: String
) {
    Row {
        Text(
            text = emoji,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
