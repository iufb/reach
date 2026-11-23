package io.github.iufb.reach.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.components.SheetHeader
import io.github.iufb.reach.ui.format.PhoneMask
import io.github.iufb.reach.ui.format.NationalPhoneTransformation
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Поле телефона как в Telegram iOS (#330): строка страны (флаг, название, шеврон → список стран),
 * под ней «+код | номер» с маской страны. Код и номер — отдельные поля: когда код распознан,
 * фокус уходит в номер; Backspace в пустом номере возвращает в код; вставка `+7 701 …` раскладывается сама.
 *
 * [horizontalPadding] — отступ содержимого строк (0 на экране входа, 16dp в карточке);
 * [outerLines] — линии сверху и снизу во всю ширину, как у полей шага входа.
 */
@Composable
fun PhoneNumberInput(
    value: PhoneInput,
    onValueChange: (PhoneInput) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    outerLines: Boolean = true,
    enabled: Boolean = true,
    focusOnStart: Boolean = false,
    onDone: () -> Unit = {},
    viewModel: PhoneCountryViewModel = hiltViewModel(),
) {
    val colors = ReachTheme.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.label)
    val countries by viewModel.countries.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val codeFocus = remember { FocusRequester() }
    val nationalFocus = remember { FocusRequester() }
    var picking by rememberSaveable { mutableStateOf(false) }
    var prefilled by rememberSaveable { mutableStateOf(false) }

    // Пустое поле: страна SIM, как в Telegram
    LaunchedEffect(countries) {
        if (prefilled || countries.isEmpty()) return@LaunchedEffect
        prefilled = true
        if (current.digits.isEmpty()) viewModel.simCountry()?.let { change(PhoneInput(it.callingCode, "", it.iso)) }
        if (focusOnStart) (if (current.code.isEmpty()) codeFocus else nationalFocus).requestFocus()
    }

    val country = remember(value, countries) { viewModel.countryFor(value) }

    // Поля на TextFieldValue: после программной смены (раскладка вставки, выбор страны) курсор — в конец
    var codeField by remember { mutableStateOf(TextFieldValue(value.code, TextRange(value.code.length))) }
    if (codeField.text != value.code) codeField = TextFieldValue(value.code, TextRange(value.code.length))
    var nationalField by remember { mutableStateOf(TextFieldValue(value.national, TextRange(value.national.length))) }
    if (nationalField.text != value.national) nationalField = TextFieldValue(value.national, TextRange(value.national.length))

    fun onCodeInput(field: TextFieldValue) {
        val typed = PhoneMask.digits(field.text)
        val codes = viewModel.callingCodes()
        val stillTyping = typed.length <= PhoneInput.MAX_CODE &&
            (typed !in codes || codes.any { it.length > typed.length && it.startsWith(typed) })
        if (stillTyping) {
            codeField = field.copy(text = typed)
            change(current.copy(code = typed, iso = null))
            return
        }
        // Код распознан (или длиннее любого кода): лишние цифры — в начало номера, фокус в номер
        val code = (typed.length downTo 1).map(typed::take).firstOrNull { it in codes } ?: typed.take(PhoneInput.MAX_CODE)
        change(PhoneInput(code, (typed.drop(code.length) + current.national).take(PhoneMask.MAX_DIGITS - code.length)))
        nationalFocus.requestFocus()
    }

    fun onNationalInput(field: TextFieldValue) {
        if (field.text.trimStart().startsWith("+")) {
            // Вставили номер целиком
            val all = PhoneMask.digits(field.text)
            change(PhoneInput.fromDigits(all, viewModel.codeLength(all)))
            return
        }
        val national = PhoneMask.digits(field.text).take(PhoneMask.MAX_DIGITS - current.code.length)
        nationalField = field.copy(text = national, selection = TextRange(minOf(field.selection.end, national.length)))
        change(current.copy(national = national))
    }

    Column(modifier.fillMaxWidth()) {
        if (outerLines) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        // Страна
        Row(
            Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
                .clickable(enabled = enabled) { picking = true }
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                country != null -> listOf(country.flag, country.name).filter(String::isNotEmpty).joinToString(" ")
                value.code.isEmpty() -> stringResource(R.string.phone_country_choose)
                else -> stringResource(R.string.phone_country_invalid)
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = style.copy(color = if (country != null || value.code.isEmpty()) colors.accent else colors.secondaryLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                tint = colors.tertiaryLabel,
            )
        }
        // Разделитель с «хвостиком» над кодом, как в Telegram iOS
        NotchedDivider(notchX = horizontalPadding + CODE_WIDTH / 2, color = colors.separator)
        // +код | номер
        Row(
            Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = codeField,
                onValueChange = ::onCodeInput,
                modifier = Modifier.width(CODE_WIDTH).focusRequester(codeFocus),
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { nationalFocus.requestFocus() }),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("+", style = style)
                        inner()
                    }
                },
            )
            Box(Modifier.padding(end = 12.dp).width(0.5.dp).height(DIVIDER_HEIGHT).background(colors.separator))
            BasicTextField(
                value = nationalField,
                onValueChange = ::onNationalInput,
                modifier = Modifier.weight(1f).focusRequester(nationalFocus).onPreviewKeyEvent { event ->
                    // Backspace в пустом номере: назад в код, стираем его последнюю цифру
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && current.national.isEmpty()) {
                        change(current.copy(code = current.code.dropLast(1), iso = null))
                        codeFocus.requestFocus()
                        true
                    } else {
                        false
                    }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = NationalPhoneTransformation(value.code),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                decorationBox = { inner ->
                    Box {
                        if (value.national.isEmpty()) {
                            Text(
                                PhoneMask.nationalPlaceholder(value.code) ?: stringResource(R.string.auth_phone_label),
                                style = style.copy(color = colors.tertiaryLabel),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (outerLines) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
    }

    if (picking) {
        CountryPicker(
            countries = countries,
            onPick = {
                picking = false
                change(current.copy(code = it.callingCode, iso = it.iso))
                nationalFocus.requestFocus()
            },
            onDismiss = { picking = false },
        )
    }
}

/** Тонкая линия во всю ширину с маленьким уголком вниз в точке [notchX]. */
@Composable
private fun NotchedDivider(notchX: Dp, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(0.5.dp).drawBehind {
            val x = notchX.toPx()
            val half = NOTCH.toPx()
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(x - half, 0f)
                lineTo(x, half)
                lineTo(x + half, 0f)
                lineTo(size.width, 0f)
            }
            drawPath(path, color, style = Stroke(width = 0.5.dp.toPx()))
        },
    )
}

/** Список стран во весь экран: поиск по названию или коду, строка — флаг, название, «+код». */
@Composable
private fun CountryPicker(countries: List<Country>, onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    val colors = ReachTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(countries, query) {
        val q = query.trim().removePrefix("+")
        if (q.isEmpty()) countries
        else countries.filter { it.name.contains(q, ignoreCase = true) || it.callingCode.startsWith(q) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            SheetHeader(title = stringResource(R.string.phone_country_title), cancel = stringResource(R.string.cancel), onCancel = onDismiss)
            SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.iso + it.callingCode }) { country ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(country) }) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(country.flag, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                country.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("+" + country.callingCode, style = MaterialTheme.typography.bodyLarge, color = colors.secondaryLabel)
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = colors.separator)
                    }
                }
            }
        }
    }
}

private val ROW_HEIGHT = 52.dp
private val CODE_WIDTH = 64.dp
private val DIVIDER_HEIGHT = 28.dp
private val NOTCH = 6.dp
