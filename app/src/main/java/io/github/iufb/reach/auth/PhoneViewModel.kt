package io.github.iufb.reach.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.ui.format.PhoneMask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhoneUiState(
    /** Только цифры, с кодом страны; маску рисует поле. */
    val phoneNumber: String = "",
    val submitting: Boolean = false,
    val error: AuthError? = null,
    /** Номер принят TDLib: навигация должна перейти к следующему шагу. */
    val submitted: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting && phoneNumber.length >= MIN_DIGITS

    private companion object {
        const val MIN_DIGITS = 5
    }
}

@HiltViewModel
class PhoneViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    fun onPhoneNumberChange(value: String) {
        _state.update { it.copy(phoneNumber = PhoneMask.digits(value), error = null) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // Дальше экран сменит навигация: TDLib пришлёт AuthorizationStateWaitCode
            val error = runCatching { auth.setPhoneNumber("+" + current.phoneNumber) }
                .exceptionOrNull()?.toAuthError()
            _state.update { it.copy(submitting = false, error = error, submitted = error == null) }
        }
    }

    fun onSubmittedHandled() {
        _state.update { it.copy(submitted = false) }
    }
}
