package io.github.iufb.reach.chats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.presence.ActivityKind
import io.github.iufb.reach.presence.PresenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val chatsRepository: ChatsRepository,
    presence: PresenceRepository,
) : ViewModel() {

    /** Кто что делает в чатах: «печатает…» вместо последнего сообщения. */
    val activities: StateFlow<Map<Long, Map<Sender, ActivityKind>>> = presence.activities

    val list: ChatListType = ChatListType.Main

    /** `null` — список ещё ни разу не пришёл. */
    val chats: StateFlow<List<Chat>?> = chatsRepository.chatList(list)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val allLoaded: StateFlow<Boolean> = chatsRepository.allLoaded(list)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _loggingOut = MutableStateFlow(false)

    /** Выход запрошен: ждём, пока TDLib закроет сессию и навигация уведёт на экран входа. */
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    init {
        loadMore()
    }

    /** Список докрутили до конца — просим следующую порцию. */
    fun loadMore() {
        viewModelScope.launch { chatsRepository.loadMore(list) }
    }

    fun logOut() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            runCatching { auth.logOut() }.onFailure {
                Log.e(TAG, "logOut failed", it)
                _loggingOut.value = false
            }
        }
    }

    private companion object {
        const val TAG = "ChatsViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
