package io.github.iufb.reach.chat

import android.content.ClipData
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Что можно сделать с сообщением. Права берём из `getMessageProperties`,
 * чтобы не показывать пункты, которые TDLib всё равно отклонит.
 */
data class MessageActions(
    /** Текст или подпись, которые можно скопировать; `null` — копировать нечего или нельзя. */
    val copyText: String?,
    val canReply: Boolean,
) {
    val isEmpty: Boolean get() = copyText == null && !canReply
}

/** Открытое меню действий над [message]. */
data class MessageMenu(val message: Message, val actions: MessageActions)

/** Текст, который копируется из сообщения: сам текст или подпись к медиа. */
internal fun MessageBody.copyableText(): String? = when (this) {
    is MessageBody.Text -> text.text
    is MessageBody.Unsupported -> caption.text.ifBlank { null }
    is MessageBody.Service -> null
}

/** [properties] `null` — запрос не удался: показываем только то, что не требует прав сервера. */
internal fun messageActions(message: Message, properties: TdApi.MessageProperties?): MessageActions {
    // Защищённый контент (запрет копирования в чате) копировать нельзя
    val protected = properties != null &&
        (properties.hasProtectedContentByCurrentUser || properties.hasProtectedContentByOtherUser)
    return MessageActions(
        copyText = message.content.copyableText().takeUnless { protected },
        canReply = properties?.canBeReplied == true,
    )
}

/**
 * Меню у пузыря. Ставится внутрь пузыря: `DropdownMenu` привязывается к родителю.
 * [menu] не `null` — меню открыто для этого сообщения.
 */
@Composable
internal fun MessageMenuPopup(menu: MessageMenu?, onDismiss: () -> Unit, onReply: (Message) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = menu != null, onDismissRequest = onDismiss) {
        val current = menu ?: return@DropdownMenu
        val actions = current.actions
        if (actions.canReply) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_action_reply)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_reply), contentDescription = null) },
                onClick = { onReply(current.message) },
            )
        }
        actions.copyText?.let { text ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_action_copy)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                onClick = {
                    // Android 13+ сам показывает подтверждение копирования
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                    onDismiss()
                },
            )
        }
    }
}
