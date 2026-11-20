package io.github.iufb.reach.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.Avatar

/** Название чата для показа: «Избранное» для чата с собой, заглушка для удалённого аккаунта. */
@Composable
fun chatTitle(chat: Chat): String = when {
    chat.isSavedMessages -> stringResource(R.string.saved_messages)
    else -> chat.title.ifBlank { stringResource(R.string.chat_deleted_account) }
}

/** Аватарка чата; у Избранного — своя иконка вместо фото. */
@Composable
fun ChatAvatar(chat: Chat, size: Dp, modifier: Modifier = Modifier) {
    if (chat.isSavedMessages) {
        Box(
            modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_bookmark),
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    } else {
        Avatar(chat.title, chat.photo, chat.accentColorId, modifier, size)
    }
}

