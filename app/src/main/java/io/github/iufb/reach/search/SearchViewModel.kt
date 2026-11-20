package io.github.iufb.reach.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Что показывает поиск. [isRecent] — запрос пустой, это недавно найденные чаты. */
data class SearchResults(
    val chats: List<Chat> = emptyList(),
    val isRecent: Boolean = true,
    val loading: Boolean = false,
)

/**
 * Поиск по своим чатам (PRODUCT.md §4.2): сначала локально (`searchChats`), затем на сервере
 * (`searchChatsOnServer`), без дублей. Пустой запрос — недавние (`searchRecentlyFoundChats`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val td: TdClient,
    private val chats: ChatsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Найденные id по порядку; сами чаты берутся свежими из репозитория. */
    private val found: Flow<FoundIds> = _query.transformLatest { raw ->
        val query = raw.trim()
        if (query.isEmpty()) {
            emit(FoundIds(recent(), isRecent = true, loading = false))
            return@transformLatest
        }
        // Дебаунс: transformLatest отменит ожидание, если пользователь продолжит печатать
        delay(DEBOUNCE_MS)
        val local = request { td.send(TdApi.SearchChats(query, null, LIMIT)).chatIds.toList() }
        emit(FoundIds(local, isRecent = false, loading = true))
        val server = request { td.send(TdApi.SearchChatsOnServer(query, null, LIMIT)).chatIds.toList() }
        emit(FoundIds((local + server).distinct(), isRecent = false, loading = false))
    }

    val results: StateFlow<SearchResults> = combine(found, chats.chats) { ids, all ->
        SearchResults(ids.ids.mapNotNull(all::get), ids.isRecent, ids.loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchResults(loading = true))

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Чат выбран из результатов — попадает в недавние. */
    fun onChatOpened(chatId: Long) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.AddRecentlyFoundChat(chatId)) }.onFailure { Log.w(TAG, "addRecentlyFoundChat failed", it) }
        }
    }

    private suspend fun recent(): List<Long> =
        request { td.send(TdApi.SearchRecentlyFoundChats("", null, LIMIT)).chatIds.toList() }

    /** Ошибка поиска не роняет экран: пустой результат. */
    private suspend fun request(block: suspend () -> List<Long>): List<Long> = try {
        block()
    } catch (e: TdException) {
        Log.w(TAG, "search request failed", e)
        emptyList()
    }

    private data class FoundIds(val ids: List<Long>, val isRecent: Boolean, val loading: Boolean)

    private companion object {
        const val TAG = "SearchViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEBOUNCE_MS = 300L
        const val LIMIT = 50
    }
}
