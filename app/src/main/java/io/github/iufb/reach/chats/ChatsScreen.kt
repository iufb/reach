package io.github.iufb.reach.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun ChatsScreen(onChatClick: (Long) -> Unit, viewModel: ChatsViewModel = hiltViewModel()) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val allLoaded by viewModel.allLoaded.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    ChatsContent(chats, allLoaded, loggingOut, onChatClick, viewModel::loadMore, viewModel::logOut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsContent(
    chats: List<Chat>?,
    allLoaded: Boolean,
    loggingOut: Boolean,
    onChatClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onLogOut: () -> Unit,
) {
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats_title)) },
                actions = {
                    TextButton(onClick = { confirmLogOut = true }, enabled = !loggingOut) {
                        Text(stringResource(R.string.logout))
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when {
            loggingOut || chats == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            chats.isEmpty() && allLoaded -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            else -> ChatList(chats, allLoaded, onChatClick, onLoadMore, modifier)
        }
    }
    if (confirmLogOut) {
        LogOutDialog(
            onConfirm = {
                confirmLogOut = false
                onLogOut()
            },
            onDismiss = { confirmLogOut = false },
        )
    }
}

@Composable
private fun ChatList(
    chats: List<Chat>,
    allLoaded: Boolean,
    onChatClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = !allLoaded, onLoadMore)
    LazyColumn(state = listState, modifier = modifier) {
        items(chats, key = { it.id }) { chat ->
            ChatRow(chat, onClick = { onChatClick(chat.id) })
        }
        if (!allLoaded) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Подгрузка, когда до конца списка осталось меньше [threshold] элементов. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, enabled: Boolean, onLoadMore: () -> Unit, threshold: Int = 10) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount to (last >= info.totalItemsCount - threshold)
        }
            .distinctUntilChanged()
            .filter { (_, nearEnd) -> nearEnd }
            .collect { load() }
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title.ifBlank { stringResource(R.string.chat_deleted_account) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                chat.lastMessage?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatChatTime(it.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                chat.lastMessage?.let { previewText(it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun previewText(message: LastMessage): String {
    val text = when (val content = message.content) {
        is MessagePreview.Text -> content.text
        is MessagePreview.Media -> listOf(mediaKindText(content.kind), content.caption)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
    // Переносы строк в превью не нужны
    val singleLine = text.replace('\n', ' ')
    return if (message.isOutgoing) stringResource(R.string.chat_preview_you, singleLine) else singleLine
}

@Composable
internal fun mediaKindText(kind: MediaKind): String = stringResource(
    when (kind) {
        MediaKind.Photo -> R.string.media_photo
        MediaKind.Video -> R.string.media_video
        MediaKind.Document -> R.string.media_document
        MediaKind.Audio -> R.string.media_audio
        MediaKind.VoiceNote -> R.string.media_voice_note
        MediaKind.VideoNote -> R.string.media_video_note
        MediaKind.Animation -> R.string.media_animation
        MediaKind.Sticker -> R.string.media_sticker
        MediaKind.Poll -> R.string.media_poll
        MediaKind.Location -> R.string.media_location
        MediaKind.Contact -> R.string.media_contact
        MediaKind.Call -> R.string.media_call
        MediaKind.Service -> R.string.media_service
        MediaKind.Other -> R.string.media_other
    },
)

@Composable
private fun LogOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = { Text(stringResource(R.string.logout_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Preview
@Composable
private fun ChatsPreview() {
    val now = (System.currentTimeMillis() / 1000).toInt()
    val chats = listOf(
        Chat(1, ChatKind.Private, "Аня", LastMessage(1, now, false, MessagePreview.Text("Привет! 👋")), emptyMap(), 2),
        Chat(2, ChatKind.Group, "Семья", LastMessage(2, now - 86_400, true, MessagePreview.Media(MediaKind.Photo, "Море")), emptyMap(), 0),
        Chat(3, ChatKind.Channel, "Новости", LastMessage(3, now - 30 * 86_400, false, MessagePreview.Media(MediaKind.Poll, "Что дальше?")), emptyMap(), 0),
    )
    ReachTheme { ChatsContent(chats, allLoaded = false, loggingOut = false, onChatClick = {}, onLoadMore = {}, onLogOut = {}) }
}
