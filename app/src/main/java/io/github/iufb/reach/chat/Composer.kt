package io.github.iufb.reach.chat

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.chats.LinkPreview
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.media.RecorderState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.glass
import io.github.iufb.reach.ui.theme.ReachTheme

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
    /** Скрепка слева от поля открывает меню вложений ([AttachSheet]); `null` — вложения недоступны. */
    val onAttach: (() -> Unit)? = null,
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

/**
 * Стеклянный круг под кнопкой поля ввода. Стекло — подложкой позади, а не обрезкой:
 * кнопка записи рисует за своими границами (свайпы, индикатор).
 */
@Composable
private fun GlassCircle(glass: Boolean = true, content: @Composable () -> Unit) {
    // Во время записи кнопку накрывает большой круг и уводит за пальцем — пустое стекло под ним прячем
    val glassAlpha by animateFloatAsState(if (glass) 1f else 0f, tween(GLASS_FADE_MS), label = "glassCircle")
    Box(Modifier.size(PillHeight), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha }.glass(CircleShape))
        content()
    }
}

/** Поле — пилюля высотой с кнопки; с плашкой ответа или в несколько строк скругление остаётся тем же. */
private val FIELD_CORNER = PillHeight / 2
private val EXTRAS_CORNER = 20.dp
/** Значки внутри поля (колокольчик, эмодзи). */
private val FIELD_ICON = 36.dp
private val SEND_ICON = 20.dp
private const val BAR_IN_MS = 180
private const val BAR_OUT_MS = 360
/** Ровнее FastOutSlowIn: сжатие пилюли видно на всём пути, а не за первые кадры. */
private val BAR_EASING = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)
/** Доля хода пилюли (с конца), на которой проявляется содержимое поля. */
private const val FIELD_CONTENT_SPAN = 0.35f
/** Содержимое пилюли записи видно только на последнем отрезке её раскрытия. */
private const val BAR_CONTENT_FROM = 0.6f
private const val ATTACH_MIN_SCALE = 0.6f
private const val GLASS_FADE_MS = 150

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Composer(state: ComposerState, senders: Map<Sender, SenderInfo>, emoji: EmojiPanelState) {
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
    val imeVisible = WindowInsets.isImeVisible
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldPressed by fieldInteraction.collectIsPressedAsState()
    // Тап по полю, пока открыта панель, возвращает клавиатуру
    LaunchedEffect(fieldPressed) { if (fieldPressed && emoji.open) emoji.switchToKeyboard() }
    BackHandler(enabled = emoji.open) { emoji.close() }
    // Панель живёт внизу экрана (ChatBottomArea), а вставляет — сюда, на место выделения / курсора
    SideEffect {
        emoji.onPick = { picked ->
            val at = field.selection
            val text = field.text.replaceRange(at.min, at.max, picked)
            field = TextFieldValue(text, TextRange(at.min + picked.length))
            sentTexts.addLast(text)
            if (sentTexts.size > MAX_SENT_TEXTS) sentTexts.removeFirst()
            state.onDraftChange(text)
        }
    }
    // «Ответить» и «Изменить» ставят курсор в поле; восстановленный при открытии чата ответ — нет
    val focusRequester = remember { FocusRequester() }
    var seenFocusRequest by remember { mutableIntStateOf(state.focusRequest) }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest == seenFocusRequest) return@LaunchedEffect
        seenFocusRequest = state.focusRequest
        if (emoji.open) emoji.switchToKeyboard()
        // Во время записи голосового поля нет — фокусировать нечего
        runCatching { focusRequester.requestFocus() }.onSuccess { keyboard?.show() }
    }
    val token = mentionToken(field)
    val mentionQuery = token?.query
    LaunchedEffect(mentionQuery) { state.onMentionQuery(mentionQuery) }
    val richColors = richTextColors()
    val transformation = remember(state.draft, richColors) { RichVisualTransformation(state.draft, richColors) }
    val canSend = state.draft.text.isNotBlank()
    val voice = state.voice?.takeIf { state.editing == null }
    // Микрофон вместо «отправить», пока поле пустое; во время записи и предпрослушивания кнопка
    // записи сама становится «отправить» (большой круг сжимается до обычной кнопки)
    val showMic = voice != null && (voice.state !is RecorderState.Idle || !canSend)
    val recordingUi = rememberRecordingUi()
    // Пилюля записи ложится поверх поля (поле прозрачнеет, но держит место): высота поля ввода,
    // а значит и отступ ленты, при переходах не меняется
    val barTarget = voice != null && (voice.state !is RecorderState.Idle || recordingUi.cancelling)
    val barReveal = remember { Animatable(0f) }
    LaunchedEffect(barTarget) {
        barReveal.animateTo(if (barTarget) 1f else 0f, tween(if (barTarget) BAR_IN_MS else BAR_OUT_MS, easing = BAR_EASING))
    }
    val barShown = barTarget || barReveal.isRunning || barReveal.targetValue > 0f
    val hasAttach = state.editing == null && state.onAttach != null

    // Telegram iOS: под полем нет панели — стеклянные кнопки и поле-пилюля плавают над лентой.
    // Ответ, правка и превью ссылки — внутри поля, подсказки и оформление — стеклянной карточкой над ним.
    val selection = field.selection
    val showMentions = token != null && state.mentionSuggestions.isNotEmpty()
    val colors = ReachTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (showMentions || !selection.collapsed) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(RoundedCornerShape(EXTRAS_CORNER))) {
                    if (showMentions && token != null) {
                        MentionSuggestions(state.mentionSuggestions) { state.onPickMention(it, token.start, token.end) }
                    }
                    if (!selection.collapsed) {
                        FormatBar(
                            onFormat = { kind -> state.onFormat(kind, selection.min, selection.max) },
                            onLink = { linkFor = selection },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    // Рамка пилюли записи: тянется влево поверх скрепки и обратно встаёт ровно на место
                    // поля (та же высота и скругление) (#384)
                    val fieldStart = if (hasAttach) PillHeight + 6.dp else 6.dp
                    val barFrame = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(PillHeight)
                        .padding(end = 6.dp)
                        .layout { measurable, constraints ->
                            val inset = (fieldStart.toPx() * (1f - barReveal.value)).roundToInt()
                            val width = (constraints.maxWidth - inset).coerceAtLeast(0)
                            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                            layout(constraints.maxWidth, placeable.height) { placeable.place(inset, 0) }
                        }
                    // Стекло пилюли — под полем: содержимое поля проявляется поверх него, а не из-под
                    if (barShown && voice != null) Box(barFrame.glass(RoundedCornerShape(percent = 50)))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        if (hasAttach && state.onAttach != null) {
                            // Пилюля записи наезжает на скрепку: та гаснет и сжимается под ней
                            Box(
                                Modifier.graphicsLayer {
                                    val k = 1f - barReveal.value
                                    alpha = k
                                    scaleX = ATTACH_MIN_SCALE + (1f - ATTACH_MIN_SCALE) * k
                                    scaleY = scaleX
                                },
                            ) {
                                GlassCircle { AttachButton(state.onAttach) }
                            }
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                // Пока видна пилюля записи, стекло — только у неё: два слоя дали бы плотнее
                                .then(if (barShown) Modifier else Modifier.glass(RoundedCornerShape(FIELD_CORNER))),
                        ) {
                            when {
                                state.editing != null -> EditBar(state.editing, state.onCancelEdit)
                                state.replyTo != null -> ReplyBar(state.replyTo, senders, state.onCancelReply)
                            }
                            state.linkPreview?.let { ComposerLinkPreview(it, state.onDismissLinkPreview) }
                            Row(
                                // Содержимое поля проявляется на последней трети сжатия пилюли — уже поверх неё (#384)
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = PillHeight)
                                    .graphicsLayer { alpha = (1f - barReveal.value / FIELD_CONTENT_SPAN).coerceIn(0f, 1f) },
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                BasicTextField(
                                    value = field,
                                    onValueChange = {
                                        if (it.text != field.text) {
                                            sentTexts.addLast(it.text)
                                            if (sentTexts.size > MAX_SENT_TEXTS) sentTexts.removeFirst()
                                            state.onDraftChange(it.text)
                                        }
                                        field = it
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                        .padding(start = 16.dp, end = 4.dp, top = FIELD_TEXT_PADDING, bottom = FIELD_TEXT_PADDING),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.label),
                                    cursorBrush = SolidColor(colors.accent),
                                    maxLines = 6,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    visualTransformation = transformation,
                                    interactionSource = fieldInteraction,
                                    decorationBox = { inner ->
                                        Box {
                                            if (field.text.isEmpty()) {
                                                Text(
                                                    stringResource(R.string.chat_input_placeholder),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = colors.tertiaryLabel,
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                                Row(Modifier.padding(end = 4.dp, bottom = (PillHeight - FIELD_ICON) / 2)) {
                                    state.silent?.let { silent -> SilentToggle(silent, state.onToggleSilent) }
                                    EmojiToggle(emoji.open, stickers = emoji.tab == PanelTab.Stickers) {
                                        if (emoji.open) {
                                            emoji.switchToKeyboard()
                                            runCatching { focusRequester.requestFocus() }
                                            keyboard?.show()
                                        } else {
                                            emoji.show(keyboardVisible = imeVisible)
                                            keyboard?.hide()
                                        }
                                    }
                                }
                            }
                            state.error?.let {
                                Text(
                                    stringResource(R.string.chat_send_error, it),
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.destructive,
                                )
                            }
                        }
                    }
                    if (barShown && voice != null) {
                        // Содержимое пилюли и блок касаний — поверх поля; гаснет в начале сжатия
                        RecordingBar(
                            voice,
                            recordingUi,
                            barFrame,
                            drawGlass = false,
                            contentAlpha = { ((barReveal.value - BAR_CONTENT_FROM) / (1f - BAR_CONTENT_FROM)).coerceIn(0f, 1f) },
                        )
                    }
                }
                if (showMic) {
                    GlassCircle(glass = voice!!.state !is RecorderState.Recording) { MicButton(voice, recordingUi) }
                } else {
                    SendButton(
                        enabled = canSend,
                        onClick = state.onSend,
                        // Меню — только для набранного текста, не для правки
                        menu = state.sendMenu?.takeIf { state.editing == null && canSend },
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

/** Отступ текста в поле: строка bodyLarge плюс два отступа — ровно [PillHeight]. */
private val FIELD_TEXT_PADDING = 11.dp

/** «Отправить»: тап — отправка, долгое нажатие — меню вариантов ([SendMenu]). */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit, menu: SendMenu?) {
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.chat_send)
    val optionsLabel = stringResource(R.string.send_options)
    val colors = ReachTheme.colors
    Box {
        Box(
            modifier = Modifier
                .size(PillHeight)
                .clip(CircleShape)
                .background(if (enabled) colors.accent else colors.fill)
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
                modifier = Modifier.size(SEND_ICON),
                tint = if (enabled) colors.onAccent else colors.tertiaryLabel,
            )
        }
    }
    if (menu != null && open) {
        // Как в Telegram iOS: долгий тап по «Отправить» — лист вариантов отправки
        ActionSheet(
            actions = buildList {
                add(SheetAction(stringResource(R.string.send_silent), onClick = menu.onSilent))
                menu.onSchedule?.let { schedule ->
                    add(SheetAction(stringResource(R.string.send_scheduled)) { picking = true })
                    if (menu.whenOnline) add(SheetAction(stringResource(R.string.send_when_online)) { schedule(Schedule.WhenOnline) })
                }
            },
            onDismiss = { open = false },
        )
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

/** Значок внутри поля: эмодзи, клавиатура, колокольчик. */
@Composable
private fun FieldIcon(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(FIELD_ICON)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = ReachTheme.colors.secondaryLabel)
    }
}

/**
 * Смайлик (или стикер, если в прошлый раз открывали стикеры) открывает панель, клавиатура — возвращает её.
 */
@Composable
private fun EmojiToggle(open: Boolean, stickers: Boolean, onClick: () -> Unit) {
    FieldIcon(
        when {
            open -> R.drawable.ic_keyboard
            stickers -> R.drawable.ic_sticker
            else -> R.drawable.ic_emoji
        },
        stringResource(
            when {
                open -> R.string.emoji_keyboard
                stickers -> R.string.stickers_open
                else -> R.string.emoji_open
            },
        ),
        onClick,
    )
}

/** Колокольчик: перечёркнут — сообщения уходят без звука. */
@Composable
private fun SilentToggle(silent: Boolean, onToggle: () -> Unit) {
    FieldIcon(
        if (silent) R.drawable.ic_muted else R.drawable.ic_unmute,
        stringResource(if (silent) R.string.send_silent_on else R.string.send_silent_off),
        onToggle,
    )
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
                        color = ReachTheme.colors.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Скрепка: открывает меню вложений. */
@Composable
private fun AttachButton(onClick: () -> Unit) {
    val description = stringResource(R.string.attach)
    Box(
        Modifier
            .size(PillHeight)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_attach), contentDescription = null, Modifier.size(24.dp), tint = ReachTheme.colors.label)
    }
}

/** Кнопки оформления над полем, пока в нём выделен текст. */
@Composable
private fun FormatBar(onFormat: (SpanKind) -> Unit, onLink: () -> Unit) {
    // Семь кнопок делят ширину поровну — ни одна не уходит за край (#196)
    Row(Modifier.fillMaxWidth().height(PillHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FormatButton("B", R.string.format_bold, fontWeight = FontWeight.Bold) { onFormat(SpanKind.Bold) }
        FormatButton("I", R.string.format_italic, fontStyle = FontStyle.Italic) { onFormat(SpanKind.Italic) }
        FormatButton("U", R.string.format_underline, decoration = TextDecoration.Underline) { onFormat(SpanKind.Underline) }
        FormatButton("S", R.string.format_strikethrough, decoration = TextDecoration.LineThrough) {
            onFormat(SpanKind.Strikethrough)
        }
        FormatButton("</>", R.string.format_code, fontFamily = FontFamily.Monospace) { onFormat(SpanKind.Code) }
        FormatButton("▒", R.string.format_spoiler) { onFormat(SpanKind.Spoiler) }
        val linkDescription = stringResource(R.string.format_link)
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(role = Role.Button, onClick = onLink)
                .semantics { contentDescription = linkDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_link), contentDescription = null, Modifier.size(22.dp), tint = ReachTheme.colors.label)
        }
    }
}

@Composable
private fun RowScope.FormatButton(
    label: String,
    @StringRes description: Int,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    decoration: TextDecoration? = null,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    val descriptionText = stringResource(description)
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = descriptionText },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = decoration,
            fontFamily = fontFamily,
            style = MaterialTheme.typography.titleMedium,
            color = ReachTheme.colors.label,
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
