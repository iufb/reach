package io.github.iufb.reach.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.ChatRoute
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

    private val chatId: Long = savedStateHandle.toRoute<ChatRoute>().chatId
    private val history = ChatHistory(chatId, td)

    val chat: StateFlow<Chat?> = chats.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chats.chats.value[chatId])

    val state: StateFlow<ChatHistory.State> = history.state

    /** Авторы сообщений по отправителю: пользователи и чаты (анонимные админы, каналы). */
    val senders: StateFlow<Map<Sender, SenderInfo>> = combine(users.users, chats.chats) { userMap, chatMap ->
        buildMap {
            userMap.values.forEach { put(Sender.User(it.id), SenderInfo(it.fullName, it.photo, it.accentColorId)) }
            chatMap.values.forEach { put(Sender.Chat(it.id), SenderInfo(it.title, it.photo, it.accentColorId)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    init {
        viewModelScope.launch {
            val opened = chat.mapNotNull { it }.first()
            history.open(opened.lastReadInboxMessageId.takeIf { opened.unreadCount > 0 })
        }
    }

    fun loadOlder() {
        viewModelScope.launch { history.loadOlder() }
    }

    fun loadNewer() {
        viewModelScope.launch { history.loadNewer() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
