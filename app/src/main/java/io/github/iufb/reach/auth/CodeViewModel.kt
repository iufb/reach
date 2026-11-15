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

data class CodeUiState(
    val code: String = "",
    val submitting: Boolean = false,
    val error: AuthError? = null,
)

@HiltViewModel
class CodeViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    /** Куда отправлен код. `null`, пока экран открыт не в состоянии WaitCode (например, во время перехода). */
    val codeInfo: StateFlow<AuthState.WaitCode?> = auth.state
        .filterIsInstance<AuthState.WaitCode>()
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.state.value as? AuthState.WaitCode)

    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()

    fun onCodeChange(value: String) {
        val digits = value.filter(Char::isDigit)
        _state.update { it.copy(code = digits, error = null) }
        // Код полной длины отправляем сразу, без нажатия кнопки
        val length = codeInfo.value?.delivery?.length
        if (length != null && length > 0 && digits.length == length) submit()
    }

    fun submit() {
        val current = _state.value
        if (current.submitting || current.code.isEmpty()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val error = runCatching { auth.checkCode(current.code) }.exceptionOrNull()?.toAuthError()
            _state.update { it.copy(submitting = false, error = error, code = if (error != null) "" else it.code) }
        }
    }

    fun resend() {
        viewModelScope.launch {
            val error = runCatching { auth.resendCode() }.exceptionOrNull()?.toAuthError()
            _state.update { it.copy(error = error, code = "") }
        }
    }
}
