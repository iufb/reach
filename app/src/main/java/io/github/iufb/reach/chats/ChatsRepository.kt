package io.github.iufb.reach.chats

import android.util.Log
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdException
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.files.toTdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Чаты, собранные из апдейтов (PRODUCT.md §4.2, §7).
 *
 * TDLib сам присылает `UpdateNewChat` и изменения; список — это чаты с позицией
 * в нужном [ChatListType], отсортированные по `order`. `loadChats` только просит
 * TDLib прислать следующие чаты — они придут апдейтами.
 */
@Singleton
class ChatsRepository @Inject constructor(
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _chats = MutableStateFlow<Map<Long, Chat>>(emptyMap())

    /** Все известные чаты по id. */
    val chats: StateFlow<Map<Long, Chat>> = _chats.asStateFlow()

    private val _allLoaded = MutableStateFlow<Set<ChatListType>>(emptySet())
    /** Свой замок на каждый список: архив и папки грузятся параллельно с основным. */
    private val loadLocks = mutableMapOf<ChatListType, Mutex>()

    private val _folders = MutableStateFlow(ChatFolders.Empty)

    /** Папки пользователя; обновляются по `UpdateChatFolders`. */
    val folders: StateFlow<ChatFolders> = _folders.asStateFlow()

    /** Id текущего пользователя из опции `my_id`; 0 — ещё неизвестен. */
    @Volatile
    var myUserId: Long = 0
        private set

    /** «Без звука» по умолчанию для типов чатов, секунды. */
    private var scopeMute: Map<NotificationScope, Int> = emptyMap()

    private val _counts = MutableStateFlow<Map<ChatListType, ChatListCounts>>(emptyMap())

    init {
        // UNDISPATCHED: подписка на апдейты регистрируется сразу, до того как TDLib начнёт слать чаты
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect(::onUpdate)
        }
    }

    /** Чаты списка [list] в порядке TDLib (сверху — больший `order`). */
    fun chatList(list: ChatListType): Flow<List<Chat>> = _chats
        .map { all ->
            all.values
                .mapNotNull { chat -> chat.positions[list]?.let { chat to it.order } }
                .sortedWith(compareByDescending<Pair<Chat, Long>> { it.second }.thenByDescending { it.first.id })
                .map { it.first }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Все чаты списка уже загружены: дальше `loadChats` вернёт 404. */
    fun allLoaded(list: ChatListType): Flow<Boolean> = _allLoaded.map { list in it }.distinctUntilChanged()

    /** Счётчики списка; `null` — TDLib их ещё не присылал. */
    fun counts(list: ChatListType): Flow<ChatListCounts?> = _counts.map { it[list] }.distinctUntilChanged()

    /** Счётчики всех списков, о которых TDLib уже сообщил. */
    val allCounts: StateFlow<Map<ChatListType, ChatListCounts>> = _counts.asStateFlow()

    /** Просит TDLib прислать следующую порцию чатов. Параллельные вызовы схлопываются. */
    suspend fun loadMore(list: ChatListType) {
        val loadLock = synchronized(loadLocks) { loadLocks.getOrPut(list) { Mutex() } }
        if (!loadLock.tryLock()) return
        try {
            if (list in _allLoaded.value) return
            td.send(TdApi.LoadChats(list.toTd(), PAGE_SIZE))
        } catch (e: TdException) {
            if (e.code == NOT_FOUND) _allLoaded.update { it + list } else Log.e(TAG, "loadChats failed", e)
        } finally {
            loadLock.unlock()
        }
    }

    /** Закрепляет или открепляет чат в списке [list]. Позиция придёт апдейтом `UpdateChatPosition`. */
    suspend fun setPinned(chatId: Long, list: ChatListType, pinned: Boolean) {
        td.send(TdApi.ToggleChatIsPinned(list.toTd(), chatId, pinned))
    }

    /** Переносит чат в список [list] (архив ↔ основной). Позиции придут апдейтами. */
    suspend fun moveToList(chatId: Long, list: ChatListType) {
        td.send(TdApi.AddChatToList(chatId, list.toTd()))
    }

    /**
     * Без звука на [seconds] секунд (0 — включить звук). TDLib принимает настройки целиком,
     * поэтому берём текущие и меняем только `muteFor`.
     */
    suspend fun setMuteFor(chatId: Long, seconds: Int) {
        val settings = td.send(TdApi.GetChat(chatId)).notificationSettings
        settings.useDefaultMuteFor = false
        settings.muteFor = seconds
        td.send(TdApi.SetChatNotificationSettings(chatId, settings))
    }

    /** Ручная пометка «непрочитанный». */
    suspend fun setMarkedAsUnread(chatId: Long, marked: Boolean) {
        td.send(TdApi.ToggleChatIsMarkedAsUnread(chatId, marked))
    }

    /** Читает чат целиком: снимает пометку и отмечает прочитанным последнее сообщение. */
    suspend fun markAsRead(chat: Chat) {
        if (chat.isMarkedAsUnread) setMarkedAsUnread(chat.id, false)
        val last = chat.lastMessage?.id ?: return
        if (chat.unreadCount > 0) td.send(TdApi.ViewMessages(chat.id, longArrayOf(last), TdApi.MessageSourceChatList(), true))
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateNewChat -> _chats.update {
                it + (update.chat.id to update.chat.toModel().withMute(scopeMute).withSaved(myUserId))
            }
            is TdApi.UpdateOption -> if (update.name == "my_id") {
                myUserId = (update.value as? TdApi.OptionValueInteger)?.value ?: 0
                _chats.update { all -> all.mapValues { (_, chat) -> chat.withSaved(myUserId) } }
            }
            is TdApi.UpdateChatIsMarkedAsUnread -> edit(update.chatId) { copy(isMarkedAsUnread = update.isMarkedAsUnread) }
            is TdApi.UpdateChatNotificationSettings -> edit(update.chatId) {
                copy(muteFor = update.notificationSettings.ownMuteFor).withMute(scopeMute)
            }
            is TdApi.UpdateScopeNotificationSettings -> {
                scopeMute = scopeMute + (update.scope.toModel() to update.notificationSettings.muteFor)
                _chats.update { all -> all.mapValues { (_, chat) -> chat.withMute(scopeMute) } }
            }
            is TdApi.UpdateChatTitle -> edit(update.chatId) { copy(title = update.title) }
            is TdApi.UpdateChatPosition -> edit(update.chatId) { copy(positions = positions.with(update.position)) }
            is TdApi.UpdateChatLastMessage -> edit(update.chatId) {
                copy(
                    lastMessage = update.lastMessage?.toLastMessage(),
                    positions = update.positions.fold(positions) { acc, p -> acc.with(p) },
                )
            }
            is TdApi.UpdateChatDraftMessage -> edit(update.chatId) {
                copy(
                    draft = update.draftMessage?.toDraft(),
                    positions = update.positions.fold(positions) { acc, p -> acc.with(p) },
                )
            }
            is TdApi.UpdateChatPhoto -> edit(update.chatId) { copy(photo = update.photo?.small?.toTdFile()) }
            is TdApi.UpdateChatAccentColors -> edit(update.chatId) { copy(accentColorId = update.accentColorId) }
            is TdApi.UpdateChatPermissions -> edit(update.chatId) {
                copy(canSendBasicMessages = update.permissions.canSendBasicMessages)
            }
            is TdApi.UpdateChatReadInbox -> edit(update.chatId) { copy(unreadCount = update.unreadCount, lastReadInboxMessageId = update.lastReadInboxMessageId) }
            is TdApi.UpdateChatFolders -> _folders.value = ChatFolders(
                update.chatFolders.map { ChatFolder(it.id, it.name.text.text) },
                update.mainChatListPosition,
            )
            is TdApi.UpdateUnreadChatCount -> update.chatList.toModel()?.let { list ->
                _counts.update { it + (list to ChatListCounts(update.totalCount, update.unreadCount, update.unreadUnmutedCount)) }
            }
            is TdApi.UpdateAuthorizationState -> if (update.authorizationState is TdApi.AuthorizationStateClosed) {
                // Выход из аккаунта: чужие чаты новому входу не нужны
                _chats.value = emptyMap()
                _allLoaded.value = emptySet()
                _counts.value = emptyMap()
                _folders.value = ChatFolders.Empty
                scopeMute = emptyMap()
                myUserId = 0
            }
        }
    }

    private fun Chat.withSaved(myId: Long) = copy(isSavedMessages = myId != 0L && kind == ChatKind.Private && userId == myId)

    /** Открывает (создаёт при необходимости) чат с собой. */
    suspend fun savedMessagesChatId(): Long {
        val me = myUserId.takeIf { it != 0L } ?: td.send(TdApi.GetMe()).id
        return td.send(TdApi.CreatePrivateChat(me, false)).id
    }

    private inline fun edit(chatId: Long, crossinline change: Chat.() -> Chat) {
        _chats.update { all -> all[chatId]?.let { all + (chatId to it.change()) } ?: all }
    }

    private companion object {
        const val TAG = "ChatsRepository"
        const val PAGE_SIZE = 50
        const val NOT_FOUND = 404
    }
}
