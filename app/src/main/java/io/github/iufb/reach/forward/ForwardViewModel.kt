package io.github.iufb.reach.forward

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatListType
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.canWrite
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.navigation.ForwardRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Выбор чата для пересылки: чаты основного списка, куда можно писать, с поиском по названию. */
@HiltViewModel
class ForwardViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    chats: ChatsRepository,
    groups: GroupsRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ForwardRoute>()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** `null` — список ещё не пришёл. */
    val targets: StateFlow<List<Chat>?> = combine(chats.chatList(ChatListType.Main), groups.statuses, _query) { list, statuses, query ->
        list.filter { chat ->
            chat.canWrite(chat.group?.let(statuses::get)) &&
                (query.isBlank() || chat.title.contains(query.trim(), ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _forwarded = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Пересылка ушла — экран открывает целевой чат. */
    val forwarded: SharedFlow<Long> = _forwarded.asSharedFlow()

    private var sending = false

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun forwardTo(chatId: Long) {
        if (sending) return
        sending = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                td.send(
                    TdApi.ForwardMessages(
                        chatId, null, route.fromChatId, route.messageIds.toLongArray(), null, false, false,
                    ),
                )
            }.onSuccess {
                _forwarded.emit(chatId)
            }.onFailure {
                Log.e(TAG, "forwardMessages failed", it)
                _error.value = userErrors.message(it)
                sending = false
            }
        }
    }

    private companion object {
        const val TAG = "ForwardViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
