package io.github.iufb.reach.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.files.DocumentDownloads
import io.github.iufb.reach.fonts.FontImportException
import io.github.iufb.reach.fonts.FontStore
import io.github.iufb.reach.media.AudioPlayer
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.media.AudioTrack
import io.github.iufb.reach.media.RecorderState
import io.github.iufb.reach.media.VoiceRecorder
import io.github.iufb.reach.media.VoiceRecording
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.chats.toFormattedText
import android.net.Uri
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.presence.PresenceRepository
import io.github.iufb.reach.chats.DocumentInfo
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.MediaImporter
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import io.github.iufb.reach.files.ImportedImage
import io.github.iufb.reach.photoedit.PhotoEdit
import io.github.iufb.reach.photoedit.PhotoEditRenderer
import io.github.iufb.reach.photoedit.RenderedPhoto
import android.graphics.Bitmap
import io.github.iufb.reach.media.describeVideoNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import io.github.iufb.reach.chats.LinkPreview
import io.github.iufb.reach.chats.toModel
import io.github.iufb.reach.chats.replaceRange
import io.github.iufb.reach.chats.toggle
import io.github.iufb.reach.chats.trimmed
import io.github.iufb.reach.chats.withEditedText
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.MemberStatus
import io.github.iufb.reach.chats.canWrite
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.ChatRoute
import io.github.iufb.reach.users.UserStatus
import io.github.iufb.reach.users.UsersRepository
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import io.github.iufb.reach.notifications.VisibleChats
import io.github.iufb.reach.stickers.canSendStickers
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Тред комментариев или топик. [title] — название топика; [root] — пост, к которому комментарии
 * (показывается над самым старым комментарием).
 */
data class ScopeInfo(
    val title: String?,
    val root: List<Message> = emptyList(),
    val replyCount: Int = 0,
    val isClosed: Boolean = false,
    /** Писать можно, не вступая в группу. */
    val openToNonMembers: Boolean = false,
)

/**
 * Эффект по тапу на большой эмодзи (PRODUCT.md §4.5): TGS поверх чата рядом с сообщением.
 * [fromPeer] — тапнул собеседник (`updateAnimatedEmojiMessageClicked`), [key] — для повторов.
 */
data class EmojiEffect(val key: Long, val messageId: Long, val animation: TdFile, val emoji: String, val fromPeer: Boolean)

/** Фото к отправке: исходник, правки и отредактированная копия (если правки есть). */
data class PickedPhoto(val uri: Uri, val edit: PhotoEdit = PhotoEdit.None, val rendered: RenderedPhoto? = null) {
    /** Что показывать в превью. */
    val preview: Any get() = rendered?.let { java.io.File(it.path) } ?: uri
}

/** Имя и аватарка автора сообщения в группе. */
data class SenderInfo(val name: String, val photo: io.github.iufb.reach.files.TdFile?, val accentColorId: Int)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    @ApplicationScope private val appScope: CoroutineScope,
    private val chatsRepository: ChatsRepository,
    private val users: UsersRepository,
    groups: GroupsRepository,
    private val importer: MediaImporter,
    private val files: FilesRepository,
    presence: PresenceRepository,
    private val reactions: ReactionsRepository,
    private val audioPlayer: AudioPlayer,
    private val recorder: VoiceRecorder,
    private val photoEditor: PhotoEditRenderer,
    private val visibleChats: VisibleChats,
    private val prefetch: HistoryPrefetch,
    private val fontStore: FontStore,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ChatRoute>()
    private val chatId: Long = route.chatId
    /** Сообщение, на котором открыть чат; 0 — обычное открытие. */
    private val startMessageId: Long = route.messageId
    /** Весь чат, тред комментариев или топик форума. */
    val scope: ChatScope = ChatScope.of(route.threadId, route.topicId)
    private val history = ChatHistory(chatId, td, scope, prefetch)

    private val _scopeInfo = MutableStateFlow<ScopeInfo?>(null)
    /** Шапка и корень треда / топика; `null` — открыт весь чат или ещё грузится. */
    val scopeInfo: StateFlow<ScopeInfo?> = _scopeInfo.asStateFlow()

    val chat: StateFlow<Chat?> = chatsRepository.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chatsRepository.chats.value[chatId])

    val state: StateFlow<ChatHistory.State> = history.state

    /**
     * Можно ли писать: в канале без прав поле ввода не показываем. Комментировать пост можно и без
     * вступления в группу обсуждения, если она не требует вступать (`join_to_send_messages`).
     */
    val canWrite: StateFlow<Boolean> = combine(chat, groups.statuses, _scopeInfo) { chat, statuses, info ->
        val status = chat?.group?.let(statuses::get)
        // В закрытый топик пишут только админы
        val closed = info?.isClosed == true && status !is MemberStatus.Creator && status !is MemberStatus.Administrator
        chat != null && !closed && (chat.canWrite(status) || info?.openToNonMembers == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _draft = MutableStateFlow(RichText.Empty)
    /** Набранное в поле ввода, с оформлением. */
    val draft: StateFlow<RichText> = _draft.asStateFlow()

    private val _focusRequest = MutableStateFlow(0)
    /** Растёт, когда поле ввода должно получить фокус: «Ответить», «Изменить». */
    val focusRequest: StateFlow<Int> = _focusRequest.asStateFlow()

    private val _replyTo = MutableStateFlow<Message?>(null)
    /** Сообщение, на которое отвечаем; плашка над полем ввода. */
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    private val _editing = MutableStateFlow<Message?>(null)
    /** Своё сообщение, которое сейчас редактируем в поле ввода. */
    val editing: StateFlow<Message?> = _editing.asStateFlow()

    /** Набранное до входа в редактирование: вернётся в поле после него. */
    private var stashedDraft = RichText.Empty
    private var stashedReply: Message? = null

    private val _deleting = MutableStateFlow<DeleteRequest?>(null)
    /** Открытое подтверждение удаления. */
    val deleting: StateFlow<DeleteRequest?> = _deleting.asStateFlow()

    /** Режим выбора: выбранные id и их права (`null` — ещё загружаются). Пусто — режим выключен. */
    private val _selection = MutableStateFlow<Map<Long, MessageActions?>>(emptyMap())

    val selection: StateFlow<Set<Long>> = _selection.map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Общие действия над выбранными; `null` — режим выбора выключен. */
    val selectionActions: StateFlow<SelectionActions?> = combine(_selection, history.state) { selected, state ->
        if (selected.isEmpty()) null else selectionActions(selected, state.messages)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _openChat = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Перейти в другой чат: тап по упоминанию. */
    val openChat: SharedFlow<Long> = _openChat.asSharedFlow()

    private val mentionQuery = MutableStateFlow<String?>(null)

    /** Подсказки участников при вводе «@» в группе. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val mentionSuggestions: StateFlow<List<MentionCandidate>> = combine(mentionQuery, chat) { query, chat ->
        query?.takeIf { chat?.kind == ChatKind.Group }
    }
        .distinctUntilChanged()
        .debounce(MENTION_DEBOUNCE_MS)
        .mapLatest { query -> if (query == null) emptyList() else searchMembers(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Адрес, превью которого пользователь убрал: для него превью больше не показываем и не отправляем. */
    private val dismissedPreviewUrl = MutableStateFlow<String?>(null)

    /** Превью первой ссылки в набранном (только для нового сообщения, не для правки). */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val linkPreview: StateFlow<LinkPreview?> = combine(_draft, _editing) { draft, editing ->
        draft.takeIf { editing == null && it.text.contains('.') }
    }
        .distinctUntilChanged()
        .debounce(LINK_PREVIEW_DEBOUNCE_MS)
        .mapLatest { draft -> draft?.let { loadLinkPreview(it) } }
        .combine(dismissedPreviewUrl) { preview, dismissed -> preview?.takeUnless { it.url == dismissed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _pickedPhotos = MutableStateFlow<List<PickedPhoto>>(emptyList())
    /** Выбранные в Photo Picker фото: перед отправкой — превью, подпись и редактор. */
    val pickedPhotos: StateFlow<List<PickedPhoto>> = _pickedPhotos.asStateFlow()

    private val _editingPhoto = MutableStateFlow<Int?>(null)
    /** Какое из выбранных фото открыто в редакторе. */
    val editingPhoto: StateFlow<Int?> = _editingPhoto.asStateFlow()

    private val memberCount = MutableStateFlow(0)

    /** Подпись под названием: действия, статус собеседника или участники группы. */
    val header: StateFlow<HeaderInfo> = combine(
        chat, users.users, presence.activities, presence.online, memberCount,
    ) { chat, userMap, activities, online, members ->
        HeaderInfo(
            activities = activities[chatId].orEmpty(),
            userStatus = chat?.userId?.let { userMap[it]?.status },
            memberCount = members,
            onlineCount = online[chatId] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HeaderInfo())

    /** Когда последний раз говорили собеседникам «печатает»: не чаще раза в [TYPING_INTERVAL_MS]. */
    private var lastTypingSent = 0L

    /** Исходные сообщения ответов, которых нет в загруженном отрезке. */
    val replied: StateFlow<Map<Long, Message?>> = history.replied

    private val _scrollTarget = MutableStateFlow<Long?>(null)
    /** Сообщение, к которому надо прокрутить ленту (тап по ответу). */
    val scrollTarget: StateFlow<Long?> = _scrollTarget.asStateFlow()

    /** Ошибка отправки (сам запрос не ушёл). Ошибки доставки видны статусом сообщения. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /** Авторы сообщений по отправителю: пользователи и чаты (анонимные админы, каналы). */
    val senders: StateFlow<Map<Sender, SenderInfo>> = combine(users.users, chatsRepository.chats) { userMap, chatMap ->
        buildMap {
            userMap.values.forEach { put(Sender.User(it.id), SenderInfo(it.fullName, it.photo, it.accentColorId)) }
            chatMap.values.forEach { put(Sender.Chat(it.id), SenderInfo(it.title, it.photo, it.accentColorId)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    // Объявлены до init: reportViewedMessages() стартует там на Main.immediate и читает их сразу
    private val resumed = MutableStateFlow(false)
    private val visibleMessageIds = MutableStateFlow<List<Long>>(emptyList())

    init {
        // UNDISPATCHED: подписка на апдейты раньше, чем уйдёт первый getChatHistory
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { history.followUpdates() }
        viewModelScope.launch { reportViewedMessages() }
        viewModelScope.launch { history.followReplies() }
        viewModelScope.launch { followMemberCount() }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { followPinned() }
        viewModelScope.launch { followEmojiClicks() }
        viewModelScope.launch { loadPinned() }
        viewModelScope.launch {
            val opened = chat.mapNotNull { it }.first()
            // Черновик восстанавливаем, только если пользователь ещё ничего не набрал; у тредов и топиков черновиков нет
            if (scope == ChatScope.Whole) {
                opened.draft?.let { draft ->
                    _draft.compareAndSet(RichText.Empty, draft.text)
                    draft.replyToMessageId?.let { restoreReply(it) }
                }
            }
            val lastRead = if (scope == ChatScope.Whole) {
                opened.lastReadInboxMessageId.takeIf { opened.unreadCount > 0 }
            } else {
                loadScopeInfo(opened)
            }
            if (startMessageId != 0L && history.openAround(startMessageId)) {
                _scrollTarget.value = startMessageId
            } else {
                history.open(lastRead)
            }
        }
    }

    /**
     * Экран на виду: `openChat` (TDLib шлёт апдейты чата и считает просмотры), на паузе — `closeChat`.
     * Запросы идут в scope приложения: `closeChat` должен уйти, даже когда ViewModel уже уничтожается.
     */
    fun onResume() {
        if (resumed.value) return
        resumed.value = true
        visibleChats.shown(chatId)
        appScope.launch { runCatching { td.send(TdApi.OpenChat(chatId)) }.onFailure { Log.e(TAG, "openChat failed", it) } }
        // Открыли чат — ручная пометка «непрочитанный» больше не нужна
        if (chatsRepository.chats.value[chatId]?.isMarkedAsUnread == true) {
            appScope.launch { runCatching { chatsRepository.setMarkedAsUnread(chatId, false) }.onFailure { Log.e(TAG, "unmark failed", it) } }
        }
    }

    fun onPause() {
        if (!resumed.value) return
        resumed.value = false
        visibleChats.hidden(chatId)
        saveDraft()
        appScope.launch { runCatching { td.send(TdApi.CloseChat(chatId)) }.onFailure { Log.e(TAG, "closeChat failed", it) } }
    }

    /** Тред или топик: заголовок, корень треда; возвращает последнее прочитанное, если есть непрочитанные. */
    private suspend fun loadScopeInfo(opened: Chat): Long? {
        val openToNonMembers = suspend {
            val group = opened.group as? GroupRef.Super
            group != null && runCatching { !td.send(TdApi.GetSupergroup(group.id)).joinToSendMessages }.getOrDefault(false)
        }
        return when (val current = scope) {
            ChatScope.Whole -> null
            is ChatScope.Thread -> runCatching { td.send(TdApi.GetMessageThread(chatId, current.threadId)) }
                .onFailure { Log.w(TAG, "getMessageThread failed", it) }
                .getOrNull()
                ?.let { info ->
                    _scopeInfo.value = ScopeInfo(
                        title = null,
                        root = info.messages.map { it.toModel() }.sortedBy { it.id },
                        replyCount = info.replyInfo?.replyCount ?: 0,
                        openToNonMembers = openToNonMembers(),
                    )
                    info.replyInfo?.lastReadInboxMessageId?.takeIf { info.unreadMessageCount > 0 }
                }
            is ChatScope.Topic -> runCatching { td.send(TdApi.GetForumTopic(chatId, current.topicId)) }
                .onFailure { Log.w(TAG, "getForumTopic failed", it) }
                .getOrNull()
                ?.let { topic ->
                    _scopeInfo.value = ScopeInfo(title = topic.info.name, isClosed = topic.info.isClosed)
                    topic.lastReadInboxMessageId.takeIf { topic.unreadCount > 0 }
                }
        }
    }

    private val _openThread = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 1)
    /** Открыть комментарии: (чат обсуждения, id треда). */
    val openThread: SharedFlow<Pair<Long, Long>> = _openThread.asSharedFlow()

    /** «Разблокировать» вместо поля ввода в личке с заблокированным. */
    fun unblock() {
        val userId = chat.value?.userId ?: return
        viewModelScope.launch {
            runCatching { td.send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), null)) }
                .onFailure { Log.w(TAG, "unblock failed", it) }
        }
    }

    /** Плашка «N комментариев» под постом канала. */
    fun openComments(message: Message) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.GetMessageThread(chatId, message.id)) }
                .onSuccess { _openThread.emit(it.chatId to it.messageThreadId) }
                .onFailure {
                    Log.w(TAG, "getMessageThread(${message.id}) failed", it)
                    _sendError.value = userErrors.message(it)
                }
        }
    }

    private fun restoreReply(messageId: Long) {
        viewModelScope.launch {
            val message = runCatching { td.send(TdApi.GetMessage(chatId, messageId)) }
                .onFailure { Log.w(TAG, "getMessage($messageId) for draft reply failed", it) }
                .getOrNull()?.toModel() ?: return@launch
            _replyTo.compareAndSet(null, message)
        }
    }

    /**
     * Уходим с экрана — сохраняем набранное и ответ как черновик. Без текста черновик стираем, даже если
     * выбран ответ: иначе в списке чатов пустой «Черновик: », а чат поднимается наверх (#219).
     */
    private fun saveDraft() {
        if (scope != ChatScope.Whole) return
        // Во время редактирования в поле правка, а черновик — отложенный текст
        val editing = _editing.value != null
        val text = (if (editing) stashedDraft else _draft.value).trimmed()
        val replyId = (if (editing) stashedReply else _replyTo.value)?.id
        val saved = chat.value?.draft
        if (text == (saved?.text ?: RichText.Empty) && replyId == saved?.replyToMessageId) return
        val draft = if (text.isBlank()) null else {
            val content = TdApi.DraftMessageContentText(text.toFormattedText(), null)
            val replyTo = replyId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
            TdApi.DraftMessage(replyTo, 0, content, 0, null)
        }
        appScope.launch {
            runCatching { td.send(TdApi.SetChatDraftMessage(chatId, null, draft)) }
                .onFailure { Log.e(TAG, "setChatDraftMessage failed", it) }
        }
    }

    /** Какие сообщения сейчас на экране — их отмечаем прочитанными. */
    fun onVisibleMessagesChange(ids: List<Long>) {
        visibleMessageIds.value = ids
    }

    override fun onCleared() {
        recorder.discard()
        onPause()
    }

    @OptIn(FlowPreview::class)
    private suspend fun reportViewedMessages() {
        combine(resumed, visibleMessageIds, history.state) { resumed, ids, state ->
            if (!resumed) return@combine emptyList()
            // Временные id ещё отправляющихся сообщений TDLib не нужны
            val sent = state.messages.filter { it.sendingState == null }.mapTo(HashSet()) { it.id }
            ids.filter { it in sent }
        }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .debounce(VIEW_DEBOUNCE_MS)
            .collect { ids ->
                runCatching {
                    td.send(TdApi.ViewMessages(chatId, ids.toLongArray(), scope.viewSource(), false))
                }.onFailure { Log.e(TAG, "viewMessages failed", it) }
            }
    }

    /** Новый текст поля: оформление вне изменённого куска сохраняется. */
    fun onDraftChange(value: String) {
        _draft.update { it.withEditedText(value) }
        _sendError.value = null
        if (value.isNotBlank() && _editing.value == null) sendTyping()
    }

    private fun sendTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < TYPING_INTERVAL_MS) return
        lastTypingSent = now
        viewModelScope.launch {
            runCatching { td.send(TdApi.SendChatAction(chatId, scope.toTd(), "", TdApi.ChatActionTyping())) }
                .onFailure { Log.w(TAG, "sendChatAction failed", it) }
        }
    }

    /** Число участников группы или подписчиков канала: первым запросом и дальше по апдейтам. */
    private suspend fun followMemberCount() {
        val group = chat.mapNotNull { it }.first().group ?: return
        runCatching {
            memberCount.value = when (group) {
                is GroupRef.Super -> td.send(TdApi.GetSupergroup(group.id)).memberCount
                is GroupRef.Basic -> td.send(TdApi.GetBasicGroup(group.id)).memberCount
            }
        }.onFailure { Log.w(TAG, "member count failed", it) }
        td.updates.collect { update ->
            when {
                update is TdApi.UpdateSupergroup && group is GroupRef.Super && update.supergroup.id == group.id ->
                    memberCount.value = update.supergroup.memberCount
                update is TdApi.UpdateBasicGroup && group is GroupRef.Basic && update.basicGroup.id == group.id ->
                    memberCount.value = update.basicGroup.memberCount
            }
        }
    }

    private suspend fun loadLinkPreview(draft: RichText): LinkPreview? =
        // Нет ссылки — TDLib отвечает ошибкой, это нормально
        runCatching { td.send(TdApi.GetLinkPreview(draft.toFormattedText(), null)) }.getOrNull()?.toModel()

    fun onPhotosPicked(uris: List<Uri>) {
        _pickedPhotos.value = uris.map { PickedPhoto(it) }
    }

    fun cancelPhotos() {
        photoEditor.discard(_pickedPhotos.value.mapNotNull { it.rendered?.path })
        _pickedPhotos.value = emptyList()
    }

    fun editPhoto(index: Int) {
        if (index in _pickedPhotos.value.indices) _editingPhoto.value = index
    }

    fun cancelPhotoEdit() {
        _editingPhoto.value = null
    }

    /** Исходник для редактора. */
    suspend fun loadPhotoForEdit(index: Int): Bitmap = photoEditor.load(_pickedPhotos.value[index].uri)

    /** «Готово» в редакторе: собрать отредактированную копию; пустые правки — снова исходник. */
    fun applyPhotoEdit(index: Int, edit: PhotoEdit) {
        _editingPhoto.value = null
        val photo = _pickedPhotos.value.getOrNull(index) ?: return
        viewModelScope.launch {
            val rendered = if (edit.isEmpty) {
                null
            } else {
                runCatching { photoEditor.renderToFile(photo.uri, edit) }
                    .onFailure { Log.e(TAG, "photo edit failed", it) }
                    .getOrNull() ?: return@launch
            }
            photo.rendered?.let { photoEditor.discard(listOf(it.path)) }
            _pickedPhotos.update { list ->
                list.map { if (it.uri == photo.uri) it.copy(edit = edit, rendered = rendered) else it }
            }
        }
    }

    /**
     * Опции отправки: [silent] — разово «без звука» из меню отправки,
     * иначе как настроено колокольчиком чата.
     */
    private fun sendOptions(silent: Boolean = false, schedule: Schedule? = null) =
        SendOptions(silent = silent || chat.value?.defaultSilent == true, schedule = schedule)

    /** «Когда будет в сети»: только в личке с пользователем, который показывает свой статус. */
    val canScheduleWhenOnline: StateFlow<Boolean> = combine(chat, users.users) { chat, userMap ->
        val status = chat?.takeUnless { it.isSavedMessages }?.userId?.let { userMap[it]?.status }
        status is UserStatus.Online || status is UserStatus.Offline
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Колокольчик в поле ввода канала: переключает «без звука» по умолчанию для этого чата. */
    fun toggleDefaultSilent() {
        val current = chat.value?.defaultSilent ?: return
        viewModelScope.launch {
            runCatching { td.send(TdApi.ToggleChatDefaultDisableNotification(chatId, !current)) }
                .onFailure { Log.w(TAG, "toggleChatDefaultDisableNotification failed", it) }
        }
    }

    /** Отправка выбранных фото с подписью; ответ, если он начат, уходит вместе с ними. */
    fun sendPhotos(caption: String, silent: Boolean, spoiler: Boolean) {
        val uris = _pickedPhotos.value
        if (uris.isEmpty()) return
        _pickedPhotos.value = emptyList()
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching {
                val images = uris.map { photo ->
                    photo.rendered?.let { ImportedImage(it.path, it.width, it.height) } ?: importer.importImage(photo.uri)
                }
                history.sendPhotos(images, RichText(caption.trim()), reply?.id, sendOptions(silent), spoiler)
            }.onFailure {
                Log.e(TAG, "send photos failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    private val documents = DocumentDownloads(files, viewModelScope)
    /** Состояние документов, которые качаются или скачаны в этом экране; по id файла. */
    val downloads: StateFlow<Map<Int, FileState>> = documents.states

    /** Аудиоплеер, если играет что-то из этого чата; иначе пустое состояние. */
    val audio: StateFlow<AudioState> = audioPlayer.state
        .map { if (it.track?.chatId == chatId) it else AudioState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioState())

    /**
     * Тап по ▶ голосового или трека. Голосовое — играть его и следующие голосовые чата;
     * трек — очередь из всех загруженных треков чата (от старых к новым), начиная с него.
     */
    fun onAudioToggle(message: Message) {
        when (message.content) {
            is MessageBody.Voice -> {
                val track = message.voiceTrack() ?: return
                val following = history.state.value.messages
                    .filter { it.id > message.id }
                    .sortedBy { it.id }
                    .mapNotNull { it.voiceTrack() }
                audioPlayer.toggle(track, following)
            }
            is MessageBody.Audio -> {
                val tracks = history.state.value.messages.sortedBy { it.id }.mapNotNull { it.musicTrack() }
                audioPlayer.toggleIn(tracks, tracks.indexOfFirst { it.messageId == message.id })
            }
            else -> Unit
        }
    }

    private fun Message.musicTrack(): AudioTrack? {
        val body = content as? MessageBody.Audio ?: return null
        if (sendingState != null) return null
        val audio = body.audio
        return AudioTrack(chatId, id, audio.file, isVoice = false, audio.displayTitle, audio.performer, audio.duration, audio.cover)
    }

    fun onAudioSeek(fraction: Float) = audioPlayer.seekTo(fraction)

    private fun Message.voiceTrack(): AudioTrack? {
        val body = content as? MessageBody.Voice ?: return null
        if (sendingState != null) return null
        return AudioTrack(chatId, id, body.voice.file, isVoice = true, duration = body.voice.duration)
    }

    /**
     * Запись голосового из поля ввода — без громкости: она меняется каждые 50 мс, и экран чата,
     * собирающий это состояние, перекомпоновывался бы целиком (#296). Громкость — [recordingLevel].
     */
    val recorderState: StateFlow<RecorderState> = recorder.state
        .map { if (it is RecorderState.Recording) it.copy(level = 0f) else it }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecorderState.Idle)

    /** Громкость микрофона 0..1; кнопка записи читает её каждый кадр. */
    fun recordingLevel(): Float = (recorder.state.value as? RecorderState.Recording)?.level ?: 0f

    /** Палец лёг на микрофон. Играющее аудио на время записи — на паузу. */
    fun startRecording() {
        audioPlayer.pause()
        if (!recorder.start(viewModelScope)) Log.w(TAG, "microphone unavailable")
    }

    fun lockRecording() = recorder.lock()

    /** Палец отпущен без фиксации — сразу отправить (слишком короткое не отправляется). */
    fun releaseRecording() {
        recorder.finish()?.let(::sendVoice)
    }

    /** «Стоп» в зафиксированном режиме — предпрослушивание. */
    fun stopRecordingForPreview() = recorder.stopForPreview()

    fun toggleRecordingPreview() = recorder.togglePreview()

    fun recordingPreviewProgress(): Float = recorder.previewProgress()

    /** Отправить: из идущей зафиксированной записи или из предпрослушивания. */
    fun sendRecording() {
        val recording = when (recorder.state.value) {
            is RecorderState.Recording -> recorder.finish()
            is RecorderState.Recorded -> recorder.takeRecorded()
            RecorderState.Idle -> null
        }
        recording?.let(::sendVoice)
    }

    fun discardRecording() = recorder.discard()

    /** Видеокружки разрешены: по правам чата, админам — всегда. Сервер всё равно проверит. */
    val canSendVideoNotes: StateFlow<Boolean> = combine(chat, groups.statuses) { chat, statuses ->
        val status = chat?.group?.let(statuses::get)
        chat != null && (chat.canSendVideoNotes || status is MemberStatus.Creator || status is MemberStatus.Administrator)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /**
     * Стикеры разрешены (`canSendOtherMessages` чата и участника); иначе панель стикеров
     * показывает заглушку. Сервер всё равно проверит.
     */
    val canSendStickers: StateFlow<Boolean> = combine(chat, groups.statuses) { chat, statuses ->
        chat != null && chat.canSendStickers(chat.group?.let(statuses::get))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * Отправить стикер из панели: уходит в открытый тред / топик, начатый ответ прикрепляется
     * и сбрасывается (при ошибке возвращается). Текст в поле ввода не трогаем.
     */
    fun sendSticker(sticker: StickerInfo, silent: Boolean = false) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendSticker(sticker, reply?.id, sendOptions(silent)) }.onFailure {
                Log.e(TAG, "send sticker failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    /** Началась запись кружка: играющее аудио — на паузу. */
    fun onVideoNoteRecordingStarted() {
        audioPlayer.pause()
    }

    /** Пока идёт запись кружка — «записывает видеосообщение» раз в несколько секунд. */
    fun sendRecordingVideoNoteAction() {
        viewModelScope.launch {
            runCatching { td.send(TdApi.SendChatAction(chatId, scope.toTd(), "", TdApi.ChatActionRecordingVideoNote())) }
        }
    }

    /** Записанный кружок: прочитать длительность и сторону, отправить. Короче секунды — не отправляется. */
    fun sendVideoNote(path: String) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            val note = describeVideoNote(path)
            if (note == null) {
                _replyTo.value = reply
                return@launch
            }
            runCatching { history.sendVideoNote(note, reply?.id, sendOptions()) }.onFailure {
                Log.e(TAG, "send video note failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    private fun sendVoice(recording: VoiceRecording) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendVoice(recording.path, recording.durationSec, recording.waveform, reply?.id, sendOptions()) }.onFailure {
                Log.e(TAG, "send voice failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    /** Сколько вариантов можно в новом опросе (`poll_answer_count_max`). */
    val pollMaxOptions: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        val max = runCatching { td.send(TdApi.GetOption("poll_answer_count_max")) }.getOrNull()
        emit((max as? TdApi.OptionValueInteger)?.value?.toInt() ?: DEFAULT_POLL_OPTIONS)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DEFAULT_POLL_OPTIONS)

    fun sendPoll(draft: PollDraft) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendPoll(draft, reply?.id, sendOptions()) }.onFailure {
                Log.e(TAG, "send poll failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    fun vote(message: Message, optionIds: List<Int>) = setPollAnswer(message, optionIds)

    fun retractVote(message: Message) {
        _menu.value = null
        setPollAnswer(message, emptyList())
    }

    private fun setPollAnswer(message: Message, optionIds: List<Int>) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.SetPollAnswer(chatId, message.id, optionIds.toIntArray())) }.onFailure {
                Log.w(TAG, "setPollAnswer failed", it)
                _sendError.value = userErrors.message(it)
            }
        }
    }

    fun stopPoll(message: Message) {
        _menu.value = null
        viewModelScope.launch {
            runCatching { td.send(TdApi.StopPoll(chatId, message.id, null)) }.onFailure {
                Log.w(TAG, "stopPoll failed", it)
                _sendError.value = userErrors.message(it)
            }
        }
    }

    private val _pollVoters = MutableStateFlow<PollVoters?>(null)
    private var votersMessageId = 0L

    /** Открытый список проголосовавших за вариант. */
    internal val pollVoters: StateFlow<PollVoters?> = _pollVoters.asStateFlow()

    fun showPollVoters(message: Message, option: io.github.iufb.reach.chats.PollOptionInfo) {
        votersMessageId = message.id
        _pollVoters.value = PollVoters(option, emptyList(), option.voterCount, loading = true)
        loadMoreVoters()
    }

    fun loadMoreVoters() {
        val current = _pollVoters.value ?: return
        val messageId = votersMessageId
        _pollVoters.value = current.copy(loading = true)
        viewModelScope.launch {
            val page = runCatching {
                td.send(TdApi.GetPollVoters(chatId, messageId, current.option.id, current.voters.size, VOTERS_PAGE))
            }.onFailure { Log.w(TAG, "getPollVoters failed", it) }.getOrNull()
            _pollVoters.update { state ->
                if (state == null || state.option.id != current.option.id) return@update state
                val rows = page?.voters.orEmpty().map { voter ->
                    val sender = when (val id = voter.voterId) {
                        is TdApi.MessageSenderUser -> Sender.User(id.userId)
                        is TdApi.MessageSenderChat -> Sender.Chat(id.chatId)
                        else -> Sender.Chat(0)
                    }
                    val info = senders.value[sender]
                    PollVoterRow(sender, info?.name.orEmpty(), info?.photo, info?.accentColorId ?: 0)
                }
                state.copy(
                    voters = (state.voters + rows).distinctBy { it.sender },
                    totalCount = page?.totalCount ?: state.voters.size,
                    loading = false,
                )
            }
        }
    }

    fun closePollVoters() {
        _pollVoters.value = null
    }

    private val _openedStickerSet = MutableStateFlow<Long?>(null)

    /** Пак стикера, по которому тапнули в ленте; `null` — лист пака закрыт. */
    val openedStickerSet: StateFlow<Long?> = _openedStickerSet.asStateFlow()

    fun onStickerClick(sticker: StickerInfo) {
        if (sticker.setId != 0L) _openedStickerSet.value = sticker.setId
    }

    fun dismissStickerSet() {
        _openedStickerSet.value = null
    }

    /** Открыть скачанный файл во внешнем приложении. */
    val openFile: SharedFlow<OpenFile> = documents.open

    /** Тап по документу: скачан — открыть, качается — остановить, иначе — скачать. */
    fun onDocumentClick(document: DocumentInfo) = documents.onClick(document)

    private val _fontNotice = MutableStateFlow<FontNotice?>(null)
    /** Итог «Установить как шрифт» для тоста; `null` — тоста нет. */
    val fontNotice: StateFlow<FontNotice?> = _fontNotice.asStateFlow()

    /**
     * «Установить как шрифт» (#275): скачать документ, если ещё не скачан (прогресс — в ячейке),
     * и импортировать в [FontStore]. Шрифт не выбирается: выбор — в Настройках → Шрифт.
     */
    fun installFont(message: Message) {
        _menu.value = null
        val document = (message.content as? MessageBody.Document)?.document ?: return
        viewModelScope.launch {
            val path = documents.awaitPath(document)
            _fontNotice.value = if (path == null) {
                FontNotice.Failed(null)
            } else {
                fontStore.import(java.io.File(path)).fold(
                    onSuccess = { FontNotice.Added(it) },
                    onFailure = { FontNotice.Failed((it as? FontImportException)?.problem?.message) },
                )
            }
        }
    }

    fun dismissFontNotice(notice: FontNotice) {
        _fontNotice.compareAndSet(notice, null)
    }

    /** Отправка файла, выбранного через системный выбор. */
    fun sendFile(uri: Uri) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendDocument(importer.importFile(uri), reply?.id, sendOptions()) }.onFailure {
                Log.e(TAG, "send file failed", it)
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    /** Прогресс загрузки своего отправляющегося файла, 0..1. */
    fun uploadProgress(file: TdFile): Flow<Float> = files.uploadProgress(file.id)

    /** Отмена отправки: удаление отправляющегося сообщения останавливает загрузку. */
    fun cancelSending(message: Message) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.DeleteMessages(chatId, longArrayOf(message.id), true)) }
                .onFailure { Log.e(TAG, "cancel sending failed", it) }
        }
    }

    /** ✕ на превью над полем: отправим без него. */
    fun dismissLinkPreview() {
        dismissedPreviewUrl.value = linkPreview.value?.url
    }

    /** Слово с «@» под курсором (без «@»); `null` — курсор не в упоминании. */
    fun onMentionQuery(query: String?) {
        mentionQuery.value = query
    }

    /** Выбор из подсказок: «@username » или имя с упоминанием по id, если username нет. */
    fun pickMention(candidate: MentionCandidate, start: Int, end: Int) {
        _draft.update { draft ->
            if (candidate.username.isNotEmpty()) {
                draft.replaceRange(start, end, "@${candidate.username} ")
            } else {
                draft.replaceRange(start, end, "${candidate.name} ", SpanKind.MentionUser(candidate.userId))
            }
        }
        mentionQuery.value = null
    }

    private suspend fun searchMembers(query: String): List<MentionCandidate> {
        val result = runCatching { td.send(TdApi.SearchChatMembers(chatId, query, MENTION_LIMIT, null)) }
            .onFailure { Log.w(TAG, "searchChatMembers failed", it) }
            .getOrNull() ?: return emptyList()
        val known = users.users.value
        return result.members.mapNotNull { member ->
            val id = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
            val user = known[id] ?: return@mapNotNull null
            MentionCandidate(id, user.fullName, user.username, user.photo, user.accentColorId)
        }
    }

    /** Тап по @username: ищем публичный чат и открываем его. */
    fun openUsername(username: String) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.SearchPublicChat(username)) }
                .onSuccess { _openChat.emit(it.id) }
                .onFailure { Log.w(TAG, "searchPublicChat($username) failed", it) }
        }
    }

    /** Кнопка оформления над полем: включает или снимает [kind] на выделенном. */
    fun onFormat(kind: SpanKind, start: Int, end: Int) {
        _draft.update { it.toggle(kind, start, end) }
    }

    fun send() = sendDraft(sendOptions())

    /** «Отправить без звука» из меню кнопки отправки. */
    fun sendSilently() = sendDraft(sendOptions(silent = true))

    /** «Отправить позже»: сообщение уходит в отложенные, в истории его нет. */
    fun sendScheduled(schedule: Schedule) = sendDraft(sendOptions(schedule = schedule))

    private fun sendDraft(options: SendOptions) {
        _editing.value?.let { return saveEdit(it) }
        val text = _draft.value.trimmed()
        if (text.isBlank()) return
        val reply = _replyTo.value
        val noPreview = dismissedPreviewUrl.value != null
        _draft.value = RichText.Empty
        _replyTo.value = null
        dismissedPreviewUrl.value = null
        viewModelScope.launch {
            runCatching { history.sendText(text, reply?.id, withLinkPreview = !noPreview, options = options) }.onFailure {
                Log.e(TAG, "sendMessage failed", it)
                _draft.value = text
                _replyTo.value = reply
                _sendError.value = userErrors.message(it)
            }
        }
    }

    /** «Ответить» в меню или свайп по сообщению. */
    fun startReply(message: Message) {
        _menu.value = null
        _replyTo.value = message
        _focusRequest.update { it + 1 }
    }

    fun cancelReply() {
        _replyTo.value = null
    }

    /** «Удалить» в меню: спрашиваем подтверждение. */
    fun requestDelete(menu: MessageMenu) {
        _menu.value = null
        _deleting.value = DeleteRequest(listOf(menu.message.id), menu.actions.canDeleteForAll, menu.actions.canDeleteForSelf)
    }

    fun cancelDelete() {
        _deleting.value = null
    }

    /** Удаление подтверждено; из ленты сообщения уберёт `UpdateDeleteMessages`. */
    fun confirmDelete(revoke: Boolean) {
        val request = _deleting.value ?: return
        _deleting.value = null
        clearSelection()
        if (_editing.value?.id in request.messageIds) cancelEdit()
        if (_replyTo.value?.id in request.messageIds) _replyTo.value = null
        viewModelScope.launch {
            runCatching { td.send(TdApi.DeleteMessages(chatId, request.messageIds.toLongArray(), revoke)) }
                .onFailure { Log.e(TAG, "deleteMessages failed", it) }
        }
    }

    /** «Изменить» в меню: текст сообщения в поле ввода, набранное откладывается. */
    fun startEdit(message: Message) {
        val text = message.content.editableText() ?: return
        _menu.value = null
        if (_editing.value == null) {
            stashedDraft = _draft.value
            stashedReply = _replyTo.value
        }
        _editing.value = message
        _replyTo.value = null
        _draft.value = text
        _sendError.value = null
        _focusRequest.update { it + 1 }
    }

    fun cancelEdit() {
        if (_editing.value == null) return
        _editing.value = null
        _draft.value = stashedDraft
        _replyTo.value = stashedReply
        stashedDraft = RichText.Empty
        stashedReply = null
    }

    private fun saveEdit(message: Message) {
        val original = message.content.editableText() ?: return cancelEdit()
        val edited = _draft.value.trimmed()
        // Пустым можно сделать только подпись, текст сообщения — нет
        if (edited.isBlank() && message.content is MessageBody.Text) return
        val typed = _draft.value
        cancelEdit()
        if (edited == original) return
        viewModelScope.launch {
            runCatching { history.editText(message, edited) }.onFailure {
                Log.e(TAG, "edit failed", it)
                startEdit(message)
                _draft.value = typed
                _sendError.value = userErrors.message(it)
            }
        }
    }

    // Закреплённые, загруженные списком чатов до перехода, — сразу: плашка есть с первого кадра и не сдвигает ленту
    private val pinnedMessages = MutableStateFlow(
        prefetch.takePinned(chatId)?.takeIf { scope == ChatScope.Whole }?.messages?.map { it.toModel() }.orEmpty(),
    )
    private val pinnedIndex = MutableStateFlow(0)

    /** Плашка закреплённого под шапкой; `null` — закреплённых нет. */
    val pinned: StateFlow<PinnedBanner?> = combine(pinnedMessages, pinnedIndex, ::pinnedBanner)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), pinnedBanner(pinnedMessages.value, 0))

    private fun pinnedBanner(list: List<Message>, index: Int): PinnedBanner? =
        if (list.isEmpty()) null else PinnedBanner(list[index.coerceIn(list.indices)], index.coerceIn(list.indices), list.size)

    /** Закреплённые сообщения чата, новые первыми. */
    private suspend fun loadPinned() {
        runCatching {
            td.send(TdApi.SearchChatMessages(chatId, scope.toTd(), "", null, 0, 0, PINNED_LIMIT, TdApi.SearchMessagesFilterPinned()))
        }.onSuccess { found ->
            pinnedMessages.value = found.messages.map { it.toModel() }
        }.onFailure { Log.w(TAG, "load pinned failed", it) }
    }

    private suspend fun followPinned() {
        td.updates.collect { update ->
            when (update) {
                is TdApi.UpdateMessageIsPinned -> if (update.chatId == chatId) loadPinned()
                is TdApi.UpdateMessageContent -> if (update.chatId == chatId && pinnedMessages.value.any { it.id == update.messageId }) loadPinned()
                is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                    pinnedMessages.update { list -> list.filter { it.id !in update.messageIds } }
                }
            }
        }
    }

    /** Тап по плашке: переходим к показанному закреплённому, плашка листает к следующему (более старому). */
    fun onPinnedClick() {
        val banner = pinned.value ?: return
        jumpTo(banner.message.id)
        if (banner.count > 1) pinnedIndex.value = (banner.index + 1) % banner.count
    }

    fun setPinned(message: Message, pinned: Boolean) {
        _menu.value = null
        viewModelScope.launch {
            runCatching {
                if (pinned) {
                    td.send(TdApi.PinChatMessage(chatId, message.id, false, false))
                } else {
                    td.send(TdApi.UnpinChatMessage(chatId, message.id))
                }
            }.onFailure {
                Log.w(TAG, "pin failed", it)
                _sendError.value = userErrors.message(it)
            }
        }
    }

    /** Тап по ответу: переходим к исходному сообщению, подгружая историю вокруг него. */
    fun jumpTo(messageId: Long) {
        viewModelScope.launch {
            if (history.openAround(messageId)) _scrollTarget.value = messageId
        }
    }

    fun onScrolledToTarget() {
        _scrollTarget.value = null
    }

    private val _menu = MutableStateFlow<MessageMenu?>(null)
    /** Открытое меню действий над сообщением. */
    val menu: StateFlow<MessageMenu?> = _menu.asStateFlow()

    /** Долгое нажатие: спрашиваем у TDLib права на сообщение и открываем меню, если есть что показать. */
    fun openMenu(message: Message) {
        if (message.content is MessageBody.Service) return
        viewModelScope.launch {
            val available = async { loadReactions(message) }
            val actions = loadActions(message)
            val reactionsRow = available.await()
            if (!actions.isEmpty || !reactionsRow.isEmpty) _menu.value = MessageMenu(message, actions, reactionsRow)
        }
    }

    /** Тап по чипу или выбор в меню: своя реакция снимается, чужая — ставится. */
    private val _reactionEffect = MutableStateFlow<Pair<Long, ReactionKey>?>(null)

    /** Своя реакция только что поставлена: (сообщение, реакция) — эффект поверх сообщения. */
    val reactionEffect: StateFlow<Pair<Long, ReactionKey>?> = _reactionEffect.asStateFlow()

    fun onReactionEffectDone() {
        _reactionEffect.value = null
    }

    fun toggleReaction(message: Message, key: ReactionKey) {
        _menu.value = null
        if (key == ReactionKey.Paid) return
        val chosen = message.reactions.any { it.key == key && it.isChosen }
        if (!chosen) _reactionEffect.value = message.id to key
        viewModelScope.launch {
            runCatching {
                if (chosen) reactions.remove(chatId, message.id, key) else reactions.add(chatId, message.id, key)
            }.onFailure { Log.w(TAG, "toggle reaction failed", it) }
        }
    }

    /** Двойной тап: реакция по умолчанию, если её ещё нет. */
    fun reactDefault(message: Message) {
        if (message.content is MessageBody.Service || message.sendingState != null) return
        val key = reactions.default.value ?: return
        if (message.reactions.any { it.key == key && it.isChosen }) return
        _reactionEffect.value = message.id to key
        viewModelScope.launch {
            runCatching { reactions.add(chatId, message.id, key) }.onFailure { Log.w(TAG, "default reaction failed", it) }
        }
    }

    private suspend fun loadReactions(message: Message): AvailableReactions {
        if (message.sendingState != null) return AvailableReactions.None
        return runCatching { reactions.available(chatId, message.id) }
            .onFailure { Log.w(TAG, "getMessageAvailableReactions failed", it) }
            .getOrDefault(AvailableReactions.None)
    }

    private suspend fun loadActions(message: Message): MessageActions {
        val properties = runCatching { td.send(TdApi.GetMessageProperties(chatId, message.id)) }
            .onFailure { Log.w(TAG, "getMessageProperties failed", it) }
            .getOrNull()
        return messageActions(message, properties)
    }

    /** «Выбрать» в меню: включаем режим выбора с этим сообщением. */
    fun startSelection(menu: MessageMenu) {
        _menu.value = null
        _selection.value = mapOf(menu.message.id to menu.actions)
    }

    /** Тап по сообщению в режиме выбора. Права подгружаются, пока они не пришли — действия недоступны. */
    fun toggleSelection(message: Message) {
        if (message.content is MessageBody.Service || message.sendingState != null) return
        if (message.id in _selection.value) {
            _selection.update { it - message.id }
            return
        }
        _selection.update { it + (message.id to null) }
        viewModelScope.launch {
            val actions = loadActions(message)
            _selection.update { if (message.id in it) it + (message.id to actions) else it }
        }
    }

    fun clearSelection() {
        _selection.value = emptyMap()
    }

    /** Удаление выбранных: то же подтверждение, что и для одного. */
    fun requestDeleteSelected(request: DeleteRequest) {
        _deleting.value = request
    }

    fun closeMenu() {
        _menu.value = null
    }

    fun loadOlder() {
        viewModelScope.launch { history.loadOlder() }
    }

    fun loadNewer() {
        viewModelScope.launch { history.loadNewer() }
    }

    private val _emojiEffects = MutableStateFlow<List<EmojiEffect>>(emptyList())
    /** Играющие эффекты больших эмодзи. */
    val emojiEffects: StateFlow<List<EmojiEffect>> = _emojiEffects.asStateFlow()
    private var lastEffectKey = 0L

    /** Содержимое сообщения открыто (кружок просмотрен) — TDLib пришлёт `updateMessageContentOpened`. */
    fun openContent(message: Message) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.OpenMessageContent(chatId, message.id)) }
                .onFailure { Log.w(TAG, "openMessageContent failed", it) }
        }
    }

    /** Тап по большому эмодзи: TDLib отдаёт эффект и сам сообщает о тапе собеседнику; 404 — эффекта нет. */
    fun onAnimatedEmojiClick(message: Message) {
        if (message.sendingState != null) return
        viewModelScope.launch {
            runCatching { td.send(TdApi.ClickAnimatedEmojiMessage(chatId, message.id)) }
                .onSuccess { addEmojiEffect(message.id, it, fromPeer = false) }
        }
    }

    /** Эффект появился на экране; за тапом собеседника отвечаем «смотрит анимацию», как просит TDLib. */
    fun onEmojiEffectShown(effect: EmojiEffect) {
        if (!effect.fromPeer) return
        viewModelScope.launch {
            runCatching { td.send(TdApi.SendChatAction(chatId, scope.toTd(), "", TdApi.ChatActionWatchingAnimations(effect.emoji))) }
                .onFailure { Log.w(TAG, "watching animations failed", it) }
        }
    }

    fun onEmojiEffectDone(effect: EmojiEffect) {
        _emojiEffects.update { it - effect }
    }

    private fun addEmojiEffect(messageId: Long, sticker: TdApi.Sticker, fromPeer: Boolean) {
        if (sticker.format !is TdApi.StickerFormatTgs) return
        val effect = EmojiEffect(++lastEffectKey, messageId, sticker.sticker.toTdFile(), sticker.emoji, fromPeer)
        _emojiEffects.update { (it + effect).takeLast(MAX_EMOJI_EFFECTS) }
        // Сообщение не на экране — эффект никто не доиграет; убираем сами
        viewModelScope.launch {
            delay(EMOJI_EFFECT_TIMEOUT_MS)
            onEmojiEffectDone(effect)
        }
    }

    private suspend fun followEmojiClicks() {
        td.updates.collect { update ->
            if (update is TdApi.UpdateAnimatedEmojiMessageClicked && update.chatId == chatId) {
                addEmojiEffect(update.messageId, update.sticker, fromPeer = true)
            }
        }
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val MAX_EMOJI_EFFECTS = 4
        const val EMOJI_EFFECT_TIMEOUT_MS = 6_000L
        const val PINNED_LIMIT = HistoryPrefetch.PINNED_LIMIT
        const val STOP_TIMEOUT_MS = 5_000L
        const val VIEW_DEBOUNCE_MS = 300L
        const val MENTION_DEBOUNCE_MS = 200L
        const val MENTION_LIMIT = 20
        const val LINK_PREVIEW_DEBOUNCE_MS = 600L
        const val TYPING_INTERVAL_MS = 5_000L
        const val DEFAULT_POLL_OPTIONS = 12
        const val VOTERS_PAGE = 50
    }
}
