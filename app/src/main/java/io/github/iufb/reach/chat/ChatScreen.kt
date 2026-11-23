package io.github.iufb.reach.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import io.github.iufb.reach.media.RecorderState
import io.github.iufb.reach.media.VoiceRecording
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import io.github.iufb.reach.fonts.FontKind
import android.os.SystemClock
import io.github.iufb.reach.media.VideoNoteCamera
import io.github.iufb.reach.photoedit.PhotoEditorDialog
import androidx.compose.runtime.collectAsState
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.chats.DocumentInfo
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import io.github.iufb.reach.ui.format.Dates
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.iufb.reach.stickers.StickerPanel
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.presence.activityText
import io.github.iufb.reach.presence.userStatusText
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.chats.emojiOnlyCount
import io.github.iufb.reach.chats.TextSpan
import io.github.iufb.reach.chats.contentKindText
import io.github.iufb.reach.chats.serviceText
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.BubbleShape
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.TailWidth
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.components.rememberLiveBlurForAnimatedContent
import io.github.iufb.reach.stickers.StickerSetSheet
import io.github.iufb.reach.ui.components.glass
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.rememberTextMeasurer

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onForward: (fromChatId: Long, messageIds: List<Long>) -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenProfile: (chatId: Long) -> Unit,
    onOpenUserProfile: (userId: Long) -> Unit,
    onOpenScheduled: (chatId: Long) -> Unit,
    onOpenThread: (chatId: Long, threadId: Long) -> Unit = { _, _ -> },
    /** Поиск по хэштегу из сообщения. */
    onSearch: (query: String) -> Unit = {},
    /** Только что переслали из этого чата: название получателя для тоста; `null` — тоста нет. */
    forwardedTo: String? = null,
    onForwardedShown: () -> Unit = {},
    /** Настройки → Шрифт: действие «Открыть» в тосте о добавленном шрифте; `null` — без действия. */
    onOpenFontSettings: (() -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(forwardedTo) {
        if (forwardedTo != null) {
            delay(TOAST_MS)
            onForwardedShown()
        }
    }
    val fontNotice by viewModel.fontNotice.collectAsStateWithLifecycle()
    LaunchedEffect(fontNotice) {
        fontNotice?.let {
            delay(FONT_TOAST_MS)
            viewModel.dismissFontNotice(it)
        }
    }
    val scopeInfo by viewModel.scopeInfo.collectAsStateWithLifecycle()
    val openThread by rememberUpdatedState(onOpenThread)
    LaunchedEffect(viewModel) { viewModel.openThread.collect { (chatId, threadId) -> openThread(chatId, threadId) } }
    val scoped = viewModel.scope != ChatScope.Whole
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val senders by viewModel.senders.collectAsStateWithLifecycle()
    val canWrite by viewModel.canWrite.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val focusRequest by viewModel.focusRequest.collectAsStateWithLifecycle()
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
    val search by rememberUpdatedState(onSearch)
    val mentionHandler = remember(viewModel) {
        object : MentionHandler {
            override fun openUsername(username: String) = viewModel.openUsername(username)
            // Упоминание по имени — в профиль пользователя, оттуда можно написать
            override fun openUser(userId: Long) = openUserProfile(userId)
            override fun openHashtag(tag: String) = search(tag)
        }
    }
    BackHandler(enabled = selection.isNotEmpty(), onBack = viewModel::clearSelection)
    // «Назад» при открытом меню закрывает только меню, а не чат
    BackHandler(enabled = menu != null, onBack = viewModel::closeMenu)
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
    val editingPhoto by viewModel.editingPhoto.collectAsStateWithLifecycle()
    if (pickedPhotos.isNotEmpty()) SendPhotosDialog(pickedPhotos, viewModel::sendPhotos, viewModel::editPhoto, viewModel::cancelPhotos)
    editingPhoto?.let { index ->
        pickedPhotos.getOrNull(index)?.let { photo ->
            PhotoEditorDialog(
                initial = photo.edit,
                load = { viewModel.loadPhotoForEdit(index) },
                onDone = { viewModel.applyPhotoEdit(index, it) },
                onCancel = viewModel::cancelPhotoEdit,
            )
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::sendFile)
    }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.openFile.collect { openFile(context, it) } }
    viewer?.let { (items, index) -> MediaViewer(items, index) { viewer = null } }
    val openedStickerSet by viewModel.openedStickerSet.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val reactionEffect by viewModel.reactionEffect.collectAsStateWithLifecycle()
    val emojiEffects by viewModel.emojiEffects.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val camera = remember { VideoNoteCamera(context.applicationContext) }
    val canSendVideoNotes by viewModel.canSendVideoNotes.collectAsStateWithLifecycle()
    val canSendStickers by viewModel.canSendStickers.collectAsStateWithLifecycle()
    var videoMode by rememberSaveable { mutableStateOf(false) }
    val recordingNote = camera.phase == VideoNoteCamera.Phase.Recording
    LaunchedEffect(recordingNote) {
        while (recordingNote) {
            viewModel.sendRecordingVideoNoteAction()
            delay(CHAT_ACTION_INTERVAL_MS)
        }
    }
    openedStickerSet?.let { setId ->
        StickerSetSheet(setId, onDismiss = viewModel::dismissStickerSet, onSend = if (canSendStickers) ({ viewModel.sendSticker(it) }) else null)
    }
    var creatingPoll by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    if (attaching) {
        AttachSheet(
            AttachActions(
                onSendPhotos = viewModel::onPhotosPicked,
                onSystemPicker = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFile = { filePicker.launch(arrayOf("*/*")) },
                // В личных чатах опросов нет
                onPoll = if (chat?.kind == ChatKind.Group || chat?.kind == ChatKind.Channel) ({ creatingPoll = true }) else null,
                maxPhotos = MAX_PICKED_PHOTOS,
            ),
            onDismiss = { attaching = false },
        )
    }
    val pollMaxOptions by viewModel.pollMaxOptions.collectAsStateWithLifecycle()
    if (creatingPoll) {
        CreatePollDialog(
            forceAnonymous = chat?.kind == ChatKind.Channel,
            maxOptions = pollMaxOptions,
            onCreate = {
                creatingPoll = false
                viewModel.sendPoll(it)
            },
            onDismiss = { creatingPoll = false },
        )
    }
    val pollVoters by viewModel.pollVoters.collectAsStateWithLifecycle()
    pollVoters?.let { voters ->
        PollVotersDialog(
            voters,
            onLoadMore = viewModel::loadMoreVoters,
            onOpen = { sender ->
                viewModel.closePollVoters()
                when (sender) {
                    is Sender.User -> onOpenUserProfile(sender.id)
                    is Sender.Chat -> onOpenChat(sender.id)
                }
            },
            onDismiss = viewModel::closePollVoters,
        )
    }
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalMentionHandler provides mentionHandler) {
        ChatContent(
            chat, state, senders, onBack,
            canSendStickers = canSendStickers,
            onSendSticker = viewModel::sendSticker,
            toast = fontNotice?.let { notice -> fontToast(notice, onOpenFontSettings?.let { open -> { viewModel.dismissFontNotice(notice); open() } }) }
                ?: forwardedTo?.let { ChatToastData(stringResource(R.string.forward_done, it)) },
            header = header,
            onOpenProfile = { chat?.let { onOpenProfile(it.id) } },
            onOpenScheduled = { chat?.let { onOpenScheduled(it.id) } },
            scopeTitle = when (viewModel.scope) {
                ChatScope.Whole -> null
                is ChatScope.Thread -> stringResource(R.string.comments_title)
                is ChatScope.Topic -> scopeInfo?.title.orEmpty()
            },
            threadRoot = scopeInfo?.root.orEmpty(),
            selectionBar = selectionActions?.let { actions ->
                SelectionControls(
                    actions,
                    onClose = viewModel::clearSelection,
                    onForward = { ids ->
                        viewModel.clearSelection()
                        chat?.let { onForward(it.id, ids) }
                    },
                    onDelete = viewModel::requestDeleteSelected,
                )
            },
            onLoadOlder = viewModel::loadOlder,
            onLoadNewer = viewModel::loadNewer,
            onVisibleMessagesChange = viewModel::onVisibleMessagesChange,
            pinned = pinned,
            onPinnedClick = viewModel::onPinnedClick,
            unblock = if (chat?.isBlocked == true && chat?.userId != null) viewModel::unblock else null,
            composer = if (canWrite && chat?.isBlocked != true) {
                ComposerState(
                    draft, sendError, replyTo, editing,
                    viewModel::onDraftChange, viewModel::onFormat, viewModel::send, viewModel::cancelReply, viewModel::cancelEdit,
                    focusRequest, mentionSuggestions, viewModel::onMentionQuery, viewModel::pickMention,
                    linkPreview, viewModel::dismissLinkPreview,
                    onAttach = { attaching = true },
                    voice = if (videoMode && canSendVideoNotes) {
                        // Кружок: та же кнопка и полоска записи; «стоп» — предпросмотр в круге над лентой
                        VoiceInput(
                            state = when (camera.phase) {
                                VideoNoteCamera.Phase.Idle -> RecorderState.Idle
                                VideoNoteCamera.Phase.Recorded -> RecorderState.Recorded(
                                    VoiceRecording(camera.recordedPath.orEmpty(), camera.recordedDurationSec, ByteArray(0)),
                                    camera.reviewPlaying,
                                )
                                else -> RecorderState.Recording(
                                    camera.startedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(), 0f, camera.locked,
                                )
                            },
                            onStart = {
                                viewModel.onVideoNoteRecordingStarted()
                                camera.start(viewModel::sendVideoNote)
                            },
                            onLock = camera::lock,
                            onRelease = { camera.finish(send = true) },
                            onStop = camera::stopForReview,
                            onSend = { camera.finish(send = true) },
                            onDiscard = { camera.finish(send = false) },
                            onTogglePreview = camera::toggleReviewPlayback,
                            videoMode = true,
                            onToggleMode = { videoMode = false },
                            previewProgress = { camera.reviewProgress },
                        )
                    } else {
                        VoiceInput(
                            state = recorderState,
                            onStart = viewModel::startRecording,
                            onLock = viewModel::lockRecording,
                            onRelease = viewModel::releaseRecording,
                            onStop = viewModel::stopRecordingForPreview,
                            onSend = viewModel::sendRecording,
                            onDiscard = viewModel::discardRecording,
                            onTogglePreview = viewModel::toggleRecordingPreview,
                            onToggleMode = if (canSendVideoNotes) ({ videoMode = true }) else null,
                            previewProgress = viewModel::recordingPreviewProgress,
                            level = viewModel::recordingLevel,
                        )
                    },
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
                emojiEffects = emojiEffects,
                onAnimatedEmojiClick = viewModel::onAnimatedEmojiClick,
                onOpenContent = viewModel::openContent,
                onEmojiEffectShown = viewModel::onEmojiEffectShown,
                onEmojiEffectDone = viewModel::onEmojiEffectDone,
                replied = replied,
                onJumpTo = viewModel::jumpTo,
                scrollTarget = scrollTarget,
                onScrolledToTarget = viewModel::onScrolledToTarget,
                onVote = viewModel::vote,
                onShowVoters = viewModel::showPollVoters,
                onRetractVote = viewModel::retractVote,
                onStopPoll = viewModel::stopPoll,
                onInstallFont = viewModel::installFont,
                onOpenComments = if (scoped) null else viewModel::openComments,
                senders = senders,
            ),
            recordingOverlay = { composerHeight -> VideoNoteCameraOverlay(camera, composerHeight) },
        )
    }
    }
}

private const val CHAT_ACTION_INTERVAL_MS = 5_000L

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
    /** Тред или топик: заголовок вместо названия чата; `null` — открыт весь чат. */
    scopeTitle: String? = null,
    /** Пост, к которому комментарии: над самым старым комментарием. */
    threadRoot: List<Message> = emptyList(),
    /** Личка с заблокированным: кнопка вместо поля ввода. */
    unblock: (() -> Unit)? = null,
    /** Режим выбора: своя шапка и панель действий вместо поля ввода; `null` — обычный чат. */
    selectionBar: SelectionControls?,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onVisibleMessagesChange: (List<Long>) -> Unit,
    composer: ComposerState?,
    interactions: MessageInteractions,
    pinned: PinnedBanner? = null,
    onPinnedClick: () -> Unit = {},
    /** Короткое уведомление под шапкой («Переслано в …»); `null` — нет. */
    toast: ChatToastData? = null,
    /** `false` — на вкладке «Стикеры» панели текст об ограничении. */
    canSendStickers: Boolean = true,
    /** Тап по стикеру в панели; `null` — вкладка «Стикеры» с заглушкой (превью). */
    onSendSticker: ((StickerInfo) -> Unit)? = null,
    /** Слой записи кружка: над лентой и шапкой, под полем ввода; [Dp] — высота поля. */
    recordingOverlay: @Composable (composerHeight: Dp) -> Unit = {},
) {
    val colors = ReachTheme.colors
    val density = LocalDensity.current
    // Шапка и поле ввода плавают над лентой; их высоты — отступы ленты сверху и снизу
    var topHeight by remember { mutableStateOf(0.dp) }
    var bottomHeight by remember { mutableStateOf(0.dp) }
    // Scaffold больше нет — цвет текста по умолчанию задаём сами (иначе в тёмной теме он серый)
    val emoji = rememberEmojiPanelState()
    val composerShown = composer != null && chat != null && selectionBar == null
    val menuOverlay = remember { MessageMenuOverlayState() }
    val blurPx = with(density) { MENU_BLUR.toPx() }
    // Под шапкой и полем ввода почти всегда что-то анимируется (стикеры, эмодзи, GIF): живое
    // размытие — только на мощных устройствах (#304)
    GlassScope(liveBlur = rememberLiveBlurForAnimatedContent()) {
        CompositionLocalProvider(LocalContentColor provides colors.label, LocalMessageMenuOverlay provides menuOverlay) {
        Box(Modifier.fillMaxSize()) {
        // Лента с шапкой и полем — сверху, под ними клавиатура / панель эмодзи / полоса навигации
        // одним блоком (ChatBottomArea): при их переключении область ленты не меняет размер
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom)))
                // Меню сообщения: весь чат под ним размыт, как в iOS; размытие — по ходу анимации меню
                .graphicsLayer {
                    val radius = blurPx * menuOverlay.progress.value
                    renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
                },
        ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val padding = PaddingValues(top = topHeight + 4.dp, bottom = bottomHeight + 4.dp)
            if (!state.opened || chat == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                MessageList(
                    chat, state, senders, interactions, onLoadOlder, onLoadNewer, onVisibleMessagesChange,
                    Modifier.fillMaxSize().glassSource(), padding, threadRoot,
                )
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { topHeight = with(density) { it.height.toDp() } }) {
                if (selectionBar != null) {
                    // Режим выбора — своя шапка поверх ленты
                    SelectionTopBar(selectionBar)
                } else {
                    ChatTopBar(chat, header, senders, scopeTitle, onBack, onOpenProfile, onOpenScheduled)
                }
                if (pinned != null) PinnedBar(pinned, onPinnedClick)
                NowPlayingBar()
                ChatToast(toast)
            }
            recordingOverlay(bottomHeight)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = with(density) { it.height.toDp() } },
            ) {
                when {
                    unblock != null -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        PillTextButton(stringResource(R.string.unblock), onClick = unblock)
                    }
                    // В режиме выбора поле ввода прячется: внизу действуют над выбранным, а не пишут (#196)
                    composerShown && composer != null -> Composer(composer, senders, emoji)
                    selectionBar != null -> SelectionBottomBar(selectionBar)
                }
            }
        }
        ChatBottomArea(
            emoji,
            panelAllowed = composerShown,
            canSendStickers = canSendStickers,
            stickerContent = { if (onSendSticker != null) StickerPanel(onSend = onSendSticker) else StickerTabPlaceholder() },
        )
        }
        MessageMenuOverlay(menuOverlay, interactions.menu, interactions)
        }
        }
    }
}

/** Тост под шапкой: текст, значок и необязательное действие справа («Открыть»). */
internal data class ChatToastData(
    val text: String,
    val icon: Int = R.drawable.ic_forward,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** Тост о шрифте: «Шрифт добавлен: …» с «Открыть» или текст ошибки импорта. */
@Composable
private fun fontToast(notice: FontNotice, onOpen: (() -> Unit)?): ChatToastData = when (notice) {
    is FontNotice.Added -> ChatToastData(
        text = stringResource(
            R.string.font_toast_added,
            notice.font.name,
            stringResource(if (notice.font.kind == FontKind.Emoji) R.string.font_toast_kind_emoji else R.string.font_toast_kind_text),
        ),
        icon = R.drawable.ic_text,
        actionLabel = onOpen?.let { stringResource(R.string.font_toast_open) },
        onAction = onOpen,
    )
    is FontNotice.Failed -> ChatToastData(notice.message ?: stringResource(R.string.font_toast_failed), icon = R.drawable.ic_text)
}

/** Тост Telegram iOS под шапкой: стеклянная пилюля со значком; появляется и уходит плавно. */
@Composable
private fun ChatToast(toast: ChatToastData?) {
    // Последний тост держим, пока он уезжает
    var last by remember { mutableStateOf(toast) }
    if (toast != null) last = toast
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val shown = last ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.glass(RoundedCornerShape(percent = 50)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(shown.icon), contentDescription = null, Modifier.size(20.dp), tint = ReachTheme.colors.accent)
                Text(
                    shown.text,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReachTheme.colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shown.actionLabel != null && shown.onAction != null) {
                    Text(
                        shown.actionLabel,
                        modifier = Modifier.clickable(onClick = shown.onAction),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ReachTheme.colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val TOAST_MS = 2_500L
/** Тост о шрифте дольше: в нём есть действие. */
private const val FONT_TOAST_MS = 4_000L

/** Насколько размыт чат под меню сообщения. */
private val MENU_BLUR = 18.dp

/**
 * Шапка чата Telegram iOS: «назад» слева, по центру пилюля с названием и статусом («печатает…»),
 * справа аватар — тап по нему или по пилюле открывает профиль. В треде и топике — их название.
 */
@Composable
private fun ChatTopBar(
    chat: Chat?,
    header: HeaderInfo,
    senders: Map<Sender, SenderInfo>,
    scopeTitle: String?,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenScheduled: () -> Unit,
) {
    val title = when {
        chat == null -> null
        scopeTitle != null -> scopeTitle
        else -> chatTitle(chat)
    }
    val subtitle = when {
        chat == null -> null
        scopeTitle != null -> chatTitle(chat)
        chat.isSavedMessages -> null
        else -> activityText(header.activities, withNames = chat.kind == ChatKind.Group) { sender ->
            senders[sender]?.name?.substringBefore(' ').orEmpty()
        } ?: headerSubtitle(chat, header)
    }
    ReachTopBar(
        title = title,
        subtitle = subtitle,
        titlePill = true,
        onTitleClick = if (scopeTitle == null) onOpenProfile else null,
        background = ReachTheme.colors.wallpaperTop,
        navigation = { BackPill(onBack) },
        actions = {
            if (chat?.hasScheduledMessages == true) {
                PillIconButton(
                    painterResource(R.drawable.ic_schedule),
                    stringResource(R.string.scheduled_title),
                    onOpenScheduled,
                    tint = ReachTheme.colors.accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (chat != null && scopeTitle == null) {
                Box(
                    Modifier
                        .size(PillHeight)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.open_profile), onClick = onOpenProfile),
                ) {
                    ChatAvatar(chat, size = PillHeight)
                    if (chat.autoDeleteTime > 0) {
                        Icon(
                            painterResource(R.drawable.ic_timer),
                            contentDescription = stringResource(R.string.auto_delete_enabled),
                            modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                                .background(ReachTheme.colors.cell, CircleShape).padding(2.dp),
                            tint = ReachTheme.colors.accent,
                        )
                    }
                }
            }
        },
    )
}

/** Что можно делать с сообщениями в ленте: меню, ответ, переход к исходному. */
internal class MessageInteractions(
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
    /** Эффекты больших эмодзи по тапу (своему или собеседника). */
    val emojiEffects: List<EmojiEffect> = emptyList(),
    val onAnimatedEmojiClick: (Message) -> Unit = {},
    /** Содержимое открыто (кружок просмотрен): `openMessageContent`. */
    val onOpenContent: (Message) -> Unit = {},
    val onEmojiEffectShown: (EmojiEffect) -> Unit = {},
    val onEmojiEffectDone: (EmojiEffect) -> Unit = {},
    /** Голос в опросе: номера вариантов. */
    val onVote: (Message, List<Int>) -> Unit = { _, _ -> },
    val onShowVoters: (Message, io.github.iufb.reach.chats.PollOptionInfo) -> Unit = { _, _ -> },
    val onRetractVote: (Message) -> Unit = {},
    val onStopPoll: (Message) -> Unit = {},
    /** «Установить как шрифт» у документа .ttf/.otf. */
    val onInstallFont: (Message) -> Unit = {},
    /** Плашка комментариев под постом канала; `null` — уже в треде. */
    val onOpenComments: ((Message) -> Unit)? = null,
    /** Аватарки комментаторов. */
    val senders: Map<Sender, SenderInfo> = emptyMap(),
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
    /**
     * [tail] — последнее (нижнее) в серии от одного отправителя: у пузыря хвостик.
     * [groupStart] — первое (верхнее) в серии: над ним отступ побольше.
     */
    data class Msg(
        val message: Message,
        val showSender: Boolean,
        val album: List<Message> = listOf(message),
        val tail: Boolean = true,
        val groupStart: Boolean = true,
    ) : Item {
        // Ключ — стабильный [Message.listKey], а не id: после отправки id меняется (#295)
        override val key: Any get() = message.listKey
    }

    /** Плашка дня над сообщениями этого дня. */
    data class Day(val day: LocalDate) : Item {
        override val key: Any get() = "day-${day.toEpochDay()}"
    }

    data object UnreadDivider : Item {
        override val key: Any get() = "unread"
    }
}

private fun buildItems(state: ChatHistory.State, isGroup: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Item> = buildList {
    val messages = state.messages
    fun day(m: Message) = Dates.at(m.date.toLong(), zone).toLocalDate()
    // Одна серия: тот же отправитель и направление, тот же день, не служебное
    fun sameSeries(a: Message?, b: Message) = a != null && a.sender == b.sender && a.isOutgoing == b.isOutgoing &&
        a.content !is MessageBody.Service && b.content !is MessageBody.Service && day(a) == day(b)
    var i = 0
    while (i < messages.size) {
        // Сообщения альбома идут подряд; лента — от новых к старым
        var end = i + 1
        val albumId = messages[i].mediaAlbumId
        if (albumId != 0L) while (end < messages.size && messages[end].mediaAlbumId == albumId) end++
        val group = messages.subList(i, end)
        val message = group.last()
        val older = messages.getOrNull(end)
        val newer = messages.getOrNull(i - 1)
        // В группе имя автора — над первым сообщением серии
        val showSender = isGroup && !message.isOutgoing && !sameSeries(older, message)
        add(Item.Msg(message, showSender, group.reversed(), tail = !sameSeries(newer, group.first()), groupStart = !sameSeries(older, message)))
        if (group.any { it.id == state.firstUnreadId }) add(Item.UnreadDivider)
        // Плашка дня — над первым сообщением дня; у самого старого — только когда история загружена до начала
        if (older?.let { day(it) != day(message) } ?: state.reachedOldest) add(Item.Day(day(message)))
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
    contentPadding: PaddingValues,
    threadRoot: List<Message> = emptyList(),
) {
    val isChannel = chat.kind == ChatKind.Channel
    val items = buildItems(state, isGroup = chat.kind == ChatKind.Group)
    val listState = rememberLazyListState()
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }
    ScrollToUnreadOnce(listState, items)
    FollowNewMessages(listState, state)
    // Сообщение, к которому перешли: кратко подсвечивается (#220)
    var flashId by remember { mutableStateOf<Long?>(null) }
    ScrollToTarget(listState, items, interactions.scrollTarget, interactions.onScrolledToTarget) { flashId = it }
    LaunchedEffect(flashId) {
        if (flashId == null) return@LaunchedEffect
        delay(FLASH_MS)
        flashId = null
    }
    val visibleChanged by rememberUpdatedState(onVisibleMessagesChange)
    // Ключ элемента — не id сообщения (#295), а у альбома видны все его сообщения
    val idsByKey by rememberUpdatedState(
        items.filterIsInstance<Item.Msg>().associate { it.key to it.album.map(Message::id) },
    )
    LaunchedEffect(listState) {
        // Разделитель и загрузчик в карте не значатся и отсеиваются
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMap { info -> idsByKey[info.key].orEmpty() }
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
        contentPadding = contentPadding,
        // Bottom: при малом числе сообщений они у поля ввода, а не под шапкой (#213)
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
    ) {
        items(items, key = { it.key }) { item ->
            val flashing = flashId != null && item is Item.Msg && item.album.any { it.id == flashId }
            val flash by animateColorAsState(
                if (flashing) ReachTheme.colors.accent.copy(alpha = FLASH_ALPHA) else Color.Transparent,
                animationSpec = tween(FLASH_FADE_MS),
                label = "flash",
            )
            Box(Modifier.fillMaxWidth().background(flash)) {
            when (item) {
                is Item.Msg -> when {
                    item.message.content is MessageBody.Service -> ServiceMessage(item.message.content)
                    isChannel -> ChannelPost(item.message, interactions, item.album) {
                        ForwardHeader(item.message, senders, interactions)
                        ReplyHeader(item.message, byId, senders, interactions)
                    }
                    else -> MessageBubble(
                        item.message,
                        sender = if (item.showSender) senders[item.message.sender] else null,
                        interactions = interactions,
                        album = item.album,
                        tail = item.tail,
                        groupStart = item.groupStart,
                        read = item.message.id <= chat.lastReadOutboxMessageId,
                    ) {
                        ForwardHeader(item.message, senders, interactions)
                        ReplyHeader(item.message, byId, senders, interactions)
                    }
                }
                Item.UnreadDivider -> UnreadDivider()
                is Item.Day -> DayChip(item.day)
            }
            }
        }
        // Пост, который комментируют, — над всеми комментариями
        if (state.reachedOldest && threadRoot.isNotEmpty()) {
            item(key = "thread-start") { ThreadStartDivider() }
            item(key = "thread-root") {
                val root = threadRoot.first()
                MessageBubble(root, sender = senders[root.sender], interactions = interactions, album = threadRoot) {
                    ForwardHeader(root, senders, interactions)
                }
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

/**
 * Переход к сообщению (тап по ответу, результат поиска): когда оно появилось в ленте, ставим его
 * по центру экрана и отдаём в [onArrived] — там его подсвечивают (#220).
 */
@Composable
private fun ScrollToTarget(
    listState: LazyListState,
    items: List<Item>,
    target: Long?,
    onDone: () -> Unit,
    onArrived: (Long) -> Unit,
) {
    val done by rememberUpdatedState(onDone)
    val arrived by rememberUpdatedState(onArrived)
    LaunchedEffect(target, items) {
        if (target == null) return@LaunchedEffect
        val index = items.indexOfFirst { it is Item.Msg && it.album.any { m -> m.id == target } }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index)
        // Смещения считаются от начала ленты (у reverseLayout — снизу); сдвигаем центр элемента в центр экрана
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            listState.animateScrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
        }
        arrived(target)
        done()
    }
}

private const val FLASH_MS = 1200L
private const val FLASH_FADE_MS = 400
private const val FLASH_ALPHA = 0.22f

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
    tail: Boolean = true,
    groupStart: Boolean = true,
    /** Исходящее прочитано собеседником: две галочки. */
    read: Boolean = false,
    reply: @Composable () -> Unit,
) {
    val colors = ReachTheme.colors
    val outgoing = message.isOutgoing
    // Стикер рисуется без пузыря: прозрачный фон, время — на плашке
    val bare = album.size <= 1 && message.content.isBare()
    val bubbleShape = BubbleShape(outgoing, tail)
    // Эффекты реакций и эмодзи выходят за пузырь — рисуем элемент поверх соседей
    val hasEffect = interactions.reactionEffect?.first == message.id || interactions.emojiEffects.any { it.messageId == message.id }
    CompositionLocalProvider(LocalBubbleStyle provides colors.bubbleStyle(outgoing)) {
    Row(
        modifier = Modifier
            .zIndex(if (hasEffect) 1f else 0f)
            .fillMaxWidth()
            .selectionHighlight(message, interactions, highlight = false)
            .swipeToReply(interactions.replyAction(message))
            .padding(start = 6.dp, end = 6.dp, top = if (groupStart) SERIES_GAP else 0.dp),
        // В режиме выбора кружок слева, а исходящие остаются у правого края
        horizontalArrangement = when {
            interactions.selection.isNotEmpty() && outgoing -> Arrangement.SpaceBetween
            outgoing -> Arrangement.End
            else -> Arrangement.Start
        },
    ) {
        AnimatedVisibility(
            visible = interactions.selection.isNotEmpty(),
            modifier = Modifier.align(Alignment.CenterVertically),
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            SelectionCheck(message.id in interactions.selection)
        }
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .menuAnchor(message)
                    .then(if (bare) Modifier else Modifier.clip(bubbleShape))
                    .background(
                        when {
                            bare -> Color.Transparent
                            outgoing -> colors.bubbleOutgoing
                            else -> colors.bubbleIncoming
                        },
                    )
                    .messageLongPress(message, interactions)
                    // Со стороны хвостика тело пузыря отступает на TailWidth — там место под него
                    .padding(
                        start = if (bare) 0.dp else if (outgoing) 10.dp else 10.dp + TailWidth,
                        end = if (bare) 0.dp else if (outgoing) 10.dp + TailWidth else 10.dp,
                        top = if (bare) 2.dp else 6.dp,
                        bottom = if (bare) 2.dp else 5.dp,
                    ),
            ) {
                if (sender != null) {
                    Text(
                        sender.name.ifBlank { stringResource(R.string.chat_deleted_account) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
                reply()
                val metaColor = when {
                    bare -> colors.onServiceBubble
                    outgoing -> colors.bubbleOutgoingMeta
                    else -> colors.bubbleIncomingMeta
                }
                val timeLabel = messageTimeLabel(message)
                // Время и галочки в строке текста, если там есть место (как в Telegram iOS): в конец текста
                // встаёт невидимый заполнитель их ширины — не влезает в последнюю строку, переносится на новую
                val body = message.content
                val inlineMeta = !bare && album.size <= 1 && body is MessageBody.Text && body.preview == null &&
                    body.bigEmojiCount() == 0 && message.reactions.isEmpty()
                if (inlineMeta) {
                    val metaWidth = metaWidth(timeLabel, withStatus = outgoing)
                    Box {
                        MessageContent(message, interactions, trailingSpace = metaWidth)
                        MessageMeta(timeLabel, message, outgoing, read, metaColor, Modifier.align(Alignment.BottomEnd).offset(y = 3.dp))
                    }
                } else {
                    if (album.size > 1) AlbumContent(album, interactions) else MessageContent(message, interactions)
                    ReactionChips(message.reactions, onClick = reactionClick(message, interactions))
                    MessageMeta(
                        timeLabel, message, outgoing, read, metaColor,
                        Modifier
                        .align(Alignment.End)
                        .then(
                            if (bare) {
                                Modifier
                                    .background(colors.serviceBubble, CircleShape)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            } else {
                                Modifier
                            },
                        ),
                    )
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
}

/**
 * В режиме выбора тап по строке отмечает сообщение. Пузыри отмечаются кружком слева ([SelectionCheck]);
 * [highlight] — подсветка на всю ширину для постов канала, у которых кружку нет места.
 */
@Composable
private fun Modifier.selectionHighlight(message: Message, interactions: MessageInteractions, highlight: Boolean): Modifier {
    if (interactions.selection.isEmpty()) return this
    val selected = message.id in interactions.selection
    return this
        .then(if (highlight && selected) Modifier.background(ReachTheme.colors.accent.copy(alpha = SELECTED_ALPHA)) else Modifier)
        .clickable(remember { MutableInteractionSource() }, indication = null) { interactions.onToggleSelect(message) }
}

private const val SELECTED_ALPHA = 0.18f

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

/** Время и статус отправки внутри пузыря. */
@Composable
private fun MessageMeta(time: String, message: Message, outgoing: Boolean, read: Boolean, color: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(META_GAP), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        if (outgoing) SendingStatus(message.sendingState, read, color)
    }
}

/** Ширина [MessageMeta] с отступом от текста — столько места оставляет заполнитель в конце текста. */
@Composable
private fun metaWidth(time: String, withStatus: Boolean): TextUnit {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return remember(time, withStatus, style, density) {
        val text = with(density) { measurer.measure(time, style).size.width.toDp() }
        val status = if (withStatus) META_GAP + STATUS_ICON else 0.dp
        with(density) { (text + status + META_SPACING).toSp() }
    }
}

/** Отправляется — часы, ошибка — «!», отправлено — галочка, прочитано — две. */
@Composable
private fun SendingStatus(state: SendingState?, read: Boolean, tint: Color) {
    val (icon, description) = when {
        state == SendingState.Pending -> R.drawable.ic_schedule to R.string.chat_status_sending
        state == SendingState.Failed -> null to R.string.chat_status_failed
        read -> R.drawable.ic_check_double to R.string.message_read
        else -> R.drawable.ic_check to R.string.chat_status_sent
    }
    if (icon == null) {
        Text("!", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ReachTheme.colors.destructive)
        return
    }
    Icon(painterResource(icon), stringResource(description), Modifier.size(STATUS_ICON), tint = tint)
}

/** Пост канала: карточка на всю ширину, подпись автора и просмотры внизу. */
@Composable
private fun ChannelPost(
    message: Message,
    interactions: MessageInteractions,
    album: List<Message>,
    reply: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBubbleStyle provides ReachTheme.colors.bubbleStyle(outgoing = false)) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectionHighlight(message, interactions, highlight = true)
            .swipeToReply(interactions.replyAction(message))
            .padding(start = 6.dp, end = 6.dp, top = SERIES_GAP)
            .menuAnchor(message)
            .clip(BubbleShape(outgoing = false, tail = true))
            .background(ReachTheme.colors.bubbleIncoming)
            .messageLongPress(message, interactions)
            .padding(start = 10.dp + TailWidth, end = 10.dp, top = 8.dp, bottom = 6.dp),
    ) {
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
        val openComments = interactions.onOpenComments
        if (message.hasComments && openComments != null && interactions.selection.isEmpty()) {
            CommentsBar(message, interactions.senders) { openComments(message) }
        }
    }
    }
}

/** «N комментариев» с аватарками последних комментаторов; тап открывает тред. */
@Composable
private fun CommentsBar(message: Message, senders: Map<Sender, SenderInfo>, onClick: () -> Unit) {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 8.dp))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        message.recentRepliers.take(3).forEachIndexed { i, sender ->
            val info = senders[sender]
            Avatar(
                info?.name.orEmpty(),
                info?.photo,
                info?.accentColorId ?: 0,
                modifier = Modifier.offset(x = (-8 * i).dp),
                size = 24.dp,
            )
        }
        if (message.recentRepliers.isNotEmpty()) Spacer(Modifier.width(4.dp))
        Text(
            if (message.replyCount > 0) {
                pluralStringResource(R.plurals.comments, message.replyCount, message.replyCount)
            } else {
                stringResource(R.string.comments_leave)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MessageContent(
    message: Message,
    interactions: MessageInteractions,
    /** Место под время в конце текста; `null` — время рисуется отдельной строкой. */
    trailingSpace: TextUnit? = null,
) {
    when (val body = message.content) {
        is MessageBody.Text -> {
            val bigEmoji = body.bigEmojiCount()
            if (bigEmoji > 0) {
                // Только эмодзи — крупно и без пузыря, как большие анимированные
                val size = BIG_EMOJI_SP[bigEmoji - 1]
                RichTextView(
                    body.text,
                    Modifier.messageLongPress(message, interactions),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.3f),
                    customEmojiEm = BIG_CUSTOM_EMOJI_EM,
                )
            } else {
                RichTextView(body.text, trailingSpace = trailingSpace)
                body.preview?.let { LinkPreviewCard(it) }
            }
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
            val effects = interactions.emojiEffects.filter { it.messageId == message.id }
            // Тап собеседника: эмодзи проигрывается заново вместе с эффектом (свой тап уже перезапустил)
            val peerEffect = effects.lastOrNull { it.fromPeer }?.key
            LaunchedEffect(peerEffect) { if (peerEffect != null) onReplay() }
            Box {
                AnimatedEmojiView(
                    body.emoji,
                    body.animation,
                    replay,
                    Modifier.messageLongPress(message, interactions) {
                        onReplay()
                        interactions.onAnimatedEmojiClick(message)
                    },
                )
                for (effect in effects) {
                    key(effect.key) {
                        EmojiEffectView(
                            effect,
                            towardsStart = message.isOutgoing,
                            onShown = interactions.onEmojiEffectShown,
                            onDone = interactions.onEmojiEffectDone,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
        }
        is MessageBody.VideoNote -> {
            val noteState = rememberVideoNoteState(body.note)
            VideoNoteView(
                body.note,
                noteState,
                isViewed = body.isViewed || message.isOutgoing,
                modifier = Modifier.messageLongPress(message, interactions) {
                    // Первый просмотр со звуком входящего кружка — «просмотрено»
                    if (noteState.toggle() && !message.isOutgoing && !body.isViewed) interactions.onOpenContent(message)
                },
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
        is MessageBody.Poll -> PollView(
            body.poll,
            onVote = { ids -> if (interactions.selection.isEmpty()) interactions.onVote(message, ids) },
            onShowVoters = { option -> interactions.onShowVoters(message, option) },
            modifier = Modifier.messageLongPress(message, interactions),
        )
        is MessageBody.Unsupported -> {
            UnsupportedStub(body.kind)
            if (!body.caption.isBlank()) RichTextView(body.caption)
        }
        // Служебные рисуются отдельно, без пузыря (ServiceMessage)
        is MessageBody.Service -> Text(serviceText(body), style = MaterialTheme.typography.bodyMedium)
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

/** Служебная капсула на обоях: служебные сообщения, плашки дней, начало обсуждения. */
@Composable
private fun ServiceChip(text: String) {
    val colors = ReachTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier
                .background(colors.serviceBubble, RoundedCornerShape(percent = 50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onServiceBubble,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ServiceMessage(body: MessageBody.Service) = ServiceChip(serviceText(body))

@Composable
private fun ThreadStartDivider() = ServiceChip(stringResource(R.string.comments_start))

/** «Сегодня», «Вчера», «21 ноября», в прошлые годы — с годом. */
@Composable
private fun DayChip(day: LocalDate) {
    val today = LocalDate.now()
    ServiceChip(
        when (day) {
            today -> stringResource(R.string.date_today)
            today.minusDays(1) -> stringResource(R.string.date_yesterday)
            else -> Dates.day(day, withYear = day.year != today.year)
        },
    )
}

/** «Непрочитанные сообщения» — полоса на всю ширину, как в Telegram iOS. */
@Composable
private fun UnreadDivider() {
    val colors = ReachTheme.colors
    Text(
        stringResource(R.string.chat_unread_divider),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(colors.bar)
            .padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = colors.secondaryLabel,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** «изменено 14:05» у отредактированных, иначе просто время. */
@Composable
private fun messageTimeLabel(message: Message): String {
    val time = Dates.time(message.date.toLong())
    return if (message.editDate > 0) "${stringResource(R.string.message_edited)} $time" else time
}

private const val MAX_PICKED_PHOTOS = 10

/** Отступ над первым сообщением серии; внутри серии — 2 dp. */
private val SERIES_GAP = 6.dp
private val META_GAP = 3.dp
private val STATUS_ICON = 15.dp
/** Отступ времени от последнего слова текста. */
private val META_SPACING = 8.dp

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
private fun MessageBody.isBare(): Boolean =
    this is MessageBody.Sticker || this is MessageBody.AnimatedEmoji || this is MessageBody.VideoNote || (this is MessageBody.Text && bigEmojiCount() > 0)

/** 1–3 эмодзи (обычных и кастомных) без другого текста и превью — показываем крупно; иначе 0. */
private fun MessageBody.Text.bigEmojiCount(): Int =
    if (preview != null) 0 else text.emojiOnlyCount().takeIf { it <= BIG_EMOJI_SP.size } ?: 0

/** Размер крупных эмодзи по их числу: один, два, три. Общий для обычных и кастомных. */
private val BIG_EMOJI_SP = listOf(80.sp, 64.sp, 52.sp)
