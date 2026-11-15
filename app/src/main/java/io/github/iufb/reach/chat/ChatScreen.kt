package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.ContentKind
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.ServiceKind
import io.github.iufb.reach.chats.contentKindText
import io.github.iufb.reach.chats.serviceKindText
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
    val canWrite by viewModel.canWrite.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    ChatContent(
        chat, state, senders, onBack, viewModel::loadOlder, viewModel::loadNewer,
        onVisibleMessagesChange = viewModel::onVisibleMessagesChange,
        composer = if (canWrite) ComposerState(draft, sendError, viewModel::onDraftChange, viewModel::send) else null,
    )
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
    onVisibleMessagesChange: (List<Long>) -> Unit,
    composer: ComposerState?,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = { if (composer != null && chat != null) Composer(composer) },
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
            MessageList(chat, state, senders, onLoadOlder, onLoadNewer, onVisibleMessagesChange, modifier)
        }
    }
}

/** Поле ввода; `null` в [ChatContent] — писать нельзя, поле скрыто. */
private class ComposerState(
    val draft: String,
    val error: String?,
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
)

@Composable
private fun Composer(state: ComposerState) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = state.onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                isError = state.error != null,
                supportingText = state.error?.let { { Text(stringResource(R.string.chat_send_error, it)) } },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            IconButton(onClick = state.onSend, enabled = state.draft.isNotBlank()) {
                Icon(
                    painterResource(R.drawable.ic_send),
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (state.draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        val showSender = isGroup && !message.isOutgoing &&
            (older?.sender != message.sender || older.content is MessageBody.Service)
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
    onVisibleMessagesChange: (List<Long>) -> Unit,
    modifier: Modifier,
) {
    val isChannel = chat.kind == ChatKind.Channel
    val items = buildItems(state, isGroup = chat.kind == ChatKind.Group)
    val listState = rememberLazyListState()
    ScrollToUnreadOnce(listState, items)
    FollowNewMessages(listState, state)
    val visibleChanged by rememberUpdatedState(onVisibleMessagesChange)
    LaunchedEffect(listState) {
        // Ключи сообщений — их id (Long); разделитель и загрузчик отсеиваются
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .distinctUntilChanged()
            .collect { visibleChanged(it) }
    }
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
                is Item.Msg -> when {
                    item.message.content is MessageBody.Service -> ServiceMessage(item.message.content.kind)
                    isChannel -> ChannelPost(item.message)
                    else ->
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

/**
 * Новое сообщение внизу: если пользователь был у конца ленты — докручиваем к нему,
 * если читает историю выше — не мешаем.
 */
@Composable
private fun FollowNewMessages(listState: LazyListState, state: ChatHistory.State) {
    val messages = state.messages
    val newestId = messages.firstOrNull()?.id
    var previousNewestId by remember { mutableStateOf(newestId) }
    var previousReachedNewest by remember { mutableStateOf(state.reachedNewest) }
    LaunchedEffect(newestId) {
        val previous = previousNewestId
        // Новые снизу при недогруженном конце — это страница loadNewer, а не живое сообщение: не крутим
        val live = previousReachedNewest
        previousNewestId = newestId
        previousReachedNewest = state.reachedNewest
        if (!live || previous == null || newestId == null || newestId <= previous) return@LaunchedEffect
        // Лента держит позицию по ключу видимого элемента, поэтому сейчас индекс сдвинут на число новых
        val added = messages.indexOfFirst { it.id <= previous }.takeIf { it >= 0 } ?: messages.size
        // Своё сообщение показываем всегда, чужое — только если пользователь был внизу
        if (messages.first().isOutgoing || listState.firstVisibleItemIndex <= added) listState.animateScrollToItem(0)
    }
}

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
            MessageContent(message.content)
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatMessageTime(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outgoing) SendingStatus(message.sendingState)
            }
        }
    }
}

/** Отправляется — часы, ошибка — «!», отправлено — галочка. */
@Composable
private fun SendingStatus(state: SendingState?) {
    val (symbol, description, color) = when (state) {
        SendingState.Pending -> Triple("◷", R.string.chat_status_sending, MaterialTheme.colorScheme.onSurfaceVariant)
        SendingState.Failed -> Triple("!", R.string.chat_status_failed, MaterialTheme.colorScheme.error)
        null -> Triple("✓", R.string.chat_status_sent, MaterialTheme.colorScheme.primary)
    }
    val label = stringResource(description)
    Text(
        symbol,
        modifier = Modifier.semantics { contentDescription = label },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
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
        MessageContent(message.content)
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
private fun MessageContent(body: MessageBody) {
    when (body) {
        is MessageBody.Text -> Text(body.text, style = MaterialTheme.typography.bodyLarge)
        is MessageBody.Unsupported -> {
            UnsupportedStub(body.kind)
            if (body.caption.isNotBlank()) Text(body.caption, style = MaterialTheme.typography.bodyLarge)
        }
        // Служебные рисуются отдельно, без пузыря (ServiceMessage)
        is MessageBody.Service -> Text(serviceKindText(body.kind), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Заглушка для того, что клиент пока не показывает: тип и пометка, а не пустой пузырь. */
@Composable
private fun UnsupportedStub(kind: ContentKind) {
    Column(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(contentKindText(kind), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.content_unsupported_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceMessage(kind: ServiceKind) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            serviceKindText(kind),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val chat = Chat(1, ChatKind.Group, GroupRef.Basic(1), "Семья", null, emptyMap(), 1, 2, null, 3, true)
    val messages = listOf(
        Message(3, Sender.User(2), now, false, MessageBody.Text("И вам 🙂"), "", 0),
        Message(2, Sender.User(1), now - 60, true, MessageBody.Text("Всем привет!"), "", 0, SendingState.Pending),
        Message(1, Sender.User(3), now - 120, false, MessageBody.Unsupported(ContentKind.Photo, "Море"), "", 0),
    )
    val senders = mapOf<Sender, SenderInfo>(
        Sender.User(2) to SenderInfo("Аня", null, 1),
        Sender.User(3) to SenderInfo("Папа", null, 3),
    )
    ReachTheme {
        ChatContent(
            chat,
            ChatHistory.State(messages, reachedOldest = true, reachedNewest = true, firstUnreadId = 3, opened = true),
            senders, onBack = {}, onLoadOlder = {}, onLoadNewer = {}, onVisibleMessagesChange = {},
            composer = ComposerState("Как дела?", null, {}, {}),
        )
    }
}
