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
)

/** Меню действий над [chat] в списке [list], открывается долгим тапом. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMenuSheet(chat: Chat, list: ChatListType, actions: ChatMenuActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(chat.title, chat.photo, chat.accentColorId, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    chat.title.ifBlank { stringResource(R.string.chat_deleted_account) },
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
