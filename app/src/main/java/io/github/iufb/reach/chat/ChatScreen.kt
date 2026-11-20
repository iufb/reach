package io.github.iufb.reach.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.chats.DocumentInfo
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import io.github.iufb.reach.ui.theme.reachBarColors
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.iufb.reach.chats.ChatAvatar
import io.github.iufb.reach.chats.chatTitle
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.ContentKind
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.presence.activityText
import io.github.iufb.reach.presence.userStatusText
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.chats.TextSpan
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
fun ChatScreen(
    onBack: () -> Unit,
    onForward: (fromChatId: Long, messageIds: List<Long>) -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenProfile: (chatId: Long) -> Unit,
    onOpenUserProfile: (userId: Long) -> Unit,
    onOpenScheduled: (chatId: Long) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val senders by viewModel.senders.collectAsStateWithLifecycle()
    val canWrite by viewModel.canWrite.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectionActions by viewModel.selectionActions.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val linkPreview by viewModel.linkPreview.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val canScheduleWhenOnline by viewModel.canScheduleWhenOnline.collectAsStateWithLifecycle()
    val openChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { openChat(it) } }
    val openUserProfile by rememberUpdatedState(onOpenUserProfile)
    val mentionHandler = remember(viewModel) {
        object : MentionHandler {
            override fun openUsername(username: String) = viewModel.openUsername(username)
            // Упоминание по имени — в профиль пользователя, оттуда можно написать
            override fun openUser(userId: Long) = openUserProfile(userId)
        }
    }
    BackHandler(enabled = selection.isNotEmpty(), onBack = viewModel::clearSelection)
    val replied by viewModel.replied.collectAsStateWithLifecycle()
    val scrollTarget by viewModel.scrollTarget.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    deleting?.let { DeleteDialog(it, viewModel::confirmDelete, viewModel::cancelDelete) }
    var viewer by remember { mutableStateOf<Pair<List<ViewerItem>, Int>?>(null) }
    val pickedPhotos by viewModel.pickedPhotos.collectAsStateWithLifecycle()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_PHOTOS)) {
        viewModel.onPhotosPicked(it)
    }
    if (pickedPhotos.isNotEmpty()) SendPhotosDialog(pickedPhotos, viewModel::sendPhotos, viewModel::cancelPhotos)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::sendFile)
    }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.openFile.collect { openFile(context, it) } }
    viewer?.let { (items, index) -> MediaViewer(items, index) { viewer = null } }
    val stickerSetTitle by viewModel.stickerSetTitle.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val reactionEffect by viewModel.reactionEffect.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    stickerSetTitle?.let { StickerSetDialog(it, viewModel::dismissStickerSet) }
    CompositionLocalProvider(LocalMentionHandler provides mentionHandler) {
        ChatContent(
            chat, state, senders, onBack,
            header = header,
            onOpenProfile = { chat?.let { onOpenProfile(it.id) } },
            onOpenScheduled = { chat?.let { onOpenScheduled(it.id) } },
            selectionBar = selectionActions?.let { actions ->
                {
                    SelectionBar(
                        actions,
                        onClose = viewModel::clearSelection,
                        onForward = { ids ->
                            viewModel.clearSelection()
                            chat?.let { onForward(it.id, ids) }
                        },
                        onDelete = viewModel::requestDeleteSelected,
                    )
                }
            }, viewModel::loadOlder, viewModel::loadNewer,
            onVisibleMessagesChange = viewModel::onVisibleMessagesChange,
            pinned = pinned,
            onPinnedClick = viewModel::onPinnedClick,
            composer = if (canWrite) {
                ComposerState(
                    draft, sendError, replyTo, editing,
                    viewModel::onDraftChange, viewModel::onFormat, viewModel::send, viewModel::cancelReply, viewModel::cancelEdit,
                    mentionSuggestions, viewModel::onMentionQuery, viewModel::pickMention,
                    linkPreview, viewModel::dismissLinkPreview,
                    onAttachPhoto = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onAttachFile = { filePicker.launch(arrayOf("*/*")) },
                    voice = VoiceInput(
                        state = recorderState,
                        onStart = viewModel::startRecording,
                        onLock = viewModel::lockRecording,
                        onRelease = viewModel::releaseRecording,
                        onStop = viewModel::stopRecordingForPreview,
                        onSend = viewModel::sendRecording,
                        onDiscard = viewModel::discardRecording,
                        onTogglePreview = viewModel::toggleRecordingPreview,
                    ),
                    sendMenu = SendMenu(
                        onSilent = viewModel::sendSilently,
                        onSchedule = viewModel::sendScheduled,
                        whenOnline = canScheduleWhenOnline,
                    ),
                    silent = chat?.takeIf { it.kind == ChatKind.Channel }?.defaultSilent,
                    onToggleSilent = viewModel::toggleDefaultSilent,
                )
            } else {
                null
            },
            interactions = MessageInteractions(
                menu = menu,
                onOpenMenu = viewModel::openMenu,
                onDismissMenu = viewModel::closeMenu,
                onReact = viewModel::toggleReaction,
                onPin = viewModel::setPinned,
                onDoubleTap = viewModel::reactDefault,
                onReply = if (canWrite) viewModel::startReply else null,
                onEdit = viewModel::startEdit,
                onDelete = viewModel::requestDelete,
                onForward = { message ->
                    viewModel.closeMenu()
                    chat?.let { onForward(it.id, listOf(message.id)) }
                },
                onOpenChat = onOpenChat,
                selection = selection,
                onToggleSelect = viewModel::toggleSelection,
                onStartSelection = viewModel::startSelection,
                onOpenMedia = { items, index -> viewer = items to index },
                uploadProgress = viewModel::uploadProgress,
                onCancelSending = viewModel::cancelSending,
                downloads = downloads,
                onDocumentClick = viewModel::onDocumentClick,
                onStickerClick = viewModel::onStickerClick,
                audio = audio,
                onAudioToggle = viewModel::onAudioToggle,
                onAudioSeek = viewModel::onAudioSeek,
                reactionEffect = reactionEffect,
                onReactionEffectDone = viewModel::onReactionEffectDone,
                replied = replied,
                onJumpTo = viewModel::jumpTo,
                scrollTarget = scrollTarget,
                onScrolledToTarget = viewModel::onScrolledToTarget,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    chat: Chat?,
    state: ChatHistory.State,
    senders: Map<Sender, SenderInfo>,
    onBack: () -> Unit,
    header: HeaderInfo,
    onOpenProfile: () -> Unit,
    /** Значок отложенных в шапке, пока они есть. */
    onOpenScheduled: () -> Unit = {},
    /** Шапка режима выбора; `null` — обычная шапка чата. */
    selectionBar: (@Composable () -> Unit)?,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onVisibleMessagesChange: (List<Long>) -> Unit,
    composer: ComposerState?,
    interactions: MessageInteractions,
    pinned: PinnedBanner? = null,
    onPinnedClick: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = { if (composer != null && chat != null) Composer(composer, senders) },
        topBar = {
            Column {
                if (selectionBar != null) {
                    selectionBar()
                } else {
                    TopAppBar(
                        colors = reachBarColors(),
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                            }
                        },
                        title = { if (chat != null) ChatHeader(chat, header, senders, onOpenProfile) },
                        actions = {
                            if (chat?.hasScheduledMessages == true) {
                                IconButton(onClick = onOpenScheduled) {
                                    Icon(painterResource(R.drawable.ic_schedule), contentDescription = stringResource(R.string.scheduled_title))
                                }
                            }
                        },
                    )
                }
                if (pinned != null) PinnedBar(pinned, onPinnedClick)
                NowPlayingBar()
            }
        },
    ) { padding ->
        val scheme = MaterialTheme.colorScheme
        val modifier = Modifier.fillMaxSize().padding(padding).background(
            Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.tertiaryContainer)),
        )
        if (!state.opened || chat == null) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            MessageList(chat, state, senders, interactions, onLoadOlder, onLoadNewer, onVisibleMessagesChange, modifier)
        }
    }
}

/** Что можно делать с сообщениями в ленте: меню, ответ, переход к исходному. */
private class MessageInteractions(
    /** Открытое сейчас меню. */
    val menu: MessageMenu?,
    val onOpenMenu: (Message) -> Unit,
    val onDismissMenu: () -> Unit,
    /** Поставить или снять реакцию (меню, чип под сообщением). */
    val onReact: (Message, ReactionKey) -> Unit,
    /** Двойной тап по сообщению — реакция по умолчанию. */
    val onDoubleTap: (Message) -> Unit,
    /** Закрепить (`true`) или открепить сообщение. */
    val onPin: (Message, Boolean) -> Unit,
    /** `null` — писать нельзя, ответить тоже. */
    val onReply: ((Message) -> Unit)?,
    val onEdit: (Message) -> Unit,
    val onDelete: (MessageMenu) -> Unit,
    val onForward: (Message) -> Unit,
    /** Тап по источнику пересланного: чат или канал. */
    val onOpenChat: (Long) -> Unit,
    /** Выбранные сообщения; не пусто — режим выбора: тап отмечает, свайп и меню выключены. */
    val selection: Set<Long>,
    val onToggleSelect: (Message) -> Unit,
    val onStartSelection: (MessageMenu) -> Unit,
    /** Тап по фото или видео: полноэкранный просмотр с листанием по [items]. */
    val onOpenMedia: (items: List<ViewerItem>, index: Int) -> Unit,
    /** Прогресс загрузки своего отправляющегося файла. */
    val uploadProgress: (TdFile) -> Flow<Float>,
    val onCancelSending: (Message) -> Unit,
    /** Состояние скачиваемых документов по id файла. */
    val downloads: Map<Int, FileState>,
    val onDocumentClick: (DocumentInfo) -> Unit,
    /** Исходные сообщения ответов вне загруженного отрезка (см. [ChatHistory.replied]). */
    val replied: Map<Long, Message?>,
    val onJumpTo: (Long) -> Unit,
    val scrollTarget: Long?,
    val onScrolledToTarget: () -> Unit,
    /** Тап по стикеру: название пака. */
    val onStickerClick: (StickerInfo) -> Unit = {},
    /** Что играет в аудиоплеере (только этот чат). */
    val audio: AudioState = AudioState(),
    val onAudioToggle: (Message) -> Unit = {},
    val onAudioSeek: (Float) -> Unit = {},
    /** Эффект своей реакции: (сообщение, реакция). */
    val reactionEffect: Pair<Long, ReactionKey>? = null,
    val onReactionEffectDone: () -> Unit = {},
) {
    /** Меню, если оно открыто именно для [message]. */
    fun menuFor(message: Message): MessageMenu? = menu?.takeIf { it.message.id == message.id }

    /** Ответ свайпом: только на уже отправленные сообщения и если писать можно. */
    fun replyAction(message: Message): (() -> Unit)? {
        if (selection.isNotEmpty()) return null
        val reply = onReply ?: return null
        return if (message.sendingState == null) ({ reply(message) }) else null
    }
}

@Composable
private fun ChatHeader(chat: Chat, header: HeaderInfo, senders: Map<Sender, SenderInfo>, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.open_profile), onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(chat, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(chatTitle(chat), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // У Избранного нет статуса и «печатает»
            if (chat.isSavedMessages) return@Column
            val activity = activityText(header.activities, withNames = chat.kind == ChatKind.Group) { sender ->
                senders[sender]?.name?.substringBefore(' ').orEmpty()
            }
            Text(
                activity ?: headerSubtitle(chat, header),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.88f),
                fontWeight = if (activity != null) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Когда никто ничего не делает: статус собеседника, участники группы, подписчики канала. */
@Composable
private fun headerSubtitle(chat: Chat, header: HeaderInfo): String = when (chat.kind) {
    ChatKind.Private, ChatKind.Secret ->
        header.userStatus?.let { userStatusText(it) } ?: stringResource(R.string.chat_kind_private)
    ChatKind.Group -> if (header.memberCount > 0) {
        val members = pluralStringResource(R.plurals.members, header.memberCount, header.memberCount)
        if (header.onlineCount > 1) stringResource(R.string.group_online, members, header.onlineCount) else members
    } else {
        stringResource(R.string.chat_kind_group)
    }
    ChatKind.Channel -> if (header.memberCount > 0) {
        pluralStringResource(R.plurals.subscribers, header.memberCount, header.memberCount)
    } else {
        stringResource(R.string.chat_kind_channel)
    }
}

/** Элементы ленты снизу вверх: сообщения и разделитель «Непрочитанные». */
private sealed interface Item {
    val key: Any

    /**
     * Сообщение или альбом. У альбома [message] — самое старое из загруженных (к нему подпись и ответ),
     * [album] — все его сообщения от старых к новым; у обычного сообщения — только оно само.
     */
    data class Msg(val message: Message, val showSender: Boolean, val album: List<Message> = listOf(message)) : Item {
        override val key: Any get() = message.id
    }

    data object UnreadDivider : Item {
        override val key: Any get() = "unread"
    }
}

private fun buildItems(state: ChatHistory.State, isGroup: Boolean): List<Item> = buildList {
    val messages = state.messages
    var i = 0
    while (i < messages.size) {
        // Сообщения альбома идут подряд; лента — от новых к старым
        var end = i + 1
        val albumId = messages[i].mediaAlbumId
        if (albumId != 0L) while (end < messages.size && messages[end].mediaAlbumId == albumId) end++
        val group = messages.subList(i, end)
        val message = group.last()
        val older = messages.getOrNull(end)
        // В группе имя автора — над первым сообщением серии
        val showSender = isGroup && !message.isOutgoing &&
            (older?.sender != message.sender || older.content is MessageBody.Service)
        add(Item.Msg(message, showSender, group.reversed()))
        if (group.any { it.id == state.firstUnreadId }) add(Item.UnreadDivider)
        i = end
    }
}

@Composable
private fun MessageList(
    chat: Chat,
    state: ChatHistory.State,
    senders: Map<Sender, SenderInfo>,
    interactions: MessageInteractions,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onVisibleMessagesChange: (List<Long>) -> Unit,
    modifier: Modifier,
) {
    val isChannel = chat.kind == ChatKind.Channel
    val items = buildItems(state, isGroup = chat.kind == ChatKind.Group)
    val listState = rememberLazyListState()
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }
    ScrollToUnreadOnce(listState, items)
    FollowNewMessages(listState, state)
    ScrollToTarget(listState, items, interactions.scrollTarget, interactions.onScrolledToTarget)
    val visibleChanged by rememberUpdatedState(onVisibleMessagesChange)
    // Ключ альбома — id одного сообщения, а видны все его сообщения
    val albumIds by rememberUpdatedState(
        items.filterIsInstance<Item.Msg>().filter { it.album.size > 1 }.associate { it.key to it.album.map(Message::id) },
    )
    LaunchedEffect(listState) {
        // Ключи сообщений — их id (Long); разделитель и загрузчик отсеиваются
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMap { info ->
                albumIds[info.key] ?: listOfNotNull(info.key as? Long)
            }
        }
            .distinctUntilChanged()
            .collect { visibleChanged(it) }
    }
    PagingEffects(listState, state, onLoadOlder, onLoadNewer)
    // reverseLayout: индекс 0 — самое новое сообщение, внизу экрана
    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is Item.Msg -> when {
                    item.message.content is MessageBody.Service -> ServiceMessage(item.message.content.kind)
                    isChannel -> ChannelPost(item.message, interactions, item.album) {
                        ForwardHeader(item.message, senders, interactions)
                        ReplyHeader(item.message, byId, senders, interactions)
                    }
                    else -> MessageBubble(
                        item.message,
                        sender = if (item.showSender) senders[item.message.sender] else null,
                        interactions = interactions,
                        album = item.album,
                    ) {
                        ForwardHeader(item.message, senders, interactions)
                        ReplyHeader(item.message, byId, senders, interactions)
                    }
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

/** «Переслано от …»; тап открывает исходный чат или канал, если он известен. */
@Composable
private fun ForwardHeader(message: Message, senders: Map<Sender, SenderInfo>, interactions: MessageInteractions) {
    val author = message.forwardedFrom ?: return
    val source = ((author as? MessageAuthor.Known)?.sender as? Sender.Chat)?.id?.takeIf { Sender.Chat(it) in senders }
    val name = when (author) {
        is MessageAuthor.Known -> senders[author.sender]?.name.orEmpty().ifBlank { stringResource(R.string.chat_deleted_account) }
        is MessageAuthor.Hidden -> author.name
    }
    Text(
        stringResource(R.string.forwarded_from, name),
        modifier = Modifier
            .padding(bottom = 2.dp)
            .then(if (source != null) Modifier.clickable { interactions.onOpenChat(source) } else Modifier),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Блок ответа над текстом сообщения; тап ведёт к исходному. */
@Composable
private fun ReplyHeader(
    message: Message,
    loaded: Map<Long, Message>,
    senders: Map<Sender, SenderInfo>,
    interactions: MessageInteractions,
) {
    val reply = message.replyTo ?: return
    val inChat = !reply.isExternal
    val original = if (inChat) loaded[reply.messageId] ?: interactions.replied[reply.messageId] else null
    val resolved = !inChat || reply.messageId in loaded || reply.messageId in interactions.replied
    ReplyBlock(reply, original, resolved, senders) {
        if (inChat && original != null) interactions.onJumpTo(reply.messageId)
    }
}

/** Переход к сообщению (тап по ответу): прокручиваем к нему, когда оно появилось в ленте. */
@Composable
private fun ScrollToTarget(listState: LazyListState, items: List<Item>, target: Long?, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(target, items) {
        if (target == null) return@LaunchedEffect
        val index = items.indexOfFirst { it is Item.Msg && it.album.any { m -> m.id == target } }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index)
        done()
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
private fun MessageBubble(
    message: Message,
    sender: SenderInfo?,
    interactions: MessageInteractions,
    album: List<Message>,
    reply: @Composable () -> Unit,
) {
    val outgoing = message.isOutgoing
    // Стикер рисуется без пузыря: прозрачный фон, время — на плашке
    val bare = album.size <= 1 && message.content.isBare()
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectionHighlight(message, interactions)
            .swipeToReply(interactions.replyAction(message))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(bubbleShape)
                    .background(
                        when {
                            bare -> Color.Transparent
                            outgoing -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                    )
                    .messageLongPress(message, interactions)
                    .padding(horizontal = if (bare) 0.dp else 12.dp, vertical = if (bare) 2.dp else 8.dp),
            ) {
                MessagePopup(message, interactions)
                if (sender != null) {
                    Text(
                        sender.name.ifBlank { stringResource(R.string.chat_deleted_account) },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                reply()
                if (album.size > 1) AlbumContent(album, interactions) else MessageContent(message, interactions)
                ReactionChips(message.reactions, onClick = reactionClick(message, interactions))
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .then(
                            if (bare) {
                                Modifier
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            } else {
                                Modifier
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        messageTimeLabel(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (outgoing) SendingStatus(message.sendingState)
                }
            }
            // Эффект своей реакции — поверх пузыря, не меняя его размер
            interactions.reactionEffect?.takeIf { it.first == message.id }?.let { (_, key) ->
                ReactionEffect(
                    key,
                    onDone = interactions.onReactionEffectDone,
                    modifier = Modifier
                        .matchParentSize()
                        .wrapContentSize(unbounded = true)
                        .size(180.dp),
                )
            }
        }
    }
}

@Composable
private fun MessagePopup(message: Message, interactions: MessageInteractions) {
    MessageMenuPopup(
        interactions.menuFor(message),
        interactions.onDismissMenu,
        onReply = { interactions.onReply?.invoke(it) },
        onEdit = interactions.onEdit,
        onDelete = interactions.onDelete,
        onForward = interactions.onForward,
        onSelect = interactions.onStartSelection,
        onReact = interactions.onReact,
        onPin = interactions.onPin,
    )
}

/** Выбранное сообщение подсвечивается на всю ширину; в режиме выбора тап по строке отмечает его. */
@Composable
private fun Modifier.selectionHighlight(message: Message, interactions: MessageInteractions): Modifier {
    if (interactions.selection.isEmpty()) return this
    val selected = message.id in interactions.selection
    return this
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
        .clickable { interactions.onToggleSelect(message) }
}

/** Долгое нажатие на сообщение открывает меню действий. */
@Composable
private fun Modifier.messageLongPress(
    message: Message,
    interactions: MessageInteractions,
    onClick: () -> Unit = {},
): Modifier {
    val haptics = LocalHapticFeedback.current
    val selecting = interactions.selection.isNotEmpty()
    return combinedClickable(
        onClick = { if (selecting) interactions.onToggleSelect(message) else onClick() },
        onDoubleClick = if (selecting) null else ({ interactions.onDoubleTap(message) }),
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (selecting) interactions.onToggleSelect(message) else interactions.onOpenMenu(message)
        },
    )
}

/** В режиме выбора чипы не нажимаются: тап отмечает сообщение. */
private fun reactionClick(message: Message, interactions: MessageInteractions): ((ReactionKey) -> Unit)? =
    if (interactions.selection.isEmpty()) ({ key -> interactions.onReact(message, key) }) else null

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
private fun ChannelPost(
    message: Message,
    interactions: MessageInteractions,
    album: List<Message>,
    reply: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectionHighlight(message, interactions)
            .swipeToReply(interactions.replyAction(message))
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .messageLongPress(message, interactions)
            .padding(12.dp),
    ) {
        MessagePopup(message, interactions)
        reply()
        if (album.size > 1) AlbumContent(album, interactions) else MessageContent(message, interactions)
        ReactionChips(message.reactions, onClick = reactionClick(message, interactions))
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
                messageTimeLabel(message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageContent(message: Message, interactions: MessageInteractions) {
    when (val body = message.content) {
        is MessageBody.Text -> {
            RichTextView(body.text)
            body.preview?.let { LinkPreviewCard(it) }
        }
        is MessageBody.Photo -> {
            val spoiler = rememberMediaSpoiler(message.id, body.hasSpoiler)
            Box(contentAlignment = Alignment.Center) {
                PhotoThumbnail(
                    body.photo,
                    Modifier
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .spoilerBlur(spoiler.hidden)
                        .messageLongPress(message, interactions) {
                            spoiler.open { interactions.onOpenMedia(listOf(ViewerItem.Photo(body.photo)), 0) }
                        },
                )
                MediaSpoilerCover(spoiler.hidden, Modifier.matchParentSize().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)))
                UploadOverlay(message, body.photo.full, interactions)
            }
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.Video -> {
            val spoiler = rememberMediaSpoiler(message.id, body.hasSpoiler)
            Box(contentAlignment = Alignment.Center) {
                VideoThumbnail(
                    body.video,
                    Modifier
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .spoilerBlur(spoiler.hidden)
                        .messageLongPress(message, interactions) {
                            spoiler.open { interactions.onOpenMedia(listOf(ViewerItem.Video(body.video)), 0) }
                        },
                )
                MediaSpoilerCover(spoiler.hidden, Modifier.matchParentSize().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)))
                UploadOverlay(message, body.video.file, interactions)
            }
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.Voice -> {
            VoiceNoteView(
                body.voice,
                isListened = body.isListened || message.isOutgoing,
                playback = interactions.audio.takeIf { it.track?.messageId == message.id },
                onToggle = { interactions.onAudioToggle(message) },
                onSeek = interactions.onAudioSeek,
                modifier = Modifier.messageLongPress(message, interactions),
            )
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.Audio -> {
            AudioRow(
                body.audio,
                playback = interactions.audio.takeIf { it.track?.messageId == message.id },
                onToggle = { interactions.onAudioToggle(message) },
                onSeek = interactions.onAudioSeek,
                modifier = Modifier.messageLongPress(message, interactions) { interactions.onAudioToggle(message) },
            )
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.AnimatedEmoji -> {
            val (replay, onReplay) = rememberReplay()
            AnimatedEmojiView(
                body.emoji,
                body.animation,
                replay,
                Modifier.messageLongPress(message, interactions, onClick = onReplay),
            )
        }
        is MessageBody.Sticker -> StickerView(
            body.sticker,
            Modifier.messageLongPress(message, interactions) { interactions.onStickerClick(body.sticker) },
        )
        is MessageBody.Animation -> {
            val spoiler = rememberMediaSpoiler(message.id, body.hasSpoiler)
            Box(contentAlignment = Alignment.Center) {
                AnimationThumbnail(
                    body.animation,
                    Modifier
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .spoilerBlur(spoiler.hidden)
                        .messageLongPress(message, interactions) {
                            spoiler.open { interactions.onOpenMedia(listOf(ViewerItem.Animation(body.animation)), 0) }
                        },
                )
                MediaSpoilerCover(spoiler.hidden, Modifier.matchParentSize().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)))
            }
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.Document -> {
            Box(contentAlignment = Alignment.CenterStart) {
                DocumentRow(
                    body.document,
                    interactions.downloads[body.document.file.id],
                    Modifier.messageLongPress(message, interactions) { interactions.onDocumentClick(body.document) },
                )
                UploadOverlay(message, body.document.file, interactions)
            }
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        is MessageBody.Unsupported -> {
            UnsupportedStub(body.kind)
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        // Служебные рисуются отдельно, без пузыря (ServiceMessage)
        is MessageBody.Service -> Text(serviceKindText(body.kind), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Альбом: сетка до 10 элементов. 2 — в ряд, 3 — большой сверху и два под ним, 4 — 2×2,
 * дальше — ряд из двух и ряды по три. Подпись — первая непустая.
 */
@Composable
private fun AlbumContent(album: List<Message>, interactions: MessageInteractions) {
    val media = album.mapNotNull { it.content.viewerItem() }
    // Альбом файлов (или музыки) — столбиком, как отдельные сообщения
    if (media.isEmpty()) {
        album.forEach { MessageContent(it, interactions) }
        return
    }
    val rows: List<List<Message>> = when (album.size) {
        2 -> listOf(album)
        3 -> listOf(album.take(1), album.drop(1))
        4 -> album.chunked(2)
        else -> listOf(album.take(2)) + album.drop(2).chunked(3)
    }
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { message ->
                    val cell = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                    when (val body = message.content) {
                        is MessageBody.Photo -> Box(cell, contentAlignment = Alignment.Center) {
                            val spoiler = rememberMediaSpoiler(message.id, body.hasSpoiler)
                            PhotoThumbnail(
                                body.photo,
                                Modifier.spoilerBlur(spoiler.hidden).messageLongPress(message, interactions) {
                                    spoiler.open {
                                        interactions.onOpenMedia(media, media.indexOf(ViewerItem.Photo(body.photo)).coerceAtLeast(0))
                                    }
                                },
                                square = row.size > 1,
                            )
                            MediaSpoilerCover(spoiler.hidden, Modifier.matchParentSize())
                            UploadOverlay(message, body.photo.full, interactions)
                        }
                        is MessageBody.Video -> Box(cell, contentAlignment = Alignment.Center) {
                            val spoiler = rememberMediaSpoiler(message.id, body.hasSpoiler)
                            VideoThumbnail(
                                body.video,
                                Modifier.spoilerBlur(spoiler.hidden).messageLongPress(message, interactions) {
                                    spoiler.open {
                                        interactions.onOpenMedia(media, media.indexOf(ViewerItem.Video(body.video)).coerceAtLeast(0))
                                    }
                                },
                                square = row.size > 1,
                            )
                            MediaSpoilerCover(spoiler.hidden, Modifier.matchParentSize())
                            UploadOverlay(message, body.video.file, interactions)
                        }
                        else -> Box(
                            cell
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .messageLongPress(message, interactions),
                            contentAlignment = Alignment.Center,
                        ) {
                            val kind = (body as? MessageBody.Unsupported)?.kind ?: ContentKind.Unknown
                            Text(contentKindText(kind), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    album.firstNotNullOfOrNull { m ->
        when (val body = m.content) {
            is MessageBody.Photo -> body.caption
            is MessageBody.Video -> body.caption
            is MessageBody.Animation -> body.caption
            is MessageBody.Audio -> body.caption
            is MessageBody.Unsupported -> body.caption
            is MessageBody.Document -> body.caption
            else -> null
        }?.takeUnless { it.isBlank() }
    }?.let { RichTextView(it) }
}

/** Своё отправляющееся медиа: кольцо загрузки и ✕ — отменить отправку. */
@Composable
private fun UploadOverlay(message: Message, file: TdFile?, interactions: MessageInteractions) {
    if (message.sendingState != SendingState.Pending || file == null) return
    val progress by remember(file) { interactions.uploadProgress(file) }.collectAsState(initial = 0f)
    Box(
        Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(44.dp), color = Color.White, strokeWidth = 2.dp)
        IconButton(onClick = { interactions.onCancelSending(message) }) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.upload_cancel), tint = Color.White)
        }
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

/** «изменено 14:05» у отредактированных, иначе просто время. */
@Composable
private fun messageTimeLabel(message: Message): String {
    val time = formatMessageTime(message.date)
    return if (message.editDate > 0) "${stringResource(R.string.message_edited)} $time" else time
}

private const val MAX_PICKED_PHOTOS = 10

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun formatMessageTime(unixSeconds: Int): String =
    Instant.ofEpochSecond(unixSeconds.toLong()).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

@Preview
@Composable
private fun ChatPreview() {
    val now = (System.currentTimeMillis() / 1000).toInt()
    val chat = Chat(1, ChatKind.Group, GroupRef.Basic(1), "Семья", null, emptyMap(), 1, 2, null, 3, true)
    val messages = listOf(
        Message(3, Sender.User(2), now, false, MessageBody.Text(RichText("И вам 🙂")), "", 0),
        Message(2, Sender.User(1), now - 60, true, MessageBody.Text(RichText("Всем привет!", listOf(TextSpan(0, 4, SpanKind.Bold)))), "", 0, SendingState.Pending),
        Message(1, Sender.User(3), now - 120, false, MessageBody.Unsupported(ContentKind.Photo, RichText("Море")), "", 0),
    )
    val senders = mapOf<Sender, SenderInfo>(
        Sender.User(2) to SenderInfo("Аня", null, 1),
        Sender.User(3) to SenderInfo("Папа", null, 3),
    )
    ReachTheme {
        ChatContent(
            chat,
            ChatHistory.State(messages, reachedOldest = true, reachedNewest = true, firstUnreadId = 3, opened = true),
            senders, onBack = {}, header = HeaderInfo(), onOpenProfile = {}, selectionBar = null, onLoadOlder = {}, onLoadNewer = {}, onVisibleMessagesChange = {},
            composer = ComposerState(RichText("Как дела?"), null, null, null, {}, { _, _, _ -> }, {}, {}, {}),
            interactions = MessageInteractions(
                null, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, {}, emptySet(), {}, {}, { _, _ -> }, { emptyFlow() }, {}, emptyMap(), {},
                emptyMap(), {}, null, {},
            ),
        )
    }
}

/** Фото и видео альбома листаются в просмотре вместе. */
private fun MessageBody.viewerItem(): ViewerItem? = when (this) {
    is MessageBody.Photo -> ViewerItem.Photo(photo)
    is MessageBody.Video -> ViewerItem.Video(video)
    else -> null
}

/** Содержимое без пузыря: стикеры и большие эмодзи. */
private fun MessageBody.isBare(): Boolean = this is MessageBody.Sticker || this is MessageBody.AnimatedEmoji
