package io.github.iufb.reach.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegistrationUiState(
    val firstName: String = "",
    val lastName: String = "",
    val acceptedTerms: Boolean = false,
    val submitting: Boolean = false,
    val error: AuthError? = null,
) {
    val canSubmit: Boolean get() = !submitting && acceptedTerms && firstName.isNotBlank()
}

/** Регистрация нового аккаунта (PRODUCT.md §4.1, ADR-0007). */
@HiltViewModel
class RegistrationViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    val terms: StateFlow<AuthState.WaitRegistration?> = auth.state
        .filterIsInstance<AuthState.WaitRegistration>()
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.state.value as? AuthState.WaitRegistration)

    private val _state = MutableStateFlow(RegistrationUiState())
    val state: StateFlow<RegistrationUiState> = _state.asStateFlow()

    fun onFirstNameChange(value: String) = _state.update { it.copy(firstName = value.take(NAME_MAX), error = null) }
    fun onLastNameChange(value: String) = _state.update { it.copy(lastName = value.take(NAME_MAX), error = null) }
    fun onAcceptTerms(value: Boolean) = _state.update { it.copy(acceptedTerms = value) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // При успехе TDLib пришлёт AuthorizationStateReady
            val error = runCatching { auth.register(current.firstName, current.lastName) }.exceptionOrNull()?.toAuthError()
            _state.update { it.copy(submitting = false, error = error) }
        }
    }

    private companion object {
        const val NAME_MAX = 64
    }
}
