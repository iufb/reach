package io.github.iufb.reach.chats

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import io.github.iufb.reach.navigation.ChatsRoute
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.push.PushRegistrar
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.presence.ActivityKind
import io.github.iufb.reach.presence.PresenceRepository
import io.github.iufb.reach.core.td.TdException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Вкладка папки; [title] `null` — это «Все чаты». */
data class FolderTab(val list: ChatListType, val title: String?, val unread: Int)

/** Сводка архива для строки над основным списком. */
data class ArchiveSummary(val total: Int, val unreadChats: Int, val titles: List<String>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val pushRegistrar: PushRegistrar,
    private val chatsRepository: ChatsRepository,
    presence: PresenceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Кто что делает в чатах: «печатает…» вместо последнего сообщения. */
    val activities: StateFlow<Map<Long, Map<Sender, ActivityKind>>> = presence.activities

    private val isArchive = savedStateHandle.toRoute<ChatsRoute>().archive

    private val _list = MutableStateFlow(if (isArchive) ChatListType.Archive else ChatListType.Main)

    /** Показанный список: архив, основной или выбранная папка. */
    val list: StateFlow<ChatListType> = _list.asStateFlow()

    /** Вкладки папок над основным списком; пусто — папок нет или это архив. */
    val tabs: StateFlow<List<FolderTab>> = combine(chatsRepository.folders, chatsRepository.allCounts) { folders, counts ->
        if (isArchive) return@combine emptyList()
        val titles = folders.folders.associate { it.id to it.title }
        folders.lists.map { list ->
            FolderTab(list, (list as? ChatListType.Folder)?.let { titles[it.id] }, counts[list]?.unreadUnmuted ?: 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** `null` — список ещё ни разу не пришёл. */
    val chats: StateFlow<List<Chat>?> = _list.flatMapLatest { list -> chatsRepository.chatList(list).map<List<Chat>, List<Chat>?> { it }.onStart { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val allLoaded: StateFlow<Boolean> = _list.flatMapLatest(chatsRepository::allLoaded)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Строка «Архив» над основным списком; `null` — архив пуст или это сам архив. */
    val archive: StateFlow<ArchiveSummary?> = combine(
        chatsRepository.counts(ChatListType.Archive),
        chatsRepository.chatList(ChatListType.Archive),
        _list,
    ) { counts, archived, list ->
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

    private val _openChat = Channel<Long>(Channel.BUFFERED)

    /** Чат, который нужно открыть (например, Избранное после `createPrivateChat`). */
    val openChat: Flow<Long> = _openChat.receiveAsFlow()

    /** Открыть Избранное — чат с самим собой. */
    fun openSavedMessages() {
        viewModelScope.launch {
            try {
                _openChat.send(chatsRepository.savedMessagesChatId())
            } catch (e: TdException) {
                Log.e(TAG, "open saved messages failed", e)
                _errors.send(e.message)
            }
        }
    }

    private val _loggingOut = MutableStateFlow(false)

    /** Выход запрошен: ждём, пока TDLib закроет сессию и навигация уведёт на экран входа. */
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    init {
        loadMore()
        // Первая порция архива — чтобы показать строку «Архив» с превью
        if (!isArchive) viewModelScope.launch { chatsRepository.loadMore(ChatListType.Archive) }
        // Выбранную папку удалили — возвращаемся во «Все чаты»
        viewModelScope.launch {
            chatsRepository.folders.collect { folders ->
                val current = _list.value
                if (current is ChatListType.Folder && current !in folders.lists) _list.value = ChatListType.Main
            }
        }
    }

    fun selectList(list: ChatListType) {
        if (_list.value == list) return
        _list.value = list
        loadMore()
    }

    /** Список докрутили до конца — просим следующую порцию. */
    fun loadMore() {
        val list = _list.value
        viewModelScope.launch { chatsRepository.loadMore(list) }
    }

    fun openMenu(chatId: Long) {
        menuChatId.value = chatId
    }

    fun dismissMenu() {
        menuChatId.value = null
    }

    fun setPinned(chat: Chat, pinned: Boolean) = chatAction { chatsRepository.setPinned(chat.id, _list.value, pinned) }

    /** В архив или обратно в основной список. */
    fun setArchived(chat: Chat, archived: Boolean) = chatAction {
        chatsRepository.moveToList(chat.id, if (archived) ChatListType.Archive else ChatListType.Main)
    }

    /** Без звука на [seconds] секунд; 0 — включить звук. */
    fun setMuteFor(chat: Chat, seconds: Int) = chatAction { chatsRepository.setMuteFor(chat.id, seconds) }

    /** «Пометить непрочитанным» или прочитать чат целиком. */
    fun setUnread(chat: Chat, unread: Boolean) = chatAction {
        if (unread) chatsRepository.setMarkedAsUnread(chat.id, true) else chatsRepository.markAsRead(chat)
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
            runCatching {
                // Сначала отвязываем push: после выхода сессии уже нет
                pushRegistrar.unregister()
                auth.logOut()
            }.onFailure {
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
