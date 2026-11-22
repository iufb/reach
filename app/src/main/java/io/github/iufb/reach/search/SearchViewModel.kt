package io.github.iufb.reach.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdException
import io.github.iufb.reach.navigation.SearchRoute
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Найденное сообщение и чат, в котором оно лежит. */
data class FoundMessage(
    val chat: Chat,
    val messageId: Long,
    /** Unix-время, секунды. */
    val date: Int,
    val body: MessageBody,
)

/** Подпись результата: почему он найден — username и число участников / подписчиков. */
data class ChatDetails(val username: String = "", val memberCount: Int = 0)

/**
 * Что показывает поиск. [isRecent] — запрос пустой: [chats] — недавно найденные.
 * [publicChats] — публичные чаты и каналы, которых нет среди своих.
 */
data class SearchResults(
    val query: String = "",
    val chats: List<Chat> = emptyList(),
    val publicChats: List<Chat> = emptyList(),
    val messages: List<FoundMessage> = emptyList(),
    val isRecent: Boolean = true,
    val loading: Boolean = false,
    val canLoadMoreMessages: Boolean = false,
    /** Подписи найденных чатов по id; нет записи — подписи нет. */
    val details: Map<Long, ChatDetails> = emptyMap(),
) {
    val isEmpty: Boolean get() = chats.isEmpty() && publicChats.isEmpty() && messages.isEmpty()
}

/**
 * Поиск (PRODUCT.md §4.2): свои чаты (`searchChats`, затем `searchChatsOnServer`), публичные
 * (`searchPublicChats`) и сообщения во всех чатах (`searchMessages`, с пагинацией).
 * Пустой запрос — недавние (`searchRecentlyFoundChats`). Новый запрос отменяет старый.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    chats: ChatsRepository,
    users: UsersRepository,
    groups: GroupsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val found = MutableStateFlow(Found())
    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var lastQuery: String? = null

    /** Чаты берутся свежими из репозитория: TDLib присылает их апдейтами раньше ответа поиска. */
    val results: StateFlow<SearchResults> = combine(found, chats.chats, users.users, groups.info) { f, all, userMap, groupMap ->
        val own = f.chatIds.mapNotNull(all::get)
        val ownIds = own.mapTo(HashSet()) { it.id }
        val public = f.publicChatIds.filter { it !in ownIds }.mapNotNull(all::get)
        SearchResults(
            query = f.query,
            chats = own,
            publicChats = public,
            details = (own + public).associate { chat ->
                chat.id to when {
                    chat.group != null -> groupMap[chat.group]?.let { ChatDetails(it.username, it.memberCount) } ?: ChatDetails()
                    else -> ChatDetails(username = chat.userId?.let(userMap::get)?.username.orEmpty())
                }
            },
            messages = f.messages.mapNotNull { m -> all[m.chatId]?.let { FoundMessage(it, m.id, m.date, m.body) } },
            isRecent = f.query.isEmpty(),
            loading = f.loading,
            canLoadMoreMessages = f.nextOffset.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchResults(loading = true))

    init {
        onQueryChange(savedStateHandle.toRoute<SearchRoute>().query)
    }

    fun onQueryChange(value: String) {
        _query.value = value
        val query = value.trim()
        // Пробелы по краям запрос не меняют
        if (query == lastQuery) return
        lastQuery = query
        searchJob?.cancel()
        moreJob?.cancel()
        searchJob = viewModelScope.launch { search(query) }
    }

    /** Чат выбран из результатов — попадает в недавние. */
    fun onChatOpened(chatId: Long) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.AddRecentlyFoundChat(chatId)) }.onFailure { Log.w(TAG, "addRecentlyFoundChat failed", it) }
        }
    }

    /** Список сообщений докрутили до конца. */
    fun loadMoreMessages() {
        val current = found.value
        if (current.nextOffset.isEmpty() || current.loading || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            val page = searchMessages(current.query, current.nextOffset) ?: return@launch
            found.update { f ->
                if (f.query != current.query) f else f.copy(messages = f.messages + page.messages, nextOffset = page.nextOffset)
            }
        }
    }

    private suspend fun search(query: String) {
        if (query.isEmpty()) {
            found.value = Found(chatIds = ids { td.send(TdApi.SearchRecentlyFoundChats("", null, LIMIT)).chatIds })
            return
        }
        // Старые результаты остаются на экране, пока идёт новый запрос
        found.update { it.copy(loading = true) }
        delay(DEBOUNCE_MS)
        found.value = Found(query = query, loading = true)
        coroutineScope {
            launch {
                val local = ids { td.send(TdApi.SearchChats(query, null, LIMIT)).chatIds }
                found.update { it.copy(chatIds = local) }
                val server = ids { td.send(TdApi.SearchChatsOnServer(query, null, LIMIT)).chatIds }
                found.update { it.copy(chatIds = (it.chatIds + server).distinct()) }
            }
            launch {
                val public = ids { td.send(TdApi.SearchPublicChats(query, null)).chatIds }
                found.update { it.copy(publicChatIds = public) }
            }
            launch {
                val page = searchMessages(query, "")
                if (page != null) found.update { it.copy(messages = page.messages, nextOffset = page.nextOffset) }
            }
        }
        found.update { it.copy(loading = false) }
    }

    private suspend fun searchMessages(query: String, offset: String): MessagePage? = try {
        val result = td.send(TdApi.SearchMessages(null, query, offset, LIMIT, null, null, 0, 0))
        MessagePage(result.messages.map { RawMessage(it.chatId, it.id, it.date, it.content.toBody()) }, result.nextOffset.orEmpty())
    } catch (e: TdException) {
        Log.w(TAG, "searchMessages failed", e)
        null
    }

    /** Ошибка одного запроса не роняет поиск: пустой результат. */
    private suspend fun ids(block: suspend () -> LongArray): List<Long> = try {
        block().toList()
    } catch (e: TdException) {
        Log.w(TAG, "search request failed", e)
        emptyList()
    }

    private data class RawMessage(val chatId: Long, val id: Long, val date: Int, val body: MessageBody)

    private data class MessagePage(val messages: List<RawMessage>, val nextOffset: String)

    private data class Found(
        val query: String = "",
        val chatIds: List<Long> = emptyList(),
        val publicChatIds: List<Long> = emptyList(),
        val messages: List<RawMessage> = emptyList(),
        val nextOffset: String = "",
        val loading: Boolean = false,
    )

    private companion object {
        const val TAG = "SearchViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 300L
        const val LIMIT = 50
    }
}
