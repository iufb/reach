package io.github.iufb.reach.auth

import io.github.iufb.reach.chat.RichTextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.delay

@Composable
fun PhoneScreen(onSubmitted: () -> Unit, viewModel: PhoneViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.submitted) {
        if (state.submitted) {
            onSubmitted()
            viewModel.onSubmittedHandled()
        }
    }
    PhoneContent(state, viewModel::onPhoneNumberChange, viewModel::submit)
}

@Composable
private fun PhoneContent(state: PhoneUiState, onPhoneNumberChange: (String) -> Unit, onSubmit: () -> Unit) {
    AuthLayout(
        title = stringResource(R.string.auth_phone_title),
        subtitle = stringResource(R.string.auth_phone_subtitle),
    ) {
        AuthField(
            value = state.phoneNumber,
            onValueChange = onPhoneNumberChange,
            label = stringResource(R.string.auth_phone_label),
            keyboardType = KeyboardType.Phone,
            enabled = !state.submitting,
            error = state.error,
            onDone = onSubmit,
        )
        SubmitButton(enabled = state.canSubmit, submitting = state.submitting, onClick = onSubmit)
    }
}

@Composable
fun CodeScreen(onChangePhoneNumber: () -> Unit, viewModel: CodeViewModel = hiltViewModel()) {
    val info by viewModel.codeInfo.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    CodeContent(info, state, viewModel::onCodeChange, viewModel::submit, viewModel::resend, onChangePhoneNumber)
}

@Composable
private fun CodeContent(
    info: AuthState.WaitCode?,
    state: CodeUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onChangePhoneNumber: () -> Unit,
) {
    AuthLayout(
        title = info?.phoneNumber?.let { "+" + it.removePrefix("+") } ?: stringResource(R.string.auth_code_title),
        subtitle = info?.delivery?.let { deliveryText(it) }.orEmpty(),
    ) {
        AuthField(
            value = state.code,
            onValueChange = onCodeChange,
            label = stringResource(R.string.auth_code_label),
            keyboardType = KeyboardType.NumberPassword,
            enabled = !state.submitting,
            error = state.error,
            onDone = onSubmit,
        )
        SubmitButton(enabled = !state.submitting && state.code.isNotEmpty(), submitting = state.submitting, onClick = onSubmit)
        if (info?.nextDelivery != null) ResendButton(info, onResend)
        TextButton(onClick = onChangePhoneNumber) { Text(stringResource(R.string.auth_change_phone)) }
    }
}

@Composable
private fun ResendButton(info: AuthState.WaitCode, onResend: () -> Unit) {
    // Новый WaitCode (после переотправки) перезапускает таймер
    var secondsLeft by remember(info) { mutableIntStateOf(info.resendTimeoutSeconds) }
    LaunchedEffect(info) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }
    val next = info.nextDelivery ?: return
    TextButton(onClick = onResend, enabled = secondsLeft == 0) {
        Text(
            if (secondsLeft > 0) stringResource(R.string.auth_resend_in, secondsLeft / 60, secondsLeft % 60)
            else resendText(next),
        )
    }
}

@Composable
fun PasswordScreen(onChangePhoneNumber: () -> Unit, viewModel: PasswordViewModel = hiltViewModel()) {
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    PasswordContent(
        hint, state, viewModel::onPasswordChange, viewModel::onTogglePasswordVisible, viewModel::submit, onChangePhoneNumber,
    )
}

@Composable
private fun PasswordContent(
    hint: String,
    state: PasswordUiState,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onChangePhoneNumber: () -> Unit,
) {
    AuthLayout(
        title = stringResource(R.string.auth_password_title),
        subtitle = stringResource(R.string.auth_password_subtitle),
    ) {
        AuthField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.auth_password_label),
            keyboardType = KeyboardType.Password,
            enabled = !state.submitting,
            error = state.error,
            onDone = onSubmit,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                TextButton(onClick = onTogglePasswordVisible) {
                    Text(stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show))
                }
            },
            // Подсказка видна, пока нет ошибки: ошибка занимает ту же строку под полем
            supporting = hint.takeIf { it.isNotBlank() }?.let { stringResource(R.string.auth_password_hint, it) },
        )
        SubmitButton(enabled = state.canSubmit, submitting = state.submitting, onClick = onSubmit)
        TextButton(onClick = onChangePhoneNumber) { Text(stringResource(R.string.auth_change_phone)) }
    }
}

/** Новый аккаунт: имя, фамилия, условия Telegram (без согласия — нельзя). */
@Composable
fun RegistrationScreen(onChangePhoneNumber: () -> Unit, viewModel: RegistrationViewModel = hiltViewModel()) {
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTerms by remember { mutableStateOf(false) }
    AuthLayout(
        title = stringResource(R.string.auth_register_title),
        subtitle = stringResource(R.string.auth_register_subtitle),
    ) {
        AuthField(
            value = state.firstName,
            onValueChange = viewModel::onFirstNameChange,
            label = stringResource(R.string.contacts_first_name),
            keyboardType = KeyboardType.Text,
            enabled = !state.submitting,
            error = state.error,
            onDone = viewModel::submit,
        )
        OutlinedTextField(
            value = state.lastName,
            onValueChange = viewModel::onLastNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_register_last_name)) },
            singleLine = true,
            enabled = !state.submitting,
        )
        Row(
            Modifier.fillMaxWidth().toggleable(state.acceptedTerms, role = Role.Checkbox, onValueChange = viewModel::onAcceptTerms),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.acceptedTerms, onCheckedChange = null)
            Text(stringResource(R.string.auth_register_accept), Modifier.weight(1f).padding(start = 8.dp))
        }
        TextButton(onClick = { showTerms = true }) { Text(stringResource(R.string.auth_register_terms)) }
        SubmitButton(enabled = state.canSubmit, submitting = state.submitting, onClick = viewModel::submit)
        TextButton(onClick = onChangePhoneNumber) { Text(stringResource(R.string.auth_change_phone)) }
    }
    val current = terms
    if (showTerms && current != null) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text(stringResource(R.string.auth_register_terms)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (current.minAge > 0) Text(stringResource(R.string.auth_register_min_age, current.minAge))
                    RichTextView(current.terms, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTerms = false
                    viewModel.onAcceptTerms(true)
                }) { Text(stringResource(R.string.auth_register_accept_short)) }
            },
            dismissButton = { TextButton(onClick = { showTerms = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
fun UnsupportedAuthScreen(step: String) {
    AuthLayout(
        title = stringResource(R.string.auth_unsupported_title),
        subtitle = stringResource(R.string.auth_unsupported_subtitle, step),
    ) {}
}

@Composable
private fun AuthLayout(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(68.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_send), contentDescription = null,
                    modifier = Modifier.size(34.dp).rotate(-25f),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            content()
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    error: AuthError?,
    onDone: () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = when {
            error != null -> { { Text(errorText(error)) } }
            supporting != null -> { { Text(supporting) } }
            else -> null
        },
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
private fun SubmitButton(enabled: Boolean, submitting: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        if (submitting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.auth_next))
        }
    }
}

@Composable
fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun errorText(error: AuthError): String = when (error) {
    AuthError.InvalidPhoneNumber -> stringResource(R.string.auth_error_invalid_phone)
    AuthError.PhoneNumberBanned -> stringResource(R.string.auth_error_phone_banned)
    AuthError.InvalidCode -> stringResource(R.string.auth_error_invalid_code)
    AuthError.CodeExpired -> stringResource(R.string.auth_error_code_expired)
    AuthError.InvalidPassword -> stringResource(R.string.auth_error_invalid_password)
    AuthError.SignUpForbidden -> stringResource(R.string.auth_error_signup_forbidden)
    AuthError.InvalidName -> stringResource(R.string.auth_error_invalid_name)
    is AuthError.FloodWait -> floodWaitText(error.seconds)
    is AuthError.Unknown -> stringResource(R.string.auth_error_unknown, error.message)
}

@Composable
private fun floodWaitText(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600 + 59) / 60
    val wait = when {
        hours > 0 -> pluralStringResource(R.plurals.hours, hours, hours)
        minutes > 0 -> pluralStringResource(R.plurals.minutes, minutes, minutes)
        else -> pluralStringResource(R.plurals.seconds, seconds, seconds)
    }
    return stringResource(R.string.auth_error_flood_wait, wait)
}

@Composable
private fun deliveryText(delivery: CodeDelivery): String = when (delivery) {
    is CodeDelivery.TelegramMessage -> stringResource(R.string.auth_code_sent_telegram)
    is CodeDelivery.Sms -> stringResource(R.string.auth_code_sent_sms)
    is CodeDelivery.Call -> stringResource(R.string.auth_code_sent_call)
    CodeDelivery.Other -> stringResource(R.string.auth_code_sent_other)
}

@Composable
private fun resendText(next: CodeDelivery): String = when (next) {
    is CodeDelivery.Sms -> stringResource(R.string.auth_resend_sms)
    is CodeDelivery.Call -> stringResource(R.string.auth_resend_call)
    else -> stringResource(R.string.auth_resend)
}

@Preview
@Composable
private fun PhonePreview() {
    ReachTheme { PhoneContent(PhoneUiState(phoneNumber = "+1 555"), {}, {}) }
}

@Preview
@Composable
private fun CodePreview() {
    ReachTheme {
        CodeContent(
            info = AuthState.WaitCode("15550001234", CodeDelivery.TelegramMessage(5), CodeDelivery.Sms(5), 42),
            state = CodeUiState(code = "12", error = AuthError.InvalidCode),
            onCodeChange = {}, onSubmit = {}, onResend = {}, onChangePhoneNumber = {},
        )
    }
}

@Preview
@Composable
private fun PasswordPreview() {
    ReachTheme {
        PasswordContent(
            hint = "кличка кота",
            state = PasswordUiState(password = "secret"),
            onPasswordChange = {}, onTogglePasswordVisible = {}, onSubmit = {}, onChangePhoneNumber = {},
        )
    }
}
