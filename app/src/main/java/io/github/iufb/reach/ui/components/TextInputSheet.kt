package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Ввод одной строки или короткого текста во весь экран, как правка названия в Telegram iOS:
 * «Отмена» / заголовок / «Сохранить», поле в карточке с фокусом и клавиатурой сразу.
 * «Сохранить» неактивно, пока текст пустой (если [allowEmpty] не задан).
 */
@Composable
fun TextInputSheet(
    title: String,
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = Int.MAX_VALUE,
    singleLine: Boolean = true,
    footer: String? = null,
    allowEmpty: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ReachTheme.colors.groupedBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SheetHeader(
                title = title,
                cancel = stringResource(R.string.cancel),
                onCancel = onDismiss,
                action = stringResource(R.string.save),
                actionEnabled = allowEmpty || text.isNotBlank(),
                onAction = { onSave(text.trim()) },
            )
            GroupedSection(footer = footer) {
                GroupedTextField(
                    text,
                    { text = it.take(maxLength) },
                    placeholder = placeholder,
                    focusRequester = focus,
                    singleLine = singleLine,
                    divider = false,
                )
            }
        }
    }
}
