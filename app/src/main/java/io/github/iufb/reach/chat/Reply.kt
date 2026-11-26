package io.github.iufb.reach.chat

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.bodySummary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Имя автора для плашки ответа; пустое имя — удалённый аккаунт. */
@Composable
private fun authorName(author: MessageAuthor?, senders: Map<Sender, SenderInfo>): String = when (author) {
    is MessageAuthor.Known -> senders[author.sender]?.name.orEmpty().ifBlank { stringResource(R.string.chat_deleted_account) }
    is MessageAuthor.Hidden -> author.name
    null -> ""
}

/**
 * Блок «ответ на…» в пузыре. [original] — исходное сообщение этого чата, если оно найдено;
 * [resolved] `false` — ещё ищем, `true` и `original == null` — исходное удалено.
 */
@Composable
internal fun ReplyBlock(
    reply: ReplyTo,
    original: Message?,
    resolved: Boolean,
    senders: Map<Sender, SenderInfo>,
    onClick: () -> Unit,
) {
    val author = original?.let { MessageAuthor.Known(it.sender) } ?: reply.origin
    val text = when {
        reply.quote != null -> reply.quote.text
        original != null -> bodySummary(original.content)
        reply.content != null -> bodySummary(reply.content)
        resolved -> stringResource(R.string.reply_unavailable)
        else -> ""
    }
    QuoteStrip(authorName(author, senders), text, Modifier.padding(bottom = 4.dp).clickable(onClick = onClick))
}

/** Плашка в поле ввода: на что отвечаем, с кнопкой отмены. */
@Composable
internal fun ReplyBar(message: Message, senders: Map<Sender, SenderInfo>, onCancel: () -> Unit) {
    ComposerContextBar(
        title = stringResource(R.string.reply_to, authorName(MessageAuthor.Known(message.sender), senders)),
        text = bodySummary(message.content),
        cancelDescription = stringResource(R.string.reply_cancel),
        onCancel = onCancel,
    )
}

/** Плашка в поле ввода в режиме редактирования. */
@Composable
internal fun EditBar(message: Message, onCancel: () -> Unit) {
    ComposerContextBar(
        title = stringResource(R.string.edit_title),
        text = bodySummary(message.content),
        cancelDescription = stringResource(R.string.edit_cancel),
        onCancel = onCancel,
    )
}

/**
 * Telegram iOS: ответ, правка и превью ссылки — цитатой в верхней части поля ввода,
 * крестик справа.
 */
@Composable
internal fun ComposerContextBar(
    title: String,
    text: String,
    cancelDescription: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuoteStrip(title, text, Modifier.weight(1f))
        Box(
            Modifier
                .size(CANCEL_SIZE)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = cancelDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, Modifier.size(18.dp), tint = ReachTheme.colors.secondaryLabel)
        }
    }
}

private val CANCEL_SIZE = 40.dp

/** Полоска слева, имя автора и строка текста — общий вид цитаты. */
@Composable
private fun QuoteStrip(name: String, text: String, modifier: Modifier = Modifier) {
    val accent = bubbleStyle().accent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.1f))
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (name.isNotEmpty()) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Свайп сообщения влево начинает ответ; [onReply] `null` — свайп выключен.
 * Смещение пишется синхронно в каждой дельте: поздняя дельта не может перебить возврат на место (#424).
 */
@Composable
internal fun Modifier.swipeToReply(onReply: (() -> Unit)?): Modifier {
    if (onReply == null) return this
    val offset = remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    return this
        .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                offset.floatValue = (offset.floatValue + delta).coerceIn(-threshold * 1.5f, 0f)
            },
            onDragStarted = { settle?.cancel() },
            onDragStopped = {
                if (offset.floatValue <= -threshold) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReply()
                }
                // В своём скоупе: возврат доигрывает, даже если жест или узел draggable перезапустились
                settle?.cancel()
                settle = scope.launch { animate(offset.floatValue, 0f) { value, _ -> offset.floatValue = value } }
            },
        )
        .offset { IntOffset(offset.floatValue.roundToInt(), 0) }
}

private val SWIPE_THRESHOLD = 64.dp
