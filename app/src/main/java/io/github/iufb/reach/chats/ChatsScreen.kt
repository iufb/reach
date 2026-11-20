package io.github.iufb.reach.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.key
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.presence.ActivityKind
import io.github.iufb.reach.presence.activityText
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
fun ChatsScreen(
    onChatClick: (Long) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
    onOpenAutoDownload: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    /** Форум открывается списком топиков. */
    onOpenTopics: (Long) -> Unit = onChatClick,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val openForumAware: (Long) -> Unit = { id ->
        val target = chats?.firstOrNull { it.id == id }
        if (target != null && target.viewAsTopics && !target.isSavedMessages) onOpenTopics(id) else onChatClick(id)
    }
    val allLoaded by viewModel.allLoaded.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val menuChat by viewModel.menuChat.collectAsStateWithLifecycle()
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    val openChat by rememberUpdatedState(onChatClick)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { openChat(it) } }
    ChatsContent(
        list, chats, allLoaded, loggingOut, openForumAware, viewModel::loadMore, viewModel::logOut, activities,
        onChatLongClick = viewModel::openMenu,
        snackbar = snackbar,
        archive = archive,
        onOpenArchive = onOpenArchive,
        onBack = onBack,
        onOpenSearch = onOpenSearch,
        onOpenSaved = viewModel::openSavedMessages,
        onOpenAutoDownload = onOpenAutoDownload,
        onOpenContacts = onOpenContacts,
        tabs = tabs,
        onSelectList = viewModel::selectList,
    )
    menuChat?.let { chat ->
        ChatMenuSheet(chat, list, ChatMenuActions(
                onSetPinned = viewModel::setPinned,
                onSetArchived = viewModel::setArchived,
                onSetMuteFor = viewModel::setMuteFor,
                onSetUnread = viewModel::setUnread,
            ), onDismiss = viewModel::dismissMenu)
    }
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
    activities: Map<Long, Map<Sender, ActivityKind>> = emptyMap(),
    onChatLongClick: (Long) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    archive: ArchiveSummary? = null,
    onOpenArchive: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenAutoDownload: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    tabs: List<FolderTab> = emptyList(),
    onSelectList: (ChatListType) -> Unit = {},
) {
    val isArchive = list == ChatListType.Archive
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    colors = reachBarColors(),
                    navigationIcon = {
                        if (isArchive) {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    title = {
                        Text(
                            stringResource(if (isArchive) R.string.archive_title else R.string.chats_title),
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    actions = {
                        if (!isArchive) {
                            IconButton(onClick = onOpenSearch) {
                                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search_title))
                            }
                        }
                        if (!isArchive) Box {
                            IconButton(onClick = { menuOpen = true }, enabled = !loggingOut) {
                                Icon(painterResource(R.drawable.ic_more), stringResource(R.string.chat_list_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.saved_messages)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenSaved()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contacts_title)) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenContacts()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.autodownload_title)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenAutoDownload()
                                    },
                                )
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
                io.github.iufb.reach.chat.NowPlayingBar()
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (tabs.isNotEmpty()) {
            Column(modifier) {
                FolderTabs(tabs, list, onSelectList)
                ChatsBody(list, chats, allLoaded, loggingOut, onChatClick, onChatLongClick, onLoadMore, Modifier.weight(1f).fillMaxWidth(), activities, archive, onOpenArchive)
            }
        } else {
            ChatsBody(list, chats, allLoaded, loggingOut, onChatClick, onChatLongClick, onLoadMore, modifier, activities, archive, onOpenArchive)
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

/** Вкладки папок; у каждой — число непрочитанных чатов со звуком. */
@Composable
private fun FolderTabs(tabs: List<FolderTab>, selected: ChatListType, onSelect: (ChatListType) -> Unit) {
    val index = tabs.indexOfFirst { it.list == selected }.coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = index,
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        tabs.forEachIndexed { i, tab ->
            Tab(
                selected = i == index,
                onClick = { onSelect(tab.list) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.title ?: stringResource(R.string.folder_all_chats), maxLines = 1)
                        if (tab.unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            UnreadBadge(tab.unread, muted = i != index)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatsBody(
    list: ChatListType,
    chats: List<Chat>?,
    allLoaded: Boolean,
    loggingOut: Boolean,
    onChatClick: (Long) -> Unit,
    onChatLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
    activities: Map<Long, Map<Sender, ActivityKind>>,
    archive: ArchiveSummary?,
    onOpenArchive: () -> Unit,
) {
    val isArchive = list == ChatListType.Archive
    when {
        loggingOut || chats == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        chats.isEmpty() && allLoaded && archive == null -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (isArchive) R.string.archive_empty else R.string.chats_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        // Своё состояние прокрутки у каждой вкладки
        else -> key(list) { ChatList(list, chats, allLoaded, onChatClick, onChatLongClick, onLoadMore, modifier, activities, archive, onOpenArchive) }
    }
}

@Composable
private fun ChatList(
    list: ChatListType,
    chats: List<Chat>,
    allLoaded: Boolean,
    onChatClick: (Long) -> Unit,
    onChatLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
    activities: Map<Long, Map<Sender, ActivityKind>>,
    archive: ArchiveSummary?,
    onOpenArchive: () -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = !allLoaded, onLoadMore)
    LazyColumn(state = listState, modifier = modifier) {
        if (archive != null) {
            item(key = "archive") {
                ArchiveRow(archive, onOpenArchive)
                HorizontalDivider(Modifier.padding(start = 80.dp), thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        items(chats, key = { it.id }) { chat ->
            ChatRow(
                chat,
                isPinned = chat.positions[list]?.isPinned == true,
                activity = activities[chat.id]?.let { activityText(it, withNames = false) { "" } },
                onClick = { onChatClick(chat.id) },
                onLongClick = { onChatLongClick(chat.id) },
            )
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
private fun ChatRow(chat: Chat, isPinned: Boolean, activity: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(chat, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Иконка «без звука» сразу за названием, а не у края
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chatTitle(chat),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.isMuted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_muted),
                            contentDescription = stringResource(R.string.chat_muted),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                    // «печатает…» важнее черновика и последнего сообщения
                    activity?.let { AnnotatedString(it) }
                        ?: chat.draft?.let { draftPreview(it) }
                        ?: AnnotatedString(chat.lastMessage?.let { previewText(it) }.orEmpty()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activity != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Счётчик важнее значка закрепа: если есть непрочитанные, показываем его
                when {
                    chat.unreadCount > 0 -> {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(chat.unreadCount, muted = chat.isMuted)
                    }
                    // Пометка без непрочитанных — пустая точка-счётчик
                    chat.isMarkedAsUnread -> {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(0, muted = chat.isMuted)
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

/** Строка «Архив»: сколько чатов и чьи, счётчик непрочитанных — серый, архив без звука. */
@Composable
private fun ArchiveRow(archive: ArchiveSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.archive_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    archive.titles.filter { it.isNotBlank() }.joinToString(", ")
                        .ifEmpty { pluralStringResource(R.plurals.archive_chats, archive.total, archive.total) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (archive.unreadChats > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(archive.unreadChats, muted = true)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int, muted: Boolean = false) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                count == 0 -> ""
                count > MAX_BADGE -> "$MAX_BADGE+"
                else -> count.toString()
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onPrimary,
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
