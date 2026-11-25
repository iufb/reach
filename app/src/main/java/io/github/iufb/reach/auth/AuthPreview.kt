package io.github.iufb.reach.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.navigation.authStepEnter
import io.github.iufb.reach.navigation.authStepExit

/** Шаги входа, которые можно открыть в каталоге компонентов. */
enum class AuthPreviewStep { Phone, Code, Password, Registration, Flow }

/**
 * Шаг входа во весь экран с локальным состоянием — только для каталога компонентов (debug):
 * экраны входа видны и проверяются на устройстве без выхода из аккаунта. Сеть не трогается.
 */
@Composable
fun AuthPreviewDialog(step: AuthPreviewStep, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        when (step) {
            AuthPreviewStep.Phone -> {
                var state by remember { mutableStateOf(PhoneUiState()) }
                PhoneContent(state, { state = state.copy(phone = it, error = null) }, {})
            }
            AuthPreviewStep.Code -> {
                var state by remember { mutableStateOf(CodeUiState()) }
                CodeContent(
                    info = AuthState.WaitCode("99966 1 2345", CodeDelivery.TelegramMessage(5), CodeDelivery.Sms(5), RESEND_SECONDS),
                    state = state,
                    onCodeChange = { state = state.copy(code = it, error = null) },
                    // Демонстрация ошибки: любой код «неверный»
                    onSubmit = { state = state.copy(error = AuthError.InvalidCode) },
                    onResend = {},
                    onChangePhoneNumber = onDismiss,
                )
            }
            AuthPreviewStep.Password -> {
                var state by remember { mutableStateOf(PasswordUiState()) }
                PasswordContent(
                    hint = "кличка кота",
                    state = state,
                    onPasswordChange = { state = state.copy(password = it, error = null) },
                    onTogglePasswordVisible = { state = state.copy(passwordVisible = !state.passwordVisible) },
                    onSubmit = { state = state.copy(error = AuthError.InvalidPassword) },
                    onChangePhoneNumber = onDismiss,
                )
            }
            AuthPreviewStep.Flow -> AuthPreviewFlow(onDismiss)
            AuthPreviewStep.Registration -> {
                var state by remember { mutableStateOf(RegistrationUiState()) }
                RegistrationContent(
                    state = state,
                    terms = AuthState.WaitRegistration(RichText(PREVIEW_TERMS), minAge = 16),
                    onFirstNameChange = { state = state.copy(firstName = it) },
                    onLastNameChange = { state = state.copy(lastName = it) },
                    onAcceptTerms = { state = state.copy(acceptedTerms = it) },
                    onSubmit = {},
                    onChangePhoneNumber = onDismiss,
                )
            }
        }
    }
}

/**
 * Телефон → код → пароль подряд, с теми же переходами, что в навигации (#375): «Продолжить» ведёт
 * вперёд, «Изменить номер» — назад к телефону. Код и пароль здесь принимаются любые.
 */
@Composable
private fun AuthPreviewFlow(onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    fun go(to: Int) {
        forward = to > step
        step = to
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = { authStepEnter(forward) togetherWith authStepExit(forward) },
        label = "authFlow",
    ) { shown ->
        when (shown) {
            0 -> {
                var state by remember { mutableStateOf(PhoneUiState()) }
                PhoneContent(state, { state = state.copy(phone = it, error = null) }, { go(1) })
            }
            1 -> {
                var state by remember { mutableStateOf(CodeUiState()) }
                CodeContent(
                    info = AuthState.WaitCode("99966 1 2345", CodeDelivery.TelegramMessage(5), CodeDelivery.Sms(5), RESEND_SECONDS),
                    state = state,
                    onCodeChange = { state = state.copy(code = it, error = null) },
                    onSubmit = { go(2) },
                    onResend = {},
                    onChangePhoneNumber = { go(0) },
                )
            }
            else -> {
                var state by remember { mutableStateOf(PasswordUiState()) }
                PasswordContent(
                    hint = "кличка кота",
                    state = state,
                    onPasswordChange = { state = state.copy(password = it, error = null) },
                    onTogglePasswordVisible = { state = state.copy(passwordVisible = !state.passwordVisible) },
                    onSubmit = onDismiss,
                    onChangePhoneNumber = { go(0) },
                )
            }
        }
    }
}

private const val RESEND_SECONDS = 59
private const val PREVIEW_TERMS = "Пример условий использования для предпросмотра. Настоящие условия присылает Telegram при регистрации."
