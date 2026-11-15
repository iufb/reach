package io.github.iufb.reach.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.canWrite
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.ChatRoute
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Имя и аватарка автора сообщения в группе. */
data class SenderInfo(val name: String, val photo: io.github.iufb.reach.files.TdFile?, val accentColorId: Int)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    td: TdClient,
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

    init {
        // UNDISPATCHED: подписка на апдейты раньше, чем уйдёт первый getChatHistory
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { history.followUpdates() }
        viewModelScope.launch {
            val opened = chat.mapNotNull { it }.first()
            history.open(opened.lastReadInboxMessageId.takeIf { opened.unreadCount > 0 })
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
        _sendError.value = null
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            runCatching { history.sendText(text) }.onFailure {
                Log.e(TAG, "sendMessage failed", it)
                _draft.value = text
                _sendError.value = it.message
            }
        }
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
    }
}
