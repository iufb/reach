package io.github.iufb.reach.chats

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import io.github.iufb.reach.navigation.ChatsRoute
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.presence.ActivityKind
import io.github.iufb.reach.presence.PresenceRepository
import io.github.iufb.reach.core.td.TdException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Сводка архива для строки над основным списком. */
data class ArchiveSummary(val total: Int, val unreadChats: Int, val titles: List<String>)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val chatsRepository: ChatsRepository,
    presence: PresenceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Кто что делает в чатах: «печатает…» вместо последнего сообщения. */
    val activities: StateFlow<Map<Long, Map<Sender, ActivityKind>>> = presence.activities

    val list: ChatListType = if (savedStateHandle.toRoute<ChatsRoute>().archive) ChatListType.Archive else ChatListType.Main

    /** `null` — список ещё ни разу не пришёл. */
    val chats: StateFlow<List<Chat>?> = chatsRepository.chatList(list)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val allLoaded: StateFlow<Boolean> = chatsRepository.allLoaded(list)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Строка «Архив» над основным списком; `null` — архив пуст или это сам архив. */
    val archive: StateFlow<ArchiveSummary?> = combine(
        chatsRepository.counts(ChatListType.Archive),
        chatsRepository.chatList(ChatListType.Archive),
    ) { counts, archived ->
        val total = maxOf(counts?.total ?: 0, archived.size)
        ArchiveSummary(total, counts?.unread ?: 0, archived.take(ARCHIVE_PREVIEW).map { it.title })
            .takeIf { list == ChatListType.Main && total > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val menuChatId = MutableStateFlow<Long?>(null)

    /** Чат, для которого открыто меню действий; `null` — меню закрыто. Берётся свежим из списка. */
    val menuChat: StateFlow<Chat?> = combine(menuChatId, chatsRepository.chats) { id, all -> id?.let(all::get) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _errors = Channel<String>(Channel.BUFFERED)

    /** Ошибки действий над чатами — показываются один раз снекбаром. */
    val errors: Flow<String> = _errors.receiveAsFlow()

    private val _loggingOut = MutableStateFlow(false)

    /** Выход запрошен: ждём, пока TDLib закроет сессию и навигация уведёт на экран входа. */
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    init {
        loadMore()
        // Первая порция архива — чтобы показать строку «Архив» с превью
        if (list == ChatListType.Main) viewModelScope.launch { chatsRepository.loadMore(ChatListType.Archive) }
    }

    /** Список докрутили до конца — просим следующую порцию. */
    fun loadMore() {
        viewModelScope.launch { chatsRepository.loadMore(list) }
    }

    fun openMenu(chatId: Long) {
        menuChatId.value = chatId
    }

    fun dismissMenu() {
        menuChatId.value = null
    }

    fun setPinned(chat: Chat, pinned: Boolean) = chatAction { chatsRepository.setPinned(chat.id, list, pinned) }

    /** В архив или обратно в основной список. */
    fun setArchived(chat: Chat, archived: Boolean) = chatAction {
        chatsRepository.moveToList(chat.id, if (archived) ChatListType.Archive else ChatListType.Main)
    }

    /** Действие из меню: меню закрывается сразу, ошибка TDLib уходит в [errors]. */
    private fun chatAction(action: suspend () -> Unit) {
        dismissMenu()
        viewModelScope.launch {
            try {
                action()
            } catch (e: TdException) {
                Log.e(TAG, "chat action failed", e)
                _errors.send(e.message)
            }
        }
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
        const val ARCHIVE_PREVIEW = 5
    }
}
