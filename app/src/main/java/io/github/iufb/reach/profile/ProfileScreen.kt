package io.github.iufb.reach.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.MediaViewer
import io.github.iufb.reach.chat.PhotoViewer
import io.github.iufb.reach.chat.RichTextView
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chat.ViewerItem
import io.github.iufb.reach.chat.openFile
import io.github.iufb.reach.chats.SavedMessagesAvatar
import io.github.iufb.reach.chats.autoDeleteLabel
import io.github.iufb.reach.contacts.ContactNameDialog
import io.github.iufb.reach.gallery.GalleryActions
import io.github.iufb.reach.gallery.GalleryLoadMoreEffect
import io.github.iufb.reach.gallery.GalleryTab
import io.github.iufb.reach.gallery.GalleryViewModel
import io.github.iufb.reach.gallery.TabPage
import io.github.iufb.reach.gallery.galleryItems
import io.github.iufb.reach.presence.userStatusText
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.CellIcon
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.PillTabs
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.CellIconColors
import io.github.iufb.reach.ui.theme.ReachTheme

/** Подтверждение в листе: выйти из группы, удалить её, заблокировать пользователя. */
private enum class Confirm { Leave, Delete, Block }

/**
 * Профиль пользователя, группы или канала как в Telegram iOS: крупный аватар, имя и статус, ряд кнопок-действий,
 * карточки с данными и разделами, внизу — вкладки медиа чата одной прокруткой с остальным.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onShowInChat: (chatId: Long, messageId: Long) -> Unit,
    onOpenMembers: (chatId: Long) -> Unit = {},
    onOpenPermissions: (chatId: Long) -> Unit = {},
    onOpenInviteLinks: (chatId: Long) -> Unit = {},
    onOpenStatistics: (chatId: Long) -> Unit = {},
    /** «Изменить» группы или канала (#222). */
    onEditChat: (chatId: Long) -> Unit = {},
    /** Вышли из группы или удалили её. */
    onLeft: () -> Unit = onBack,
    viewModel: ProfileViewModel = hiltViewModel(),
    gallery: GalleryViewModel = hiltViewModel(),
) {
    val leftCallback by rememberUpdatedState(onLeft)
    LaunchedEffect(viewModel) { viewModel.left.collect { leftCallback() } }
    val open by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { open(it) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.error.collect { snackbar.showSnackbar(it) } }
    val context = LocalContext.current
    LaunchedEffect(gallery) { gallery.openFile.collect { openFile(context, it) } }

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val counts by gallery.counts.collectAsStateWithLifecycle()
    val pages by gallery.pages.collectAsStateWithLifecycle()
    val downloads by gallery.downloads.collectAsStateWithLifecycle()
    val audio by gallery.audio.collectAsStateWithLifecycle()

    var viewingPhoto by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<ViewerItem>, Int>?>(null) }
    var choosingAutoDelete by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    val tabs = GalleryTab.entries.filter { (counts?.get(it) ?: 0) > 0 }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = tabs.firstOrNull { it.name == selectedName } ?: tabs.firstOrNull()
    val page = selected?.let { pages[it] ?: TabPage() }
    LaunchedEffect(selected) { if (selected != null && page?.messages.isNullOrEmpty()) gallery.loadMore(selected) }

    val colors = ReachTheme.colors
    val grid = rememberLazyGridState()
    GalleryLoadMoreEffect(grid, page) { selected?.let(gallery::loadMore) }
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topInsetPx = with(density) { (statusBar + TopBarHeight).roundToPx() }
    // Шапка (первый элемент сетки) ушла под бар — в баре проявляется имя
    var headerHeight by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val collapsed by remember {
        derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > headerHeight - topInsetPx }
    }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(TITLE_FADE_MS), label = "title")

    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            val current = profile
            // Пока профиль не пришёл (кадр-другой после перехода) — пустой фон, без спиннера
            if (current != null) {
                val title = if (current.isSavedMessages) stringResource(R.string.saved_messages)
                else current.title.ifBlank { stringResource(R.string.chat_deleted_account) }
                val more = moreActions(
                    current,
                    onEdit = { editingContact = true },
                    onRemoveContact = viewModel::removeContact,
                    onConfirm = { confirm = it },
                    onUnblock = { viewModel.setBlocked(false) },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(MEDIA_COLUMNS),
                    state = grid,
                    modifier = Modifier.fillMaxSize().glassSource(),
                    contentPadding = PaddingValues(
                        top = statusBar + HEADER_TOP,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MEDIA_GAP),
                    verticalArrangement = Arrangement.spacedBy(MEDIA_GAP),
                ) {
                    full("header") {
                        Header(
                            current,
                            title,
                            onPhoto = { viewingPhoto = true },
                            modifier = Modifier.onSizeChanged { headerHeight = it.height },
                        )
                    }
                    full("actions") {
                        Actions(
                            current,
                            onWrite = viewModel::write,
                            onToggleMute = { current.isMuted?.let { viewModel.setNotificationsEnabled(it) } },
                            onMore = if (more.isNotEmpty()) ({ moreSheet = true }) else null,
                        )
                    }
                    full("info") { Info(current) }
                    full("sections") {
                        Sections(
                            current,
                            onAutoDelete = { choosingAutoDelete = true },
                            onMembers = { onOpenMembers(viewModel.chatId) },
                            onInvites = { onOpenInviteLinks(viewModel.chatId) },
                            onPermissions = { onOpenPermissions(viewModel.chatId) },
                            onStatistics = { onOpenStatistics(viewModel.chatId) },
                        )
                    }
                    if (selected != null && page != null) {
                        full("tabs") {
                            PillTabs(
                                items = tabs.map { "${stringResource(it.label)} ${counts?.get(it) ?: 0}" },
                                selected = tabs.indexOf(selected),
                                onSelect = { selectedName = tabs[it].name },
                            )
                        }
                        galleryItems(
                            selected,
                            page,
                            downloads,
                            audio,
                            GalleryActions(
                                onOpenMedia = { items, index -> viewer = items to index },
                                onDocumentClick = gallery::onDocumentClick,
                                onAudioToggle = { gallery.onAudioToggle(selected, it) },
                                onAudioSeek = gallery::onAudioSeek,
                                onShowInChat = { onShowInChat(gallery.chatId, it.id) },
                            ),
                        )
                    }
                }
                if (moreSheet) ActionSheet(actions = more, onDismiss = { moreSheet = false })
                confirm?.let { kind -> ConfirmSheet(kind, current, viewModel) { confirm = null } }
                if (viewingPhoto) current.fullPhoto?.let { PhotoViewer(listOf(it), 0) { viewingPhoto = false } }
                viewer?.let { (items, index) -> MediaViewer(items, index) { viewer = null } }
                if (editingContact) {
                    ContactNameDialog(
                        title = stringResource(if (current.isContact == true) R.string.contact_edit else R.string.contact_add),
                        initialFirst = current.firstName,
                        initialLast = current.lastName,
                        onSave = { first, last ->
                            editingContact = false
                            viewModel.saveContact(first, last)
                        },
                        onDismiss = { editingContact = false },
                    )
                }
                if (choosingAutoDelete) {
                    AutoDeleteSheet(current.autoDeleteTime ?: 0, onPick = viewModel::setAutoDelete) { choosingAutoDelete = false }
                }
            }
            ReachTopBar(
                title = current?.let { if (it.isSavedMessages) stringResource(R.string.saved_messages) else it.title },
                navigation = { BackPill(onBack) },
                actions = {
                    when {
                        current?.isContact == true -> PillTextButton(stringResource(R.string.profile_edit), onClick = { editingContact = true })
                        current?.canEditChat == true -> PillTextButton(stringResource(R.string.profile_edit), onClick = { onEditChat(viewModel.chatId) })
                    }
                },
                background = colors.groupedBackground,
                titleAlpha = { titleAlpha },
                scrimAlpha = { titleAlpha },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** Элемент сетки во всю ширину. */
private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Аватар (тап — фото во весь экран), имя, под ним статус или число участников. */
@Composable
private fun Header(profile: Profile, title: String, onPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (profile.isSavedMessages) {
            SavedMessagesAvatar(AVATAR)
        } else {
            Avatar(
                profile.title,
                profile.photo,
                profile.accentColorId,
                modifier = Modifier.clip(CircleShape).clickable(enabled = profile.fullPhoto != null, onClick = onPhoto),
                size = AVATAR,
            )
        }
        Text(
            title,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        val subtitle = when {
            profile.isSavedMessages -> null
            profile.isUser -> userStatusText(profile.status)
            profile.memberCount > 0 -> pluralStringResource(
                if (profile.isChannel) R.plurals.subscribers else R.plurals.members,
                profile.memberCount,
                profile.memberCount,
            )
            else -> null
        }
        if (subtitle != null) {
            Text(
                subtitle,
                modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Ряд кнопок под шапкой: «сообщение», звук, «ещё». Нет ни одной — ряда нет. */
@Composable
private fun Actions(profile: Profile, onWrite: () -> Unit, onToggleMute: () -> Unit, onMore: (() -> Unit)?) {
    val buttons = buildList {
        if (profile.canWrite) add(ActionButton(R.drawable.ic_tab_chats, R.string.profile_action_message, onWrite))
        profile.isMuted?.let { muted ->
            add(
                ActionButton(
                    if (muted) R.drawable.ic_unmute else R.drawable.ic_muted,
                    if (muted) R.string.profile_action_unmute else R.string.profile_action_mute,
                    onToggleMute,
                ),
            )
        }
        onMore?.let { add(ActionButton(R.drawable.ic_more, R.string.profile_action_more, it)) }
    }
    if (buttons.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { button -> ActionTile(button, Modifier.weight(1f)) }
    }
}

private class ActionButton(@DrawableRes val icon: Int, @StringRes val label: Int, val onClick: () -> Unit)

@Composable
private fun ActionTile(button: ActionButton, modifier: Modifier) {
    val colors = ReachTheme.colors
    Column(
        modifier
            .height(ACTION_HEIGHT)
            .clip(RoundedCornerShape(ACTION_CORNER))
            .background(colors.cell)
            .clickable(role = Role.Button, onClick = button.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(button.icon), contentDescription = null, Modifier.size(24.dp), tint = colors.accent)
        Text(
            stringResource(button.label),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
            maxLines = 1,
        )
    }
}

/** Карточка данных: телефон, имя пользователя, ссылка, «о себе» / описание — подпись сверху, значение снизу. */
@Composable
private fun Info(profile: Profile) {
    if (profile.isSavedMessages) return
    val rows = buildList {
        if (profile.phoneNumber.isNotBlank()) add(InfoRow(R.string.profile_info_phone, text = "+${profile.phoneNumber}"))
        if (profile.username.isNotBlank()) add(InfoRow(R.string.profile_info_username, text = "@${profile.username}"))
        if (profile.username.isNotBlank() && !profile.isUser) add(InfoRow(R.string.profile_info_link, text = "https://t.me/${profile.username}"))
        if (!profile.about.isBlank()) {
            add(InfoRow(if (profile.isUser) R.string.profile_info_bio else R.string.profile_info_description, about = profile.about))
        }
    }
    if (rows.isEmpty()) return
    val colors = ReachTheme.colors
    GroupedSection {
        rows.forEachIndexed { index, row ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(row.label), style = MaterialTheme.typography.bodyMedium, color = colors.label)
                if (row.about != null) {
                    RichTextView(row.about, style = MaterialTheme.typography.bodyLarge)
                } else if (row.text != null) {
                    Text(row.text, style = MaterialTheme.typography.bodyLarge, color = colors.accent)
                }
            }
            if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = colors.separator)
        }
    }
}

/** Строка карточки данных: подпись и значение — строкой (ссылкой) или форматированным текстом. */
private class InfoRow(@StringRes val label: Int, val text: String? = null, val about: RichText? = null)

/** Разделы чата с цветными значками: участники, ссылки, разрешения, статистика, автоудаление. */
@Composable
private fun Sections(
    profile: Profile,
    onAutoDelete: () -> Unit,
    onMembers: () -> Unit,
    onInvites: () -> Unit,
    onPermissions: () -> Unit,
    onStatistics: () -> Unit,
) {
    val autoDelete = profile.autoDeleteTime?.let { autoDeleteLabel(it) }
    val rows = buildList {
        if (profile.canOpenMembers) {
            add(
                SectionRow(
                    if (profile.isChannel) R.string.members_subscribers else R.string.members_title,
                    R.drawable.ic_tab_contacts, CellIconColors.Orange,
                    profile.memberCount.takeIf { it > 0 }?.toString(), onMembers,
                ),
            )
        }
        if (profile.canManageInvites) add(SectionRow(R.string.invite_links_title, R.drawable.ic_link, CellIconColors.Blue, null, onInvites))
        if (profile.canEditPermissions) add(SectionRow(R.string.permissions_title, R.drawable.ic_lock, CellIconColors.Green, null, onPermissions))
        if (profile.canGetStatistics) add(SectionRow(R.string.stats_title, R.drawable.ic_poll, CellIconColors.Purple, null, onStatistics))
        if (autoDelete != null) {
            add(SectionRow(R.string.auto_delete_title, R.drawable.ic_timer, CellIconColors.Gray, autoDelete, onAutoDelete.takeIf { profile.canChangeAutoDelete }))
        }
    }
    if (rows.isEmpty()) return
    GroupedSection {
        rows.forEachIndexed { index, row ->
            GroupedCell(
                stringResource(row.title),
                onClick = row.onClick,
                icon = { CellIcon(painterResource(row.icon), row.color) },
                value = row.value,
                chevron = row.onClick != null,
                divider = index < rows.lastIndex,
            )
        }
    }
}

/** Раздел в карточке разделов; без [onClick] — только значение (автоудаление без прав). */
private class SectionRow(@StringRes val title: Int, @DrawableRes val icon: Int, val color: Color, val value: String?, val onClick: (() -> Unit)?)

/** Пункты листа «ещё»: контакт, блокировка, выход из группы и её удаление. */
@Composable
private fun moreActions(
    profile: Profile,
    onEdit: () -> Unit,
    onRemoveContact: () -> Unit,
    onConfirm: (Confirm) -> Unit,
    onUnblock: () -> Unit,
): List<SheetAction> = buildList {
    if (profile.isContact == false) add(SheetAction(stringResource(R.string.contact_add), onClick = onEdit))
    if (profile.isContact == true) add(SheetAction(stringResource(R.string.contact_remove), destructive = true, onClick = onRemoveContact))
    when (profile.isBlocked) {
        true -> add(SheetAction(stringResource(R.string.unblock), onClick = onUnblock))
        false -> add(SheetAction(stringResource(R.string.block), destructive = true) { onConfirm(Confirm.Block) })
        null -> Unit
    }
    if (profile.isMember && !profile.isUser) {
        add(
            SheetAction(stringResource(if (profile.isChannel) R.string.leave_channel else R.string.leave_group), destructive = true) {
                onConfirm(Confirm.Leave)
            },
        )
        if (profile.isOwner) {
            add(
                SheetAction(stringResource(if (profile.isChannel) R.string.delete_channel else R.string.delete_group), destructive = true) {
                    onConfirm(Confirm.Delete)
                },
            )
        }
    }
}

/** Подтверждение необратимого действия листом с заголовком-вопросом. */
@Composable
private fun ConfirmSheet(kind: Confirm, profile: Profile, viewModel: ProfileViewModel, onDismiss: () -> Unit) {
    val (question, action) = when (kind) {
        Confirm.Leave -> stringResource(R.string.leave_chat_confirm, profile.title) to
            SheetAction(stringResource(R.string.leave), destructive = true) { viewModel.leave() }
        Confirm.Delete -> stringResource(R.string.delete_chat_confirm, profile.title) to
            SheetAction(stringResource(R.string.message_action_delete), destructive = true) { viewModel.deleteChat() }
        Confirm.Block -> stringResource(R.string.block_confirm, profile.title) to
            SheetAction(stringResource(R.string.block), destructive = true) { viewModel.setBlocked(true) }
    }
    ActionSheet(title = question, actions = listOf(action), onDismiss = onDismiss)
}

/** Выбор таймера автоудаления: выкл, сутки, неделя, месяц; текущий — жирным. */
@Composable
private fun AutoDeleteSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = buildList {
        addAll(AUTO_DELETE_OPTIONS)
        // Значение, выставленное в другом клиенте, тоже показываем
        if (current !in AUTO_DELETE_OPTIONS) add(current)
    }
    ActionSheet(
        title = stringResource(R.string.auto_delete_hint),
        actions = options.map { seconds -> SheetAction(autoDeleteLabel(seconds), bold = seconds == current) { onPick(seconds) } },
        onDismiss = onDismiss,
    )
}

private val AUTO_DELETE_OPTIONS = listOf(0, 86_400, 7 * 86_400, 31 * 86_400)
private val HEADER_TOP = 8.dp
private val AVATAR = 100.dp
private val ACTION_HEIGHT = 58.dp
private val ACTION_CORNER = 14.dp
private val MEDIA_GAP = 1.dp
private const val MEDIA_COLUMNS = 3
private const val TITLE_FADE_MS = 180
