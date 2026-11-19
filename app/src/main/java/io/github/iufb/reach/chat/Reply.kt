package io.github.iufb.reach.chat

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Плашка над полем ввода: на что отвечаем, с кнопкой отмены. */
@Composable
internal fun ReplyBar(message: Message, senders: Map<Sender, SenderInfo>, onCancel: () -> Unit) {
    ComposerContextBar(
        icon = R.drawable.ic_reply,
        title = authorName(MessageAuthor.Known(message.sender), senders),
        text = bodySummary(message.content),
        cancelDescription = stringResource(R.string.reply_cancel),
        onCancel = onCancel,
    )
}

/** Плашка над полем ввода в режиме редактирования. */
@Composable
internal fun EditBar(message: Message, onCancel: () -> Unit) {
    ComposerContextBar(
        icon = R.drawable.ic_edit,
        title = stringResource(R.string.edit_title),
        text = bodySummary(message.content),
        cancelDescription = stringResource(R.string.edit_cancel),
        onCancel = onCancel,
    )
}

@Composable
private fun ComposerContextBar(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    cancelDescription: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        QuoteStrip(title, text, Modifier.weight(1f).padding(start = 12.dp))
        IconButton(onClick = onCancel) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = cancelDescription)
        }
    }
}

/** Полоска слева, имя автора и строка текста — общий вид цитаты. */
@Composable
private fun QuoteStrip(name: String, text: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
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

/** Свайп сообщения влево начинает ответ; [onReply] `null` — свайп выключен. */
@Composable
internal fun Modifier.swipeToReply(onReply: (() -> Unit)?): Modifier {
    if (onReply == null) return this
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    return this
        .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                scope.launch { offset.snapTo((offset.value + delta).coerceIn(-threshold * 1.5f, 0f)) }
            },
            onDragStopped = {
                if (offset.value <= -threshold) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReply()
                }
                offset.animateTo(0f)
            },
        )
        .offset { IntOffset(offset.value.roundToInt(), 0) }
}

private val SWIPE_THRESHOLD = 64.dp
