package com.connor.mymap.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.termsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "terms_preferences"
)

/**
 * 출시 대비 약관/개인정보/위치기반서비스 동의 상태를 보관한다.
 * 변경 이유: Google Play와 국내 위치정보 고지 흐름에 맞추려면
 * 최초 실행 시 필수 약관 동의를 한 번만 받고 이후에는 다운로드 화면으로 진입해야 한다.
 */
class TermsPreferences(context: Context) {

    private val dataStore = context.applicationContext.termsDataStore

    val hasAcceptedRequiredTerms: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[HAS_ACCEPTED_REQUIRED_TERMS] ?: false
        }

    val hasAcceptedMarketing: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[HAS_ACCEPTED_MARKETING] ?: false
        }

    suspend fun acceptTerms(marketingAccepted: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAS_ACCEPTED_REQUIRED_TERMS] = true
            preferences[HAS_ACCEPTED_MARKETING] = marketingAccepted
        }
    }

    companion object {
        private val HAS_ACCEPTED_REQUIRED_TERMS =
            booleanPreferencesKey("has_accepted_required_terms")
        private val HAS_ACCEPTED_MARKETING =
            booleanPreferencesKey("has_accepted_marketing")
    }
}
