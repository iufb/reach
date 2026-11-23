package io.github.iufb.reach.auth

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.SplashGate
import io.github.iufb.reach.chat.RichTextView
import io.github.iufb.reach.ui.components.DialogSystemBars
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.RoundCheck
import io.github.iufb.reach.ui.components.SheetHeader
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Вход в стиле Telegram iOS (#209), но со своим брендингом: знак Reach вместо логотипа Telegram.
 * Раскладка: знак, крупный заголовок, подзаголовок, поле между тонкими линиями, акцентная пилюля
 * «Продолжить» над клавиатурой. Содержимое каждого шага отделено от ViewModel ([PhoneContent] и др.),
 * чтобы его можно было показать в каталоге компонентов без выхода из аккаунта.
 */

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

/** Номер телефона: одно поле с кодом страны. */
@Composable
internal fun PhoneContent(state: PhoneUiState, onPhoneNumberChange: (String) -> Unit, onSubmit: () -> Unit) {
    AuthLayout(
        title = stringResource(R.string.auth_phone_title),
        subtitle = stringResource(R.string.auth_phone_subtitle),
        button = { AuthButton(enabled = state.canSubmit, submitting = state.submitting, onClick = onSubmit) },
    ) {
        AuthLineField(
            value = state.phoneNumber,
            onValueChange = onPhoneNumberChange,
            placeholder = stringResource(R.string.auth_phone_label),
            keyboardType = KeyboardType.Phone,
            enabled = !state.submitting,
            onDone = onSubmit,
        )
        state.error?.let { AuthErrorText(it) }
    }
}

@Composable
fun CodeScreen(onChangePhoneNumber: () -> Unit, viewModel: CodeViewModel = hiltViewModel()) {
    val info by viewModel.codeInfo.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    CodeContent(info, state, viewModel::onCodeChange, viewModel::submit, viewModel::resend, onChangePhoneNumber)
}

/** Код подтверждения: ячейки по длине кода, переотправка с таймером, «Изменить номер». */
@Composable
internal fun CodeContent(
    info: AuthState.WaitCode?,
    state: CodeUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onChangePhoneNumber: () -> Unit,
) {
    val length = info?.delivery?.length?.takeIf { it in 1..MAX_CODE } ?: DEFAULT_CODE
    AuthLayout(
        title = info?.phoneNumber?.let { "+" + it.removePrefix("+") } ?: stringResource(R.string.auth_code_title),
        subtitle = info?.delivery?.let { deliveryText(it) }.orEmpty(),
        button = { AuthButton(enabled = !state.submitting && state.code.isNotEmpty(), submitting = state.submitting, onClick = onSubmit) },
    ) {
        CodeCells(
            code = state.code,
            length = length,
            onCodeChange = onCodeChange,
            enabled = !state.submitting,
            error = state.error != null,
            onDone = onSubmit,
        )
        state.error?.let { AuthErrorText(it) }
        if (info?.nextDelivery != null) ResendLink(info, onResend)
        AuthLink(stringResource(R.string.auth_change_phone), onClick = onChangePhoneNumber)
    }
}

@Composable
private fun ResendLink(info: AuthState.WaitCode, onResend: () -> Unit) {
    // Новый WaitCode (после переотправки) перезапускает таймер
    var secondsLeft by remember(info) { mutableIntStateOf(info.resendTimeoutSeconds) }
    LaunchedEffect(info) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }
    val next = info.nextDelivery ?: return
    AuthLink(
        if (secondsLeft > 0) stringResource(R.string.auth_resend_in, secondsLeft / 60, secondsLeft % 60) else resendText(next),
        enabled = secondsLeft == 0,
        onClick = onResend,
    )
}

@Composable
fun PasswordScreen(onChangePhoneNumber: () -> Unit, viewModel: PasswordViewModel = hiltViewModel()) {
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    PasswordContent(
        hint, state, viewModel::onPasswordChange, viewModel::onTogglePasswordVisible, viewModel::submit, onChangePhoneNumber,
    )
}

/** Облачный пароль: подсказка — плейсхолдером, как в iOS; «Показать / Скрыть» справа. */
@Composable
internal fun PasswordContent(
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
        button = { AuthButton(enabled = state.canSubmit, submitting = state.submitting, onClick = onSubmit) },
    ) {
        AuthLineField(
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = hint.ifBlank { stringResource(R.string.auth_password_label) },
            keyboardType = KeyboardType.Password,
            enabled = !state.submitting,
            onDone = onSubmit,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                AuthLink(
                    stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show),
                    onClick = onTogglePasswordVisible,
                )
            },
        )
        state.error?.let { AuthErrorText(it) }
        AuthLink(stringResource(R.string.auth_change_phone), onClick = onChangePhoneNumber)
    }
}

/** Новый аккаунт: имя, фамилия, условия Telegram (без согласия — нельзя). */
@Composable
fun RegistrationScreen(onChangePhoneNumber: () -> Unit, viewModel: RegistrationViewModel = hiltViewModel()) {
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RegistrationContent(
        state, terms,
        viewModel::onFirstNameChange, viewModel::onLastNameChange, viewModel::onAcceptTerms, viewModel::submit, onChangePhoneNumber,
    )
}

@Composable
internal fun RegistrationContent(
    state: RegistrationUiState,
    terms: AuthState.WaitRegistration?,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onAcceptTerms: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onChangePhoneNumber: () -> Unit,
) {
    var showTerms by remember { mutableStateOf(false) }
    val colors = ReachTheme.colors
    AuthLayout(
        title = stringResource(R.string.auth_register_title),
        subtitle = stringResource(R.string.auth_register_subtitle),
        button = { AuthButton(enabled = state.canSubmit, submitting = state.submitting, onClick = onSubmit) },
    ) {
        AuthLineField(
            value = state.firstName,
            onValueChange = onFirstNameChange,
            placeholder = stringResource(R.string.contacts_first_name),
            keyboardType = KeyboardType.Text,
            enabled = !state.submitting,
            onDone = onSubmit,
            bottomLine = false,
        )
        AuthLineField(
            value = state.lastName,
            onValueChange = onLastNameChange,
            placeholder = stringResource(R.string.auth_register_last_name),
            keyboardType = KeyboardType.Text,
            enabled = !state.submitting,
            onDone = onSubmit,
            focusOnStart = false,
        )
        state.error?.let { AuthErrorText(it) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(state.acceptedTerms, role = Role.Checkbox, onValueChange = onAcceptTerms)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundCheck(selected = state.acceptedTerms)
            Text(
                stringResource(R.string.auth_register_accept),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.label,
            )
        }
        if (terms != null) AuthLink(stringResource(R.string.auth_register_terms), onClick = { showTerms = true })
        AuthLink(stringResource(R.string.auth_change_phone), onClick = onChangePhoneNumber)
    }
    if (showTerms && terms != null) {
        TermsSheet(
            terms,
            onAccept = {
                showTerms = false
                onAcceptTerms(true)
            },
            onDismiss = { showTerms = false },
        )
    }
}

/** Условия Telegram во весь экран: «Закрыть» / заголовок / «Принимаю», текст прокручивается. */
@Composable
private fun TermsSheet(terms: AuthState.WaitRegistration, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
            SheetHeader(
                title = stringResource(R.string.auth_register_terms),
                cancel = stringResource(R.string.close),
                onCancel = onDismiss,
                action = stringResource(R.string.auth_register_accept_short),
                onAction = onAccept,
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (terms.minAge > 0) {
                    Text(
                        stringResource(R.string.auth_register_min_age, terms.minAge),
                        modifier = Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.label,
                    )
                }
                RichTextView(terms.terms, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun UnsupportedAuthScreen(step: String) {
    AuthLayout(
        title = stringResource(R.string.auth_unsupported_title),
        subtitle = stringResource(R.string.auth_unsupported_subtitle, step),
    ) {}
}

/**
 * Каркас шага входа: сверху знак Reach, заголовок и подзаголовок, ниже [content];
 * [button] прижата к низу и поднимается вместе с клавиатурой.
 */
@Composable
private fun AuthLayout(
    title: String,
    subtitle: String,
    button: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ReachTheme.colors
    DialogSystemBars()
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                BrandMark()
                Text(
                    title,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.label,
                    textAlign = TextAlign.Center,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
            if (button != null) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) { button() }
        }
    }
}

/** Знак Reach: буква «R» из иконки приложения на акцентном квадрате со скруглением, как значок приложения. */
/** Анимация знака играет один раз за запуск: не на каждом шаге входа и не при повороте экрана. */
private var brandAnimationPlayed = false

/**
 * Знак Reach с анимацией «Прорезь» (ADR-0012). Тот же `avd_splash`, что и на системном сплэше,
 * через платформенный AnimatedVectorDrawable: разбор AVD в Compose на нём падает.
 * Круг в холсте занимает 72 из 108, поэтому холст в 1,5 раза больше [BRAND_SIZE].
 */
@Composable
private fun BrandMark() {
    val context = LocalContext.current
    val avd = remember { if (brandAnimationPlayed) null else context.getDrawable(R.drawable.avd_splash) as AnimatedVectorDrawable }
    LaunchedEffect(avd) {
        if (avd == null) return@LaunchedEffect
        // На холодном старте экран входа собирается под сплэшем: ждём, пока тот уйдёт
        withTimeoutOrNull(SPLASH_WAIT_MS) { SplashGate.dismissed.first { it } }
        avd.start()
        brandAnimationPlayed = true
    }
    Box(Modifier.size(BRAND_SIZE), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ImageView(it).apply { if (avd != null) setImageDrawable(avd) else setImageResource(R.drawable.ic_reach_mark_circle) } },
            modifier = Modifier.requiredSize(BRAND_SIZE * 1.5f),
        )
    }
}

private const val SPLASH_WAIT_MS = 3_000L

/**
 * Поле шага входа как в iOS: без рамки, между тонкими линиями во всю ширину, плейсхолдер серым.
 * [focusOnStart] — фокус и клавиатура сразу при открытии шага.
 */
@Composable
private fun AuthLineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    onDone: () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    focusOnStart: Boolean = true,
    bottomLine: Boolean = true,
) {
    val colors = ReachTheme.colors
    val focus = remember { FocusRequester() }
    if (focusOnStart) LaunchedEffect(Unit) { focus.requestFocus() }
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.label)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        Row(Modifier.fillMaxWidth().heightIn(min = FIELD_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = style.copy(color = colors.tertiaryLabel), maxLines = 1)
                        inner()
                    }
                },
            )
            trailing?.invoke()
        }
        if (bottomLine) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
    }
}

/** Код по ячейкам: скрытое поле ввода, ячейки рисуют цифры; текущая — с акцентной рамкой, ошибка — красной. */
@Composable
private fun CodeCells(code: String, length: Int, onCodeChange: (String) -> Unit, enabled: Boolean, error: Boolean, onDone: () -> Unit) {
    val colors = ReachTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(length)) },
        modifier = Modifier.focusRequester(focus),
        enabled = enabled,
        singleLine = true,
        // Цифры и так видны в ячейках: обычная цифровая клавиатура, а не «парольная»
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until length) {
                    val current = i == code.length.coerceAtMost(length - 1)
                    val border = when {
                        error -> colors.destructive
                        current -> colors.accent
                        else -> colors.separator
                    }
                    Box(
                        Modifier
                            .width(CODE_CELL_WIDTH)
                            .height(CODE_CELL_HEIGHT)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.fill)
                            .border(if (current || error) 1.5.dp else 0.5.dp, border, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.label)
                    }
                }
            }
        },
    )
}

/** Акцентная пилюля «Продолжить» во всю ширину; во время запроса — индикатор вместо текста. */
@Composable
private fun AuthButton(enabled: Boolean, submitting: Boolean, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(AUTH_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled || submitting) colors.accent else colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (submitting) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Text(
                stringResource(R.string.auth_next),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.onAccent else colors.tertiaryLabel,
            )
        }
    }
}

/** Ссылка-действие акцентом: «Изменить номер», «Отправить ещё раз», «Показать». */
@Composable
private fun AuthLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = ReachTheme.colors
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) colors.accent else colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AuthErrorText(error: AuthError) {
    Text(
        errorText(error),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = ReachTheme.colors.destructive,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(ReachTheme.colors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ReachTheme.colors.accent)
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
    is AuthError.Unknown -> error.error.arg?.let { stringResource(error.error.text, it) } ?: stringResource(error.error.text)
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

private val MAX_WIDTH = 480.dp
private val BRAND_SIZE = 88.dp
private val FIELD_HEIGHT = 52.dp
private val CODE_CELL_WIDTH = 44.dp
private val CODE_CELL_HEIGHT = 56.dp
private val AUTH_BUTTON_HEIGHT = PillHeight + 6.dp
private const val DEFAULT_CODE = 5
private const val MAX_CODE = 8
