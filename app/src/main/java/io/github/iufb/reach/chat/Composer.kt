package io.github.iufb.reach.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import io.github.iufb.reach.ui.components.Avatar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind

/** Поле ввода; `null` в [ChatContent] — писать нельзя, поле скрыто. */
internal class ComposerState(
    /** Набранное с оформлением; оформление живёт в модели, поле его только показывает. */
    val draft: RichText,
    val error: String?,
    /** Сообщение, на которое отвечаем. */
    val replyTo: Message?,
    /** Своё сообщение, которое редактируем; плашка редактирования важнее плашки ответа. */
    val editing: Message?,
    val onDraftChange: (String) -> Unit,
    /** Оформить выделенный участок (повторно — снять). */
    val onFormat: (SpanKind, start: Int, end: Int) -> Unit,
    val onSend: () -> Unit,
    val onCancelReply: () -> Unit,
    val onCancelEdit: () -> Unit,
    /** Участники по набранному «@…» (только в группах). */
    val mentionSuggestions: List<MentionCandidate> = emptyList(),
    val onMentionQuery: (String?) -> Unit = {},
    /** Выбор подсказки: заменить участок [start]..[end] («@запрос») упоминанием. */
    val onPickMention: (MentionCandidate, start: Int, end: Int) -> Unit = { _, _, _ -> },
)

/** «@запрос» перед курсором: участок от «@» до курсора и сам запрос. */
private data class MentionToken(val start: Int, val end: Int, val query: String)

private fun mentionToken(field: TextFieldValue): MentionToken? {
    if (!field.selection.collapsed) return null
    val cursor = field.selection.start
    val before = field.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    // «@» в начале слова, после него — без пробелов
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    val query = before.substring(at + 1)
    if (query.any { it.isWhitespace() }) return null
    return MentionToken(at, cursor, query)
}

@Composable
internal fun Composer(state: ComposerState, senders: Map<Sender, SenderInfo>) {
    // Поле держит своё TextFieldValue (выделение, composing-регион клавиатуры), текст сверяется с моделью
    var field by remember { mutableStateOf(TextFieldValue(state.draft.text, TextRange(state.draft.text.length))) }
    LaunchedEffect(state.draft.text) {
        if (field.text != state.draft.text) field = TextFieldValue(state.draft.text, TextRange(state.draft.text.length))
    }
    var linkFor by remember { mutableStateOf<TextRange?>(null) }
    val token = mentionToken(field)
    val mentionQuery = token?.query
    LaunchedEffect(mentionQuery) { state.onMentionQuery(mentionQuery) }
    val colors = richTextColors()
    val transformation = remember(state.draft, colors) { RichVisualTransformation(state.draft, colors) }
    val canSend = state.draft.text.isNotBlank()

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            when {
                state.editing != null -> EditBar(state.editing, state.onCancelEdit)
                state.replyTo != null -> ReplyBar(state.replyTo, senders, state.onCancelReply)
            }
            if (token != null && state.mentionSuggestions.isNotEmpty()) {
                MentionSuggestions(state.mentionSuggestions) { state.onPickMention(it, token.start, token.end) }
            }
            val selection = field.selection
            if (!selection.collapsed) {
                FormatBar(
                    onFormat = { kind -> state.onFormat(kind, selection.min, selection.max) },
                    onLink = { linkFor = selection },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != state.draft.text) state.onDraftChange(it.text)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    isError = state.error != null,
                    supportingText = state.error?.let { { Text(stringResource(R.string.chat_send_error, it)) } },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    visualTransformation = transformation,
                )
                IconButton(onClick = state.onSend, enabled = canSend) {
                    Icon(
                        painterResource(R.drawable.ic_send),
                        contentDescription = stringResource(R.string.chat_send),
                        tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    linkFor?.let { range ->
        LinkDialog(
            onConfirm = { url ->
                state.onFormat(SpanKind.Link(url), range.min, range.max)
                linkFor = null
            },
            onDismiss = { linkFor = null },
        )
    }
}

/** Список участников над полем; не выше нескольких строк, дальше прокрутка. */
@Composable
private fun MentionSuggestions(candidates: List<MentionCandidate>, onPick: (MentionCandidate) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        items(candidates, key = { it.userId }) { candidate ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(candidate) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(candidate.name, candidate.photo, candidate.accentColorId, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    candidate.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (candidate.username.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "@${candidate.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Кнопки оформления над полем, пока в нём выделен текст. */
@Composable
private fun FormatBar(onFormat: (SpanKind) -> Unit, onLink: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        FormatButton("B", R.string.format_bold, fontWeight = FontWeight.Bold) { onFormat(SpanKind.Bold) }
        FormatButton("I", R.string.format_italic, fontStyle = FontStyle.Italic) { onFormat(SpanKind.Italic) }
        FormatButton("U", R.string.format_underline, decoration = TextDecoration.Underline) { onFormat(SpanKind.Underline) }
        FormatButton("S", R.string.format_strikethrough, decoration = TextDecoration.LineThrough) {
            onFormat(SpanKind.Strikethrough)
        }
        FormatButton("</>", R.string.format_code, fontFamily = FontFamily.Monospace) { onFormat(SpanKind.Code) }
        IconButton(onClick = onLink) {
            Icon(painterResource(R.drawable.ic_link), contentDescription = stringResource(R.string.format_link))
        }
    }
}

@Composable
private fun FormatButton(
    label: String,
    @StringRes description: Int,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    decoration: TextDecoration? = null,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    val descriptionText = stringResource(description)
    TextButton(onClick = onClick) {
        Text(
            label,
            modifier = Modifier.semantics { contentDescription = descriptionText },
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = decoration,
            fontFamily = fontFamily,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LinkDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val valid = url.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.format_link_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.format_link_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val trimmed = url.trim()
                onConfirm(if ("://" in trimmed || trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")) trimmed else "https://$trimmed")
            }) { Text(stringResource(R.string.format_link_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Показывает оформление модели прямо в поле. Текст не меняется, поэтому смещения тождественные.
 * Если текст поля уже ушёл вперёд модели (модель ещё не обновилась), рисуем без оформления.
 */
private class RichVisualTransformation(private val rich: RichText, private val colors: RichTextColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = if (text.text == rich.text) rich.toAnnotatedString(colors, clickableLinks = false) else text
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is RichVisualTransformation && other.rich == rich && other.colors == colors

    override fun hashCode(): Int = rich.hashCode() * 31 + colors.hashCode()
}
