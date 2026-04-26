package com.connor.mymap.ui.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TermsScreen(
    onTermsAccepted: () -> Unit,
    onShowTermsDetail: (typeKey: String) -> Unit,
    viewModel: TermsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "서비스 이용 동의",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "MyMap은 오프라인 지도 표시, 내 위치 확인, 이동 경로 기록 기능을 제공하기 위해 필요한 항목만 안내하고 동의를 받습니다. 위치 권한과 GPS 설정은 위치 기능을 사용할 때만 요청합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 변경 이유: Play Store 제출 시 앱 내부에서 필수/선택 동의를 명확히 구분해야 하므로
                // 전체 동의는 편의 기능으로 제공하되 필수 항목 동의 여부는 각각 보관한다.
                TermsCheckRow(
                    title = "전체 동의",
                    description = "필수 항목과 선택 항목을 모두 동의합니다.",
                    checked = uiState.allAccepted,
                    onCheckedChange = viewModel::setAllAccepted,
                    onShowDetail = null
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TermsCheckRow(
                    title = "[필수] 이용약관",
                    description = "앱 이용 조건과 서비스 제공 범위에 동의합니다.",
                    checked = uiState.serviceTermsAccepted,
                    onCheckedChange = viewModel::setServiceTermsAccepted,
                    onShowDetail = { onShowTermsDetail(TermsType.Service.routeKey) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TermsCheckRow(
                    title = "[필수] 개인정보처리방침",
                    description = "앱이 처리하는 개인정보 항목, 이용 목적, 보유 및 파기 기준을 확인했습니다.",
                    checked = uiState.privacyPolicyAccepted,
                    onCheckedChange = viewModel::setPrivacyPolicyAccepted,
                    onShowDetail = { onShowTermsDetail(TermsType.Privacy.routeKey) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TermsCheckRow(
                    title = "[필수] 위치기반서비스 이용약관",
                    description = "내 위치 확인 및 사용자가 시작한 이동 경로 기록 중 현재 위치를 지도 위에 표시하고 기기 내부에 저장하는 것에 동의합니다. 이 동의만으로 위치 권한이나 GPS가 켜지지는 않으며, 위치 정보는 서버로 전송하지 않습니다.",
                    checked = uiState.locationTermsAccepted,
                    onCheckedChange = viewModel::setLocationTermsAccepted,
                    onShowDetail = { onShowTermsDetail(TermsType.Location.routeKey) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TermsCheckRow(
                    title = "[선택] 마케팅 정보 수신",
                    description = "서비스 소식과 안내를 받을 수 있습니다. 동의하지 않아도 앱을 이용할 수 있습니다.",
                    checked = uiState.marketingAccepted,
                    onCheckedChange = viewModel::setMarketingAccepted,
                    onShowDetail = null
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "현재 버전에서는 계정 가입, 광고 추적, 서버 위치 전송을 사용하지 않습니다. 백그라운드 위치는 사용자가 이동 경로 기록을 시작한 동안에만 사용합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                // 정책 반영: 약관 동의 완료 시점에는 위치 권한 요청이나 GPS 켜기 팝업을 띄우지 않는다.
                // 위치 접근은 사용자가 지도 화면에서 "내 위치" 버튼을 누른 직후에만 요청해야
                // Google Play의 in-context permission 흐름과 사용자의 합리적 기대에 맞는다.
                viewModel.acceptRequiredTerms(onComplete = onTermsAccepted)
            },
            enabled = uiState.allRequiredAccepted && !uiState.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (uiState.isSaving) "저장 중..." else "동의하고 시작하기",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun TermsCheckRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onShowDetail: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 변경 이유: Play 심사 및 정보통신망법 모두 약관 본문을 동의 시점에 직접 확인할 수 있어야 한다.
        // 필수 약관 행에는 본문으로 진입하는 버튼을 둔다.
        if (onShowDetail != null) {
            TextButton(onClick = onShowDetail) {
                Text("전문 보기")
            }
        }
    }
}
