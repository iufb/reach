package io.github.iufb.reach.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.iufb.reach.emoji.EmojiPanel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.iufb.reach.chats.LinkPreview
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.media.RecorderState

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
    /** Счётчик просьб поставить курсор в поле и открыть клавиатуру; растёт при «Ответить» и «Изменить». */
    val focusRequest: Int = 0,
    /** Участники по набранному «@…» (только в группах). */
    val mentionSuggestions: List<MentionCandidate> = emptyList(),
    val onMentionQuery: (String?) -> Unit = {},
    /** Выбор подсказки: заменить участок [start]..[end] («@запрос») упоминанием. */
    val onPickMention: (MentionCandidate, start: Int, end: Int) -> Unit = { _, _, _ -> },
    /** Превью ссылки из набранного; `null` — ссылки нет или превью убрали. */
    val linkPreview: LinkPreview? = null,
    val onDismissLinkPreview: () -> Unit = {},
    /** Скрепка слева от поля: фото или файл; `null` — вложения недоступны. */
    val onAttachPhoto: (() -> Unit)? = null,
    val onAttachFile: (() -> Unit)? = null,
    /** «Опрос» в меню скрепки; `null` — опросы здесь недоступны (личка). */
    val onAttachPoll: (() -> Unit)? = null,
    /** Голосовые; `null` — записывать нельзя. */
    val voice: VoiceInput? = null,
    /** Меню по долгому нажатию на «отправить»; `null` — меню нет. */
    val sendMenu: SendMenu? = null,
    /** Колокольчик «без звука по умолчанию» (каналы); `null` — не показываем. */
    val silent: Boolean? = null,
    val onToggleSilent: () -> Unit = {},
)

/** Варианты отправки из меню кнопки «отправить». */
internal class SendMenu(
    val onSilent: () -> Unit,
    /** «Отправить позже»; `null` — недоступно. */
    val onSchedule: ((Schedule) -> Unit)? = null,
    /** Показывать «Когда будет в сети». */
    val whenOnline: Boolean = false,
)

/** Сколько последних отданных модели текстов помнит поле, см. [Composer]. */
private const val MAX_SENT_TEXTS = 64

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
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue(state.draft.text, TextRange(state.draft.text.length))) }
    // Тексты, отданные модели из поля, но ещё не вернувшиеся обратно. Модель отстаёт от поля на кадр-другой:
    // при быстром вводе в композицию приходит уже устаревший черновик, и перезапись им стёрла бы набранное (#211).
    // Внешними считаем только тексты, которых поле не отдавало: упоминание, очистка после отправки, правка.
    val sentTexts = remember { ArrayDeque<String>() }
    LaunchedEffect(state.draft.text) {
        val text = state.draft.text
        when {
            text == field.text -> sentTexts.clear()
            text in sentTexts -> Unit
            else -> {
                sentTexts.clear()
                field = TextFieldValue(text, TextRange(text.length))
            }
        }
    }
    var linkFor by remember { mutableStateOf<TextRange?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldPressed by fieldInteraction.collectIsPressedAsState()
    // Тап по полю, пока открыта панель, возвращает клавиатуру
    LaunchedEffect(fieldPressed) { if (fieldPressed) emojiOpen = false }
    // «Ответить» и «Изменить» ставят курсор в поле; восстановленный при открытии чата ответ — нет
    val focusRequester = remember { FocusRequester() }
    var seenFocusRequest by remember { mutableIntStateOf(state.focusRequest) }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest == seenFocusRequest) return@LaunchedEffect
        seenFocusRequest = state.focusRequest
        emojiOpen = false
        // Во время записи голосового поля нет — фокусировать нечего
        runCatching { focusRequester.requestFocus() }.onSuccess { keyboard?.show() }
    }
    val token = mentionToken(field)
    val mentionQuery = token?.query
    LaunchedEffect(mentionQuery) { state.onMentionQuery(mentionQuery) }
    val colors = richTextColors()
    val transformation = remember(state.draft, colors) { RichVisualTransformation(state.draft, colors) }
    val canSend = state.draft.text.isNotBlank()
    val voice = state.voice?.takeIf { state.editing == null }
    val recording = voice != null && voice.state !is RecorderState.Idle
    // Микрофон вместо «отправить», пока поле пустое и запись не зафиксирована
    val showMic = voice != null && !canSend &&
        (voice.state is RecorderState.Idle || (voice.state as? RecorderState.Recording)?.locked == false)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            when {
                state.editing != null -> EditBar(state.editing, state.onCancelEdit)
                state.replyTo != null -> ReplyBar(state.replyTo, senders, state.onCancelReply)
            }
            state.linkPreview?.let { ComposerLinkPreview(it, state.onDismissLinkPreview) }
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
                if (recording) {
                    RecordingBar(voice!!, Modifier.weight(1f))
                } else {
                    if (state.editing == null && (state.onAttachPhoto != null || state.onAttachFile != null)) {
                        AttachButton(state.onAttachPhoto, state.onAttachFile, state.onAttachPoll)
                    }
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
                            if (it.text != field.text) {
                                sentTexts.addLast(it.text)
                                if (sentTexts.size > MAX_SENT_TEXTS) sentTexts.removeFirst()
                                state.onDraftChange(it.text)
                            }
                            field = it
                        },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(stringResource(R.string.chat_send_error, it)) } },
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        visualTransformation = transformation,
                        interactionSource = fieldInteraction,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                state.silent?.let { silent -> SilentToggle(silent, state.onToggleSilent) }
                                EmojiToggle(emojiOpen) {
                                    if (emojiOpen) keyboard?.show() else keyboard?.hide()
                                    emojiOpen = !emojiOpen
                                }
                            }
                        },
                    )
                }
                if (showMic) {
                    MicButton(voice!!)
                } else {
                    val sendVoice = recording
                    SendButton(
                        enabled = canSend || sendVoice,
                        onClick = if (sendVoice) voice!!.onSend else state.onSend,
                        // Меню — только для набранного текста, не для правки и не для голосового
                        menu = state.sendMenu?.takeIf { !sendVoice && state.editing == null && canSend },
                    )
                }
            }
            if (emojiOpen) {
                EmojiPanel(onPick = { emoji ->
                    // Вставка на место выделения / курсора
                    val selection = field.selection
                    val text = field.text.replaceRange(selection.min, selection.max, emoji)
                    field = TextFieldValue(text, TextRange(selection.min + emoji.length))
                    sentTexts.addLast(text)
                    if (sentTexts.size > MAX_SENT_TEXTS) sentTexts.removeFirst()
                    state.onDraftChange(text)
                })
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

/** «Отправить»: тап — отправка, долгое нажатие — меню вариантов ([SendMenu]). */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit, menu: SendMenu?) {
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.chat_send)
    val optionsLabel = stringResource(R.string.send_options)
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClickLabel = if (menu != null) optionsLabel else null,
                    onClick = onClick,
                    onLongClick = menu?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            open = true
                        }
                    },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_send),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menu != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.send_silent)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_muted), contentDescription = null) },
                    onClick = {
                        open = false
                        menu.onSilent()
                    },
                )
                menu.onSchedule?.let { schedule ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.send_scheduled)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) },
                        onClick = {
                            open = false
                            picking = true
                        },
                    )
                    if (menu.whenOnline) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.send_when_online)) },
                            onClick = {
                                open = false
                                schedule(Schedule.WhenOnline)
                            },
                        )
                    }
                }
            }
        }
    }
    val schedule = menu?.onSchedule
    if (picking && schedule != null) {
        ScheduleDialog(
            initial = null,
            onPick = { date ->
                picking = false
                schedule(Schedule.At(date))
            },
            onDismiss = { picking = false },
        )
    }
}

/** ☺ открывает панель эмодзи, ⌨ — возвращает клавиатуру. */
@Composable
private fun EmojiToggle(open: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        val description = stringResource(if (open) R.string.emoji_keyboard else R.string.emoji_open)
        Text(
            if (open) "⌨" else "☺",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Колокольчик: перечёркнут — сообщения уходят без звука. */
@Composable
private fun SilentToggle(silent: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            painterResource(if (silent) R.drawable.ic_muted else R.drawable.ic_unmute),
            contentDescription = stringResource(if (silent) R.string.send_silent_on else R.string.send_silent_off),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Скрепка с выбором: фото из галереи или любой файл. */
@Composable
private fun AttachButton(onPhoto: (() -> Unit)?, onFile: (() -> Unit)?, onPoll: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_attach),
                contentDescription = stringResource(R.string.attach),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onPhoto?.let { pick ->
                DropdownMenuItem(text = { Text(stringResource(R.string.attach_photo)) }, onClick = { open = false; pick() })
            }
            onFile?.let { pick ->
                DropdownMenuItem(text = { Text(stringResource(R.string.attach_file)) }, onClick = { open = false; pick() })
            }
            onPoll?.let { create ->
                DropdownMenuItem(text = { Text(stringResource(R.string.attach_poll)) }, onClick = { open = false; create() })
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
        FormatButton("▒", R.string.format_spoiler) { onFormat(SpanKind.Spoiler) }
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
        val styled = if (text.text == rich.text) rich.toAnnotatedString(colors, clickableLinks = false, spoilers = Spoilers.Marked) else text
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is RichVisualTransformation && other.rich == rich && other.colors == colors

    override fun hashCode(): Int = rich.hashCode() * 31 + colors.hashCode()
}
