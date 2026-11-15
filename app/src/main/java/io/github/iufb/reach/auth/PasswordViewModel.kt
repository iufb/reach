package io.github.iufb.reach.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PasswordUiState(
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val error: AuthError? = null,
) {
    val canSubmit: Boolean get() = !submitting && password.isNotEmpty()
}

@HiltViewModel
class PasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    /** Подсказка пароля; пустая, если её нет или экран открыт не в состоянии WaitPassword. */
    val hint: StateFlow<String> = auth.state
        .filterIsInstance<AuthState.WaitPassword>()
        .map { it.hint }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (auth.state.value as? AuthState.WaitPassword)?.hint.orEmpty())

    private val _state = MutableStateFlow(PasswordUiState())
    val state: StateFlow<PasswordUiState> = _state.asStateFlow()

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun onTogglePasswordVisible() {
        _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // При успехе TDLib пришлёт AuthorizationStateReady, экран сменит навигация
            val error = runCatching { auth.checkPassword(current.password) }.exceptionOrNull()?.toAuthError()
            // Пароль не стираем: с кнопкой «Показать» опечатку проще поправить, чем набрать всё заново
            _state.update { it.copy(submitting = false, error = error) }
        }
    }
}
