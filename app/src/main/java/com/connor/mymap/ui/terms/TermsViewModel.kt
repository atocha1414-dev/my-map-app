package com.connor.mymap.ui.terms

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.connor.mymap.data.local.TermsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TermsUiState(
    val serviceTermsAccepted: Boolean = false,
    val privacyPolicyAccepted: Boolean = false,
    val locationTermsAccepted: Boolean = false,
    val marketingAccepted: Boolean = false,
    val isSaving: Boolean = false
) {
    val allRequiredAccepted: Boolean
        get() = serviceTermsAccepted && privacyPolicyAccepted && locationTermsAccepted

    val allAccepted: Boolean
        get() = allRequiredAccepted && marketingAccepted
}

class TermsViewModel(application: Application) : AndroidViewModel(application) {

    private val termsPreferences = TermsPreferences(application)

    private val _uiState = MutableStateFlow(TermsUiState())
    val uiState: StateFlow<TermsUiState> = _uiState.asStateFlow()

    fun setServiceTermsAccepted(accepted: Boolean) {
        _uiState.update { it.copy(serviceTermsAccepted = accepted) }
    }

    fun setPrivacyPolicyAccepted(accepted: Boolean) {
        _uiState.update { it.copy(privacyPolicyAccepted = accepted) }
    }

    fun setLocationTermsAccepted(accepted: Boolean) {
        _uiState.update { it.copy(locationTermsAccepted = accepted) }
    }

    fun setMarketingAccepted(accepted: Boolean) {
        _uiState.update { it.copy(marketingAccepted = accepted) }
    }

    fun setAllAccepted(accepted: Boolean) {
        _uiState.update {
            it.copy(
                serviceTermsAccepted = accepted,
                privacyPolicyAccepted = accepted,
                locationTermsAccepted = accepted,
                marketingAccepted = accepted
            )
        }
    }

    fun acceptRequiredTerms(onComplete: () -> Unit) {
        val currentState = _uiState.value
        if (!currentState.allRequiredAccepted || currentState.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            termsPreferences.acceptTerms(
                marketingAccepted = currentState.marketingAccepted
            )
            _uiState.update { it.copy(isSaving = false) }
            onComplete()
        }
    }
}
