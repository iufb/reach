package io.github.iufb.reach.chats

import android.content.res.Configuration
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.navigation.MainTab
import io.github.iufb.reach.navigation.MainTabBar
import io.github.iufb.reach.presence.ActivityKind
import io.github.iufb.reach.presence.activityText
import io.github.iufb.reach.ui.components.OnlineAvatar
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.PillGroup
import io.github.iufb.reach.ui.components.PillGroupIcon
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SearchButton
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.SkeletonCircleShape
import io.github.iufb.reach.ui.components.SkeletonFadeMs
import io.github.iufb.reach.ui.components.TabBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.components.rememberSkeletonVisible
import io.github.iufb.reach.ui.components.skeletonFraction
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun ChatsScreen(
    onChatClick: (Long) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
    onSelectTab: (MainTab) -> Unit = {},
    /** «Новая группа» (`false`) / «Новый канал» (`true`). */
    onNewMessage: () -> Unit = {},
    /** Форум открывается списком топиков. */
    onOpenTopics: (Long) -> Unit = onChatClick,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val openForumAware: (Long) -> Unit = { id ->
        val target = chats?.firstOrNull { it.id == id }
        if (target != null && target.viewAsTopics && !target.isSavedMessages) onOpenTopics(id)
        else viewModel.openWhenReady(id) { onChatClick(id) }
    }
    val allLoaded by viewModel.allLoaded.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val online by viewModel.onlineUsers.collectAsStateWithLifecycle()
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
        online = online,
        onChatLongClick = viewModel::openMenu,
        snackbar = snackbar,
        archive = archive,
        onOpenArchive = onOpenArchive,
        onBack = onBack,
        onOpenSearch = onOpenSearch,
        onOpenSaved = viewModel::openSavedMessages,
        onSelectTab = onSelectTab,
        onNewMessage = onNewMessage,
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
    /** Собеседники «в сети»: зелёная точка на аватаре. */
    online: Set<Long> = emptySet(),
    onChatLongClick: (Long) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    archive: ArchiveSummary? = null,
    onOpenArchive: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onSelectTab: (MainTab) -> Unit = {},
    onNewMessage: () -> Unit = {},
    tabs: List<FolderTab> = emptyList(),
    onSelectList: (ChatListType) -> Unit = {},
) {
    val colors = ReachTheme.colors
    val isArchive = list == ChatListType.Archive
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    // Шапка плавает над списком: её высота (с плеером) — отступ сверху у содержимого
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (isArchive) navBar else navBar + TabBarHeight + 16.dp
    // В хосте вкладок — общее стекло с единственным таббаром (#395)
    GlassScope(shareParent = true) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            ChatsBody(
                list, chats, allLoaded, loggingOut, onChatClick, onChatLongClick, onLoadMore,
                PaddingValues(top = headerHeight, bottom = bottomInset),
                activities, online, archive, onOpenArchive,
                header = {
                    if (!isArchive) {
                        SearchButton(onOpenSearch, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        if (tabs.isNotEmpty()) FolderTabs(tabs, list, onSelectList)
                    }
                },
            )
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                ReachTopBar(
                    title = stringResource(if (isArchive) R.string.archive_title else R.string.chats_title),
                    navigation = if (isArchive) ({ BackPill(onBack) }) else null,
                    actions = { if (!isArchive) ChatsActions(loggingOut, onOpenSaved, onNewMessage) },
                )
                io.github.iufb.reach.chat.NowPlayingBar()
            }
            if (!isArchive) {
                MainTabBar(MainTab.Chats, onSelect = onSelectTab, modifier = Modifier.align(Alignment.BottomCenter))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset))
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

/** Справа в шапке: «Избранное» и «новое сообщение» (контакт, группа, канал). */
@Composable
private fun ChatsActions(loggingOut: Boolean, onOpenSaved: () -> Unit, onNewMessage: () -> Unit) {
    PillGroup {
        PillGroupIcon(painterResource(R.drawable.ic_bookmark), stringResource(R.string.saved_messages), onOpenSaved)
        PillGroupIcon(
            painterResource(R.drawable.ic_edit),
            stringResource(R.string.chats_create),
            onClick = { if (!loggingOut) onNewMessage() },
        )
    }
}

/** Папки — капсулы под поиском; у каждой число непрочитанных чатов со звуком. */
@Composable
private fun FolderTabs(tabs: List<FolderTab>, selected: ChatListType, onSelect: (ChatListType) -> Unit) {
    val colors = ReachTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.list == selected
            Row(
                Modifier
                    .height(FOLDER_HEIGHT)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.fill else Color.Transparent)
                    .clickable { onSelect(tab.list) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tab.title ?: stringResource(R.string.folder_all_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.accent else colors.secondaryLabel,
                    maxLines = 1,
                )
                if (tab.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    UnreadBadge(tab.unread, muted = !isSelected)
                }
            }
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
    padding: PaddingValues,
    activities: Map<Long, Map<Sender, ActivityKind>>,
    online: Set<Long>,
    archive: ArchiveSummary?,
    onOpenArchive: () -> Unit,
    header: @Composable () -> Unit,
) {
    val isArchive = list == ChatListType.Archive
    val placeholder = Modifier.fillMaxSize().padding(padding)
    when {
        loggingOut -> Box(placeholder, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        chats != null && chats.isEmpty() && allLoaded && archive == null -> Column(placeholder) {
            header()
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (isArchive) R.string.archive_empty else R.string.chats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ReachTheme.colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Своё состояние прокрутки у каждой вкладки
        else -> key(list) {
            ChatList(list, chats, allLoaded, onChatClick, onChatLongClick, onLoadMore, padding, activities, online, archive, onOpenArchive, header)
        }
    }
}

@Composable
private fun ChatList(
    list: ChatListType,
    /** `null` — первая страница ещё не пришла: вместо строк скелетон. */
    chats: List<Chat>?,
    allLoaded: Boolean,
    onChatClick: (Long) -> Unit,
    onChatLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    padding: PaddingValues,
    activities: Map<Long, Map<Sender, ActivityKind>>,
    online: Set<Long>,
    archive: ArchiveSummary?,
    onOpenArchive: () -> Unit,
    header: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = chats != null && !allLoaded, onLoadMore)
    val skeleton = rememberSkeletonVisible(chats == null)
    // Хвост из заглушек — тоже с задержкой: короткая подгрузка не мигает внизу списка
    val loadingMore = rememberSkeletonVisible(chats != null && !allLoaded)
    val skeletonRows = LocalConfiguration.current.screenHeightDp.dp.div(ROW_HEIGHT).toInt() + 1
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().glassSource(), contentPadding = padding) {
        item(key = "header") { Column { header() } }
        if (archive != null) {
            item(key = "archive") { ArchiveRow(archive, onOpenArchive) }
        }
        if (chats == null) {
            if (skeleton) {
                items(skeletonRows, key = { "skeleton:$it" }, contentType = { "skeleton" }) { index ->
                    SkeletonChatRow(index, Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = tween(SkeletonFadeMs)))
                }
            }
            return@LazyColumn
        }
        items(chats, key = { it.id }) { chat ->
            ChatRow(
                chat,
                isPinned = chat.positions[list]?.isPinned == true,
                activity = activities[chat.id]?.let { activityText(it, withNames = false) { "" } },
                online = !chat.isSavedMessages && chat.userId?.let { it in online } == true,
                onClick = { onChatClick(chat.id) },
                onLongClick = { onChatLongClick(chat.id) },
                // Строки проявляются поверх уходящего скелетона
                modifier = Modifier.animateItem(fadeInSpec = tween(SkeletonFadeMs), placementSpec = null, fadeOutSpec = null),
            )
        }
        if (loadingMore) {
            items(LOAD_MORE_SKELETON_ROWS, key = { "loading:$it" }, contentType = { "skeleton" }) { index ->
                SkeletonChatRow(
                    index,
                    Modifier.animateItem(fadeInSpec = tween(SkeletonFadeMs), placementSpec = null, fadeOutSpec = tween(SkeletonFadeMs)),
                )
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

/**
 * Строка чата Telegram iOS: аватар, справа две строки — имя с mute и время с галочками,
 * под ними превью в две строки и счётчик (или значок закрепа). Разделитель — от текста.
 */
@Composable
private fun ChatRow(
    chat: Chat,
    isPinned: Boolean,
    activity: String?,
    online: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReachTheme.colors
    ChatRowLayout(
        modifier = modifier,
        avatar = { OnlineAvatar(online, AVATAR_SIZE, colors.background) { ChatAvatar(chat, size = AVATAR_SIZE) } },
        onClick = onClick,
        onLongClick = onLongClick,
        title = {
            Text(
                chatTitle(chat),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chat.isMuted) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(R.drawable.ic_muted),
                    contentDescription = stringResource(R.string.chat_muted),
                    modifier = Modifier.size(15.dp),
                    tint = colors.tertiaryLabel,
                )
            }
        },
        meta = {
            ReadMark(chat)
            // Черновик новее последнего сообщения — показываем его время
            listOfNotNull(chat.lastMessage?.date, chat.draft?.date).maxOrNull()?.let { date ->
                Text(Dates.chatListTime(date.toLong()), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryLabel)
            }
        },
        preview = {
            Text(
                // «печатает…» важнее черновика и последнего сообщения
                activity?.let { AnnotatedString(it) }
                    // Черновик без текста (только ответ, например из другого клиента) не показываем
                    ?: chat.draft?.takeIf { it.text.text.isNotBlank() }?.let { draftPreview(it) }
                    ?: AnnotatedString(chat.lastMessage?.let { previewText(it, chat) }.orEmpty()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (activity != null) colors.accent else colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        badge = {
            // Счётчик важнее значка закрепа: если есть непрочитанные, показываем его
            when {
                chat.unreadCount > 0 -> UnreadBadge(chat.unreadCount, muted = chat.isMuted)
                // Пометка без непрочитанных — пустая точка-счётчик
                chat.isMarkedAsUnread -> UnreadBadge(0, muted = chat.isMuted)
                isPinned -> Icon(
                    painterResource(R.drawable.ic_pin),
                    contentDescription = stringResource(R.string.chat_pinned),
                    // Скрепка Fluent уже наклонена — без доворота (#374)
                    modifier = Modifier.size(16.dp),
                    tint = colors.tertiaryLabel,
                )
            }
        },
    )
}

/** Галочки у своего последнего сообщения: одна — отправлено, две — прочитано. В «Избранном» и каналах их нет. */
@Composable
private fun ReadMark(chat: Chat) {
    val last = chat.lastMessage ?: return
    if (!last.isOutgoing || chat.isSavedMessages || chat.kind == ChatKind.Channel || chat.draft != null) return
    val read = last.id <= chat.lastReadOutboxMessageId
    Icon(
        painterResource(if (read) R.drawable.ic_check_double else R.drawable.ic_check),
        contentDescription = stringResource(if (read) R.string.message_read else R.string.message_sent),
        modifier = Modifier.padding(end = 3.dp).size(17.dp),
        tint = ReachTheme.colors.positive,
    )
}

/** Общая раскладка строки списка: у строки архива те же размеры. */
@Composable
private fun ChatRowLayout(
    avatar: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    title: @Composable RowScope.() -> Unit,
    meta: @Composable RowScope.() -> Unit,
    preview: @Composable RowScope.() -> Unit,
    badge: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReachTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).padding(top = 8.dp, end = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Иконка «без звука» сразу за названием, а не у края
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = title)
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, content = meta)
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                    preview()
                    Box(Modifier.padding(start = 8.dp, top = 6.dp)) { badge() }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        }
    }
}

/** Скелетон строки чата: та же раскладка, что у [ChatRow], вместо текста — полоски разной длины. */
@Composable
internal fun SkeletonChatRow(index: Int, modifier: Modifier = Modifier) {
    ChatRowLayout(
        modifier = modifier,
        avatar = { SkeletonBox(Modifier.size(AVATAR_SIZE), SkeletonCircleShape) },
        onClick = null,
        onLongClick = null,
        title = {
            Box(Modifier.weight(1f).height(22.dp), contentAlignment = Alignment.CenterStart) {
                SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index, 0.3f, 0.55f)).height(14.dp))
            }
        },
        meta = { SkeletonBox(Modifier.size(width = 34.dp, height = 12.dp)) },
        preview = {
            Column(Modifier.weight(1f)) {
                Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index + 3, 0.7f, 0.92f)).height(12.dp))
                }
                Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index + 5, 0.4f, 0.65f)).height(12.dp))
                }
            }
        },
        badge = {},
    )
}

/** Строка «Архив»: сколько чатов и чьи, счётчик непрочитанных — серый, архив без звука. */
@Composable
private fun ArchiveRow(archive: ArchiveSummary, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    ChatRowLayout(
        avatar = {
            Box(
                Modifier.size(AVATAR_SIZE).background(colors.secondaryLabel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_archive), contentDescription = null, tint = Color.White)
            }
        },
        onClick = onClick,
        onLongClick = null,
        title = {
            Text(
                stringResource(R.string.archive_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.label,
            )
        },
        meta = {},
        preview = {
            Text(
                archive.titles.filter { it.isNotBlank() }.joinToString(", ")
                    .ifEmpty { pluralStringResource(R.plurals.archive_chats, archive.total, archive.total) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        badge = { if (archive.unreadChats > 0) UnreadBadge(archive.unreadChats, muted = true) },
    )
}

/** Счётчик непрочитанного: акцентный, у чатов без звука — серый; `0` — пустая точка. */
@Composable
private fun UnreadBadge(count: Int, muted: Boolean = false) {
    val colors = ReachTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE)
            .background(if (muted) colors.secondaryLabel.copy(alpha = 0.7f) else colors.accent, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                count == 0 -> ""
                count > MAX_BADGE -> "$MAX_BADGE+"
                else -> count.toString()
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onAccent,
        )
    }
}

private const val MAX_BADGE = 999
private const val LOAD_MORE_SKELETON_ROWS = 3
private val ROW_HEIGHT = 78.dp
private val AVATAR_SIZE = 60.dp
private val BADGE_SIZE = 20.dp
private val FOLDER_HEIGHT = 32.dp

/** «Черновик: …» — метка красным, как в официальном клиенте. */
@Composable
private fun draftPreview(draft: Draft): AnnotatedString {
    val label = stringResource(R.string.chat_preview_draft)
    val color = ReachTheme.colors.destructive
    return buildAnnotatedString {
        withStyle(SpanStyle(color = color)) { append(label) }
        append(' ')
        append(draft.text.text.replace('\n', ' '))
    }
}

@Composable
private fun previewText(message: LastMessage, chat: Chat): String {
    // Переносы строк в превью не нужны
    val text = bodySummary(message.content).replace('\n', ' ')
    // В канале пишет сам канал — «Вы:» не нужно (#221)
    val own = message.isOutgoing && chat.kind != ChatKind.Channel
    return if (own) stringResource(R.string.chat_preview_you, text) else text
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

@Preview(name = "Список — день")
@Preview(name = "Список — ночь", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatsPreview() {
    val now = (System.currentTimeMillis() / 1000).toInt()
    val chats = listOf(
        Chat(4, ChatKind.Private, null, "Избранное", null, mapOf(ChatListType.Main to ChatPosition(10, isPinned = true)), 0, 0, null, 0, true),
        Chat(1, ChatKind.Private, null, "Аня", LastMessage(1, now, false, MessageBody.Text(RichText("Привет! 👋"))), emptyMap(), 2, 0, null, 1, true, draft = Draft(RichText("Завтра в 10?"), now)),
        Chat(2, ChatKind.Group, GroupRef.Basic(2), "Семья", LastMessage(2, now - 86_400, true, MessageBody.Unsupported(ContentKind.Photo, RichText("Море"))), emptyMap(), 0, 0, null, 0, true),
        Chat(3, ChatKind.Channel, GroupRef.Super(3), "Новости", LastMessage(3, now - 30 * 86_400, false, MessageBody.Unsupported(ContentKind.Poll, RichText("Что дальше?"))), emptyMap(), 0, 0, null, 0, true),
    )
    ReachTheme { ChatsContent(ChatListType.Main, chats, allLoaded = false, loggingOut = false, onChatClick = {}, onLoadMore = {}, onLogOut = {}) }
}
