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
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Имя и аватарка автора сообщения в группе. */
data class SenderInfo(val name: String, val photo: io.github.iufb.reach.files.TdFile?, val accentColorId: Int)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    @ApplicationScope private val appScope: CoroutineScope,
    chats: ChatsRepository,
    users: UsersRepository,
    groups: GroupsRepository,
) : ViewModel() {

    private val chatId: Long = savedStateHandle.toRoute<ChatRoute>().chatId
    private val history = ChatHistory(chatId, td)

    val chat: StateFlow<Chat?> = chats.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chats.chats.value[chatId])

    val state: StateFlow<ChatHistory.State> = history.state

    /** Можно ли писать: в канале без прав поле ввода не показываем. */
    val canWrite: StateFlow<Boolean> = combine(chat, groups.statuses) { chat, statuses ->
        chat != null && chat.canWrite(chat.group?.let(statuses::get))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _replyTo = MutableStateFlow<Message?>(null)
    /** Сообщение, на которое отвечаем; плашка над полем ввода. */
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    private val _editing = MutableStateFlow<Message?>(null)
    /** Своё сообщение, которое сейчас редактируем в поле ввода. */
    val editing: StateFlow<Message?> = _editing.asStateFlow()

    /** Набранное до входа в редактирование: вернётся в поле после него. */
    private var stashedDraft = ""
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

    /** Исходные сообщения ответов, которых нет в загруженном отрезке. */
    val replied: StateFlow<Map<Long, Message?>> = history.replied

    private val _scrollTarget = MutableStateFlow<Long?>(null)
    /** Сообщение, к которому надо прокрутить ленту (тап по ответу). */
    val scrollTarget: StateFlow<Long?> = _scrollTarget.asStateFlow()

    /** Ошибка отправки (сам запрос не ушёл). Ошибки доставки видны статусом сообщения. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /** Авторы сообщений по отправителю: пользователи и чаты (анонимные админы, каналы). */
    val senders: StateFlow<Map<Sender, SenderInfo>> = combine(users.users, chats.chats) { userMap, chatMap ->
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
        viewModelScope.launch {
            val opened = chat.mapNotNull { it }.first()
            // Черновик восстанавливаем, только если пользователь ещё ничего не набрал
            opened.draft?.let { draft ->
                _draft.compareAndSet("", draft.text.text)
                draft.replyToMessageId?.let { restoreReply(it) }
            }
            history.open(opened.lastReadInboxMessageId.takeIf { opened.unreadCount > 0 })
        }
    }

    /**
     * Экран на виду: `openChat` (TDLib шлёт апдейты чата и считает просмотры), на паузе — `closeChat`.
     * Запросы идут в scope приложения: `closeChat` должен уйти, даже когда ViewModel уже уничтожается.
     */
    fun onResume() {
        resumed.value = true
        appScope.launch { runCatching { td.send(TdApi.OpenChat(chatId)) }.onFailure { Log.e(TAG, "openChat failed", it) } }
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
        val text = (if (editing) stashedDraft else _draft.value).trim()
        val replyId = (if (editing) stashedReply else _replyTo.value)?.id
        val saved = chat.value?.draft
        if (text == saved?.text?.text.orEmpty() && replyId == saved?.replyToMessageId) return
        val draft = if (text.isEmpty() && replyId == null) null else {
            val content = TdApi.DraftMessageContentText(TdApi.FormattedText(text, emptyArray()), null)
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

    fun onDraftChange(value: String) {
        _draft.value = value
        _sendError.value = null
    }

    fun send() {
        _editing.value?.let { return saveEdit(it) }
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val reply = _replyTo.value
        _draft.value = ""
        _replyTo.value = null
        viewModelScope.launch {
            runCatching { history.sendText(text, reply?.id) }.onFailure {
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
        _draft.value = text.text
        _sendError.value = null
    }

    fun cancelEdit() {
        if (_editing.value == null) return
        _editing.value = null
        _draft.value = stashedDraft
        _replyTo.value = stashedReply
        stashedDraft = ""
        stashedReply = null
    }

    private fun saveEdit(message: Message) {
        val original = message.content.editableText() ?: return cancelEdit()
        val text = _draft.value.trim()
        // Пустым можно сделать только подпись, текст сообщения — нет
        if (text.isEmpty() && message.content is MessageBody.Text) return
        val edited = original.withEditedText(text)
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
            val actions = loadActions(message)
            if (!actions.isEmpty) _menu.value = MessageMenu(message, actions)
        }
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
        const val STOP_TIMEOUT_MS = 5_000L
        const val VIEW_DEBOUNCE_MS = 300L
    }
}
