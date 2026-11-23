package io.github.iufb.reach.auth

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.phone.PhoneInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhoneUiState(
    /** Код страны и номер отдельно, только цифры; маску рисует поле (#330). */
    val phone: PhoneInput = PhoneInput(),
    val submitting: Boolean = false,
    val error: AuthError? = null,
    /** Telegram попросил подождать (FLOOD_WAIT): до этого момента по `elapsedRealtime` «Далее» недоступна. */
    val retryUntil: Long? = null,
    /** Номер принят TDLib: навигация должна перейти к следующему шагу. */
    val submitted: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting && phone.code.isNotEmpty() && phone.digits.length >= MIN_DIGITS

    private companion object {
        const val MIN_DIGITS = 5
    }
}

@HiltViewModel
class PhoneViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    fun onPhoneChange(value: PhoneInput) {
        _state.update { it.copy(phone = value, error = null) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        if ((current.retryUntil ?: 0) > SystemClock.elapsedRealtime()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // Дальше экран сменит навигация: TDLib пришлёт AuthorizationStateWaitCode
            val error = runCatching { auth.setPhoneNumber("+" + current.phone.digits) }
                .exceptionOrNull()?.toAuthError()
            val retryUntil = (error as? AuthError.FloodWait)?.let { SystemClock.elapsedRealtime() + it.seconds * 1000L }
            _state.update { it.copy(submitting = false, error = error, submitted = error == null, retryUntil = retryUntil) }
        }
    }

    fun onSubmittedHandled() {
        _state.update { it.copy(submitted = false) }
    }
}
