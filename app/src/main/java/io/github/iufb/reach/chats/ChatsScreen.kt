package io.github.iufb.reach.chats

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.Dp
import io.github.iufb.reach.ui.theme.reachBarColors
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
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
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun ChatsScreen(onChatClick: (Long) -> Unit, viewModel: ChatsViewModel = hiltViewModel()) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val allLoaded by viewModel.allLoaded.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    ChatsContent(viewModel.list, chats, allLoaded, loggingOut, onChatClick, viewModel::loadMore, viewModel::logOut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsContent(
    list: ChatListType,
    chats: List<Chat>?,
    allLoaded: Boolean,
    loggingOut: Boolean,
    onChatClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onLogOut: () -> Unit,
) {
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                title = { Text(stringResource(R.string.chats_title), fontWeight = FontWeight.Medium) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !loggingOut) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.chat_list_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logout)) },
                                onClick = {
                                    menuOpen = false
                                    confirmLogOut = true
                                },
                            )
                        }
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
            else -> ChatList(list, chats, allLoaded, onChatClick, onLoadMore, modifier)
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
    list: ChatListType,
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
            ChatRow(chat, isPinned = chat.positions[list]?.isPinned == true, onClick = { onChatClick(chat.id) })
            HorizontalDivider(Modifier.padding(start = 80.dp), thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
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
private fun ChatRow(chat: Chat, isPinned: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.title, chat.photo, chat.accentColorId, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title.ifBlank { stringResource(R.string.chat_deleted_account) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Черновик новее последнего сообщения — показываем его время
                listOfNotNull(chat.lastMessage?.date, chat.draft?.date).maxOrNull()?.let { date ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatChatTime(date),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.draft?.let { draftPreview(it) }
                        ?: AnnotatedString(chat.lastMessage?.let { previewText(it) }.orEmpty()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Счётчик важнее значка закрепа: если есть непрочитанные, показываем его
                when {
                    chat.unreadCount > 0 -> {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(chat.unreadCount)
                    }
                    isPinned -> {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painterResource(R.drawable.ic_pin),
                            contentDescription = stringResource(R.string.chat_pinned),
                            modifier = Modifier.size(16.dp).rotate(45f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private const val MAX_BADGE = 999

/** «Черновик: …» — метка красным, как в официальном клиенте. */
@Composable
private fun draftPreview(draft: Draft): AnnotatedString {
    val label = stringResource(R.string.chat_preview_draft)
    val color = MaterialTheme.colorScheme.error
    return buildAnnotatedString {
        withStyle(SpanStyle(color = color)) { append(label) }
        append(' ')
        append(draft.text.text.replace('\n', ' '))
    }
}

@Composable
private fun previewText(message: LastMessage): String {
    // Переносы строк в превью не нужны
    val text = bodySummary(message.content).replace('\n', ' ')
    return if (message.isOutgoing) stringResource(R.string.chat_preview_you, text) else text
}

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
        Chat(4, ChatKind.Private, null, "Избранное", null, mapOf(ChatListType.Main to ChatPosition(10, isPinned = true)), 0, 0, null, 0, true),
        Chat(1, ChatKind.Private, null, "Аня", LastMessage(1, now, false, MessageBody.Text(RichText("Привет! 👋"))), emptyMap(), 2, 0, null, 1, true, Draft(RichText("Завтра в 10?"), now)),
        Chat(2, ChatKind.Group, GroupRef.Basic(2), "Семья", LastMessage(2, now - 86_400, true, MessageBody.Unsupported(ContentKind.Photo, RichText("Море"))), emptyMap(), 0, 0, null, 0, true),
        Chat(3, ChatKind.Channel, GroupRef.Super(3), "Новости", LastMessage(3, now - 30 * 86_400, false, MessageBody.Unsupported(ContentKind.Poll, RichText("Что дальше?"))), emptyMap(), 0, 0, null, 0, true),
    )
    ReachTheme { ChatsContent(ChatListType.Main, chats, allLoaded = false, loggingOut = false, onChatClick = {}, onLoadMore = {}, onLogOut = {}) }
}
