package io.github.iufb.reach.chats

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.Avatar

/**
 * Что можно сделать с чатом из списка. Пункты добавляются по мере появления функций
 * (архив, mute, «непрочитанный»), каждый — отдельным колбэком.
 */
class ChatMenuActions(
    val onSetPinned: (Chat, Boolean) -> Unit,
    val onSetArchived: (Chat, Boolean) -> Unit,
    val onSetMuteFor: (Chat, Int) -> Unit,
    val onSetUnread: (Chat, Boolean) -> Unit,
)

/** Варианты «без звука»: подпись и срок в секундах. */
internal val MUTE_OPTIONS = listOf(
    R.string.mute_1_hour to 60 * 60,
    R.string.mute_8_hours to 8 * 60 * 60,
    R.string.mute_2_days to 2 * 24 * 60 * 60,
    R.string.mute_forever to MUTE_FOREVER,
)

/** TDLib считает «навсегда» всё, что дольше года. */
internal const val MUTE_FOREVER = Int.MAX_VALUE

/** Меню действий над [chat] в списке [list], открывается долгим тапом. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMenuSheet(chat: Chat, list: ChatListType, actions: ChatMenuActions, onDismiss: () -> Unit) {
    // «Без звука» раскрывается в список сроков прямо в меню
    var muteOptions by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(chat, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    chatTitle(chat),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val pinned = chat.positions[list]?.isPinned == true
            ChatMenuItem(
                if (pinned) stringResource(R.string.chat_action_unpin) else stringResource(R.string.chat_action_pin),
                painterResource(R.drawable.ic_pin),
            ) { actions.onSetPinned(chat, !pinned) }
            val archived = ChatListType.Archive in chat.positions
            ChatMenuItem(
                if (archived) stringResource(R.string.chat_action_unarchive) else stringResource(R.string.chat_action_archive),
                painterResource(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive),
            ) { actions.onSetArchived(chat, !archived) }
            ChatMenuItem(
                stringResource(if (chat.hasUnread) R.string.chat_action_mark_read else R.string.chat_action_mark_unread),
                painterResource(if (chat.hasUnread) R.drawable.ic_mark_read else R.drawable.ic_mark_unread),
            ) { actions.onSetUnread(chat, !chat.hasUnread) }
            // У «Избранного» уведомлений нет — TDLib откажет в смене настроек (#216)
            when {
                chat.isSavedMessages -> Unit
                chat.isMuted -> ChatMenuItem(stringResource(R.string.chat_action_unmute), painterResource(R.drawable.ic_unmute)) {
                    actions.onSetMuteFor(chat, 0)
                }
                !muteOptions -> ChatMenuItem(stringResource(R.string.chat_action_mute), painterResource(R.drawable.ic_muted)) {
                    muteOptions = true
                }
                else -> MUTE_OPTIONS.forEach { (label, seconds) ->
                    ChatMenuItem(stringResource(label), painterResource(R.drawable.ic_muted)) { actions.onSetMuteFor(chat, seconds) }
                }
            }
        }
    }
}

@Composable
internal fun ChatMenuItem(text: String, icon: Painter, color: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
