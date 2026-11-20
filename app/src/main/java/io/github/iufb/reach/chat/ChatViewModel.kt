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
import io.github.iufb.reach.files.DocumentDownloads
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
import kotlinx.coroutines.flow.Flow
import io.github.iufb.reach.chats.LinkPreview
import io.github.iufb.reach.chats.toModel
import io.github.iufb.reach.chats.replaceRange
import io.github.iufb.reach.chats.toggle
import io.github.iufb.reach.chats.trimmed
import io.github.iufb.reach.chats.withEditedText
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.canWrite
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.ChatRoute
import io.github.iufb.reach.users.UsersRepository
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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

/** Имя и аватарка автора сообщения в группе. */
data class SenderInfo(val name: String, val photo: io.github.iufb.reach.files.TdFile?, val accentColorId: Int)

@HiltViewModel
class ChatViewModel @Inject constructor(
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
) : ViewModel() {

    private val chatId: Long = savedStateHandle.toRoute<ChatRoute>().chatId
    /** Сообщение, на котором открыть чат; 0 — обычное открытие. */
    private val startMessageId: Long = savedStateHandle.toRoute<ChatRoute>().messageId
    private val history = ChatHistory(chatId, td)

    val chat: StateFlow<Chat?> = chatsRepository.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chatsRepository.chats.value[chatId])

    val state: StateFlow<ChatHistory.State> = history.state

    /** Можно ли писать: в канале без прав поле ввода не показываем. */
    val canWrite: StateFlow<Boolean> = combine(chat, groups.statuses) { chat, statuses ->
        chat != null && chat.canWrite(chat.group?.let(statuses::get))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _draft = MutableStateFlow(RichText.Empty)
    /** Набранное в поле ввода, с оформлением. */
    val draft: StateFlow<RichText> = _draft.asStateFlow()

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

    private val _pickedPhotos = MutableStateFlow<List<Uri>>(emptyList())
    /** Выбранные в Photo Picker фото: перед отправкой — превью и подпись. */
    val pickedPhotos: StateFlow<List<Uri>> = _pickedPhotos.asStateFlow()

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
        viewModelScope.launch { loadPinned() }
        viewModelScope.launch {
            val opened = chat.mapNotNull { it }.first()
            // Черновик восстанавливаем, только если пользователь ещё ничего не набрал
            opened.draft?.let { draft ->
                _draft.compareAndSet(RichText.Empty, draft.text)
                draft.replyToMessageId?.let { restoreReply(it) }
            }
            if (startMessageId != 0L && history.openAround(startMessageId)) {
                _scrollTarget.value = startMessageId
            } else {
                history.open(opened.lastReadInboxMessageId.takeIf { opened.unreadCount > 0 })
            }
        }
    }

    /**
     * Экран на виду: `openChat` (TDLib шлёт апдейты чата и считает просмотры), на паузе — `closeChat`.
     * Запросы идут в scope приложения: `closeChat` должен уйти, даже когда ViewModel уже уничтожается.
     */
    fun onResume() {
        resumed.value = true
        appScope.launch { runCatching { td.send(TdApi.OpenChat(chatId)) }.onFailure { Log.e(TAG, "openChat failed", it) } }
        // Открыли чат — ручная пометка «непрочитанный» больше не нужна
        if (chatsRepository.chats.value[chatId]?.isMarkedAsUnread == true) {
            appScope.launch { runCatching { chatsRepository.setMarkedAsUnread(chatId, false) }.onFailure { Log.e(TAG, "unmark failed", it) } }
        }
    }

    fun onPause() {
        if (!resumed.value) return
        resumed.value = false
        saveDraft()
        appScope.launch { runCatching { td.send(TdApi.CloseChat(chatId)) }.onFailure { Log.e(TAG, "closeChat failed", it) } }
    }

    private fun restoreReply(messageId: Long) {
        viewModelScope.launch {
            val message = runCatching { td.send(TdApi.GetMessage(chatId, messageId)) }
                .onFailure { Log.w(TAG, "getMessage($messageId) for draft reply failed", it) }
                .getOrNull()?.toModel() ?: return@launch
            _replyTo.compareAndSet(null, message)
        }
    }

    /** Уходим с экрана — сохраняем набранное и ответ как черновик (или стираем черновик, если всё пусто). */
    private fun saveDraft() {
        // Во время редактирования в поле правка, а черновик — отложенный текст
        val editing = _editing.value != null
        val text = (if (editing) stashedDraft else _draft.value).trimmed()
        val replyId = (if (editing) stashedReply else _replyTo.value)?.id
        val saved = chat.value?.draft
        if (text == (saved?.text ?: RichText.Empty) && replyId == saved?.replyToMessageId) return
        val draft = if (text.isBlank() && replyId == null) null else {
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
                    td.send(TdApi.ViewMessages(chatId, ids.toLongArray(), TdApi.MessageSourceChatHistory(), false))
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
            runCatching { td.send(TdApi.SendChatAction(chatId, null, "", TdApi.ChatActionTyping())) }
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
        _pickedPhotos.value = uris
    }

    fun cancelPhotos() {
        _pickedPhotos.value = emptyList()
    }

    /** Отправка выбранных фото с подписью; ответ, если он начат, уходит вместе с ними. */
    fun sendPhotos(caption: String) {
        val uris = _pickedPhotos.value
        if (uris.isEmpty()) return
        _pickedPhotos.value = emptyList()
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching {
                val images = uris.map { importer.importImage(it) }
                history.sendPhotos(images, RichText(caption.trim()), reply?.id)
            }.onFailure {
                Log.e(TAG, "send photos failed", it)
                _replyTo.value = reply
                _sendError.value = it.message
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

    /** Запись голосового из поля ввода. */
    val recorderState: StateFlow<RecorderState> = recorder.state

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

    private fun sendVoice(recording: VoiceRecording) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendVoice(recording.path, recording.durationSec, recording.waveform, reply?.id) }.onFailure {
                Log.e(TAG, "send voice failed", it)
                _replyTo.value = reply
                _sendError.value = it.message
            }
        }
    }

    private val _stickerSetTitle = MutableStateFlow<String?>(null)

    /** Название пака стикера, по которому тапнули; `null` — диалог закрыт. */
    val stickerSetTitle: StateFlow<String?> = _stickerSetTitle.asStateFlow()

    fun onStickerClick(sticker: StickerInfo) {
        if (sticker.setId == 0L) return
        viewModelScope.launch {
            runCatching { td.send(TdApi.GetStickerSet(sticker.setId)).title }
                .onSuccess { _stickerSetTitle.value = it }
        }
    }

    fun dismissStickerSet() {
        _stickerSetTitle.value = null
    }

    /** Открыть скачанный файл во внешнем приложении. */
    val openFile: SharedFlow<OpenFile> = documents.open

    /** Тап по документу: скачан — открыть, качается — остановить, иначе — скачать. */
    fun onDocumentClick(document: DocumentInfo) = documents.onClick(document)

    /** Отправка файла, выбранного через системный выбор. */
    fun sendFile(uri: Uri) {
        val reply = _replyTo.value
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendDocument(importer.importFile(uri), reply?.id) }.onFailure {
                Log.e(TAG, "send file failed", it)
                _replyTo.value = reply
                _sendError.value = it.message
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

    fun send() {
        _editing.value?.let { return saveEdit(it) }
        val text = _draft.value.trimmed()
        if (text.isBlank()) return
        val reply = _replyTo.value
        val noPreview = dismissedPreviewUrl.value != null
        _draft.value = RichText.Empty
        _replyTo.value = null
        dismissedPreviewUrl.value = null
        viewModelScope.launch {
            runCatching { history.sendText(text, reply?.id, withLinkPreview = !noPreview) }.onFailure {
                Log.e(TAG, "sendMessage failed", it)
                _draft.value = text
                _replyTo.value = reply
                _sendError.value = it.message
            }
        }
    }

    /** «Ответить» в меню или свайп по сообщению. */
    fun startReply(message: Message) {
        _menu.value = null
        _replyTo.value = message
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
                _sendError.value = it.message
            }
        }
    }

    private val pinnedMessages = MutableStateFlow<List<Message>>(emptyList())
    private val pinnedIndex = MutableStateFlow(0)

    /** Плашка закреплённого под шапкой; `null` — закреплённых нет. */
    val pinned: StateFlow<PinnedBanner?> = combine(pinnedMessages, pinnedIndex) { list, index ->
        if (list.isEmpty()) null else PinnedBanner(list[index.coerceIn(list.indices)], index.coerceIn(list.indices), list.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Закреплённые сообщения чата, новые первыми. */
    private suspend fun loadPinned() {
        runCatching {
            td.send(TdApi.SearchChatMessages(chatId, null, "", null, 0, 0, PINNED_LIMIT, TdApi.SearchMessagesFilterPinned()))
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
                _sendError.value = it.message
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

    private companion object {
        const val TAG = "ChatViewModel"
        const val PINNED_LIMIT = 100
        const val STOP_TIMEOUT_MS = 5_000L
        const val VIEW_DEBOUNCE_MS = 300L
        const val MENTION_DEBOUNCE_MS = 200L
        const val MENTION_LIMIT = 20
        const val LINK_PREVIEW_DEBOUNCE_MS = 600L
        const val TYPING_INTERVAL_MS = 5_000L
    }
}
