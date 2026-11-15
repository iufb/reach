package io.github.iufb.reach.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.MessagePreview
import io.github.iufb.reach.chats.mediaKindText
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val senders by viewModel.senders.collectAsStateWithLifecycle()
    ChatContent(chat, state, senders, onBack, viewModel::loadOlder, viewModel::loadNewer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    chat: Chat?,
    state: ChatHistory.State,
    senders: Map<Sender, SenderInfo>,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { if (chat != null) ChatHeader(chat) },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (!state.opened || chat == null) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            MessageList(chat, state, senders, onLoadOlder, onLoadNewer, modifier)
        }
    }
}

@Composable
private fun ChatHeader(chat: Chat) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(chat.title, chat.photo, chat.accentColorId, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    when (chat.kind) {
                        ChatKind.Private, ChatKind.Secret -> R.string.chat_kind_private
                        ChatKind.Group -> R.string.chat_kind_group
                        ChatKind.Channel -> R.string.chat_kind_channel
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Элементы ленты снизу вверх: сообщения и разделитель «Непрочитанные». */
private sealed interface Item {
    val key: Any

    data class Msg(val message: Message, val showSender: Boolean) : Item {
        override val key: Any get() = message.id
    }

    data object UnreadDivider : Item {
        override val key: Any get() = "unread"
    }
}

private fun buildItems(state: ChatHistory.State, isGroup: Boolean): List<Item> = buildList {
    val messages = state.messages
    messages.forEachIndexed { i, message ->
        val older = messages.getOrNull(i + 1)
        // В группе имя автора — над первым сообщением серии
        val showSender = isGroup && !message.isOutgoing && older?.sender != message.sender
        add(Item.Msg(message, showSender))
        if (message.id == state.firstUnreadId) add(Item.UnreadDivider)
    }
}

@Composable
private fun MessageList(
    chat: Chat,
    state: ChatHistory.State,
    senders: Map<Sender, SenderInfo>,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    modifier: Modifier,
) {
    val isChannel = chat.kind == ChatKind.Channel
    val items = buildItems(state, isGroup = chat.kind == ChatKind.Group)
    val listState = rememberLazyListState()
    ScrollToUnreadOnce(listState, items)
    PagingEffects(listState, state, onLoadOlder, onLoadNewer)
    // reverseLayout: индекс 0 — самое новое сообщение, внизу экрана
    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is Item.Msg -> if (isChannel) {
                    ChannelPost(item.message)
                } else {
                    MessageBubble(item.message, sender = if (item.showSender) senders[item.message.sender] else null)
                }
                Item.UnreadDivider -> UnreadDivider()
            }
        }
        if (!state.reachedOldest) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Открыли с непрочитанного — прокручиваем так, чтобы разделитель оказался у верхнего края. */
@Composable
private fun ScrollToUnreadOnce(listState: LazyListState, items: List<Item>) {
    var done by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (done || items.isEmpty()) return@LaunchedEffect
        done = true
        val divider = items.indexOf(Item.UnreadDivider)
        if (divider < 0) return@LaunchedEffect
        listState.scrollToItem(divider)
        // Сейчас разделитель у нижнего края; сдвигаем ленту к новым на высоту экрана
        listState.scrollBy(-listState.layoutInfo.viewportSize.height * UNREAD_TOP_FRACTION)
    }
}

private const val UNREAD_TOP_FRACTION = 0.85f

@Composable
private fun PagingEffects(
    listState: LazyListState,
    state: ChatHistory.State,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    threshold: Int = 10,
) {
    val older by rememberUpdatedState(onLoadOlder)
    val newer by rememberUpdatedState(onLoadNewer)
    LaunchedEffect(listState, state.reachedOldest) {
        if (state.reachedOldest) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount to (last >= info.totalItemsCount - threshold)
        }.distinctUntilChanged().filter { it.second }.collect { older() }
    }
    LaunchedEffect(listState, state.reachedNewest) {
        if (state.reachedNewest) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.totalItemsCount to (listState.firstVisibleItemIndex < threshold)
        }.distinctUntilChanged().filter { it.second }.collect { newer() }
    }
}

@Composable
private fun MessageBubble(message: Message, sender: SenderInfo?) {
    val outgoing = message.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (sender != null) {
                Text(
                    sender.name.ifBlank { stringResource(R.string.chat_deleted_account) },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            MessageBody(message.content)
            Text(
                formatMessageTime(message.date),
                modifier = Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Пост канала: карточка на всю ширину, подпись автора и просмотры внизу. */
@Composable
private fun ChannelPost(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        MessageBody(message.content)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                message.authorSignature,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.viewCount > 0) {
                Text(
                    pluralStringResource(R.plurals.views, message.viewCount, message.viewCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatMessageTime(message.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBody(content: MessagePreview) {
    when (content) {
        is MessagePreview.Text -> Text(content.text, style = MaterialTheme.typography.bodyLarge)
        is MessagePreview.Media -> {
            Text(
                "[" + mediaKindText(content.kind) + "]",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (content.caption.isNotBlank()) Text(content.caption, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun UnreadDivider() {
    Text(
        stringResource(R.string.chat_unread_divider),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun formatMessageTime(unixSeconds: Int): String =
    Instant.ofEpochSecond(unixSeconds.toLong()).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

@Preview
@Composable
private fun ChatPreview() {
    val now = (System.currentTimeMillis() / 1000).toInt()
    val chat = Chat(1, ChatKind.Group, "Семья", null, emptyMap(), 1, 2, null, 3)
    val messages = listOf(
        Message(3, Sender.User(2), now, false, MessagePreview.Text("И вам 🙂"), "", 0),
        Message(2, Sender.User(1), now - 60, true, MessagePreview.Text("Всем привет!"), "", 0),
        Message(1, Sender.User(3), now - 120, false, MessagePreview.Media(io.github.iufb.reach.chats.MediaKind.Photo, "Море"), "", 0),
    )
    val senders = mapOf<Sender, SenderInfo>(
        Sender.User(2) to SenderInfo("Аня", null, 1),
        Sender.User(3) to SenderInfo("Папа", null, 3),
    )
    ReachTheme {
        ChatContent(
            chat,
            ChatHistory.State(messages, reachedOldest = true, reachedNewest = true, firstUnreadId = 3, opened = true),
            senders, onBack = {}, onLoadOlder = {}, onLoadNewer = {},
        )
    }
}
