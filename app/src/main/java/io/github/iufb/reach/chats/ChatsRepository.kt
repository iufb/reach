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
    private val loadLock = Mutex()

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

    /** Просит TDLib прислать следующую порцию чатов. Параллельные вызовы схлопываются. */
    suspend fun loadMore(list: ChatListType) {
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

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateNewChat -> _chats.update { it + (update.chat.id to update.chat.toModel()) }
            is TdApi.UpdateChatTitle -> edit(update.chatId) { copy(title = update.title) }
            is TdApi.UpdateChatPosition -> edit(update.chatId) { copy(positions = positions.with(update.position)) }
            is TdApi.UpdateChatLastMessage -> edit(update.chatId) {
                copy(
                    lastMessage = update.lastMessage?.toLastMessage(),
                    positions = update.positions.fold(positions) { acc, p -> acc.with(p) },
                )
            }
            is TdApi.UpdateChatDraftMessage -> edit(update.chatId) {
                copy(positions = update.positions.fold(positions) { acc, p -> acc.with(p) })
            }
            is TdApi.UpdateChatPhoto -> edit(update.chatId) { copy(photo = update.photo?.small?.toTdFile()) }
            is TdApi.UpdateChatAccentColors -> edit(update.chatId) { copy(accentColorId = update.accentColorId) }
            is TdApi.UpdateChatReadInbox -> edit(update.chatId) { copy(unreadCount = update.unreadCount, lastReadInboxMessageId = update.lastReadInboxMessageId) }
            is TdApi.UpdateAuthorizationState -> if (update.authorizationState is TdApi.AuthorizationStateClosed) {
                // Выход из аккаунта: чужие чаты новому входу не нужны
                _chats.value = emptyMap()
                _allLoaded.value = emptySet()
            }
        }
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
