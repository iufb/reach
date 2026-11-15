package io.github.iufb.reach.chat

import android.util.Log
import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.drinkless.tdlib.TdApi

/**
 * Загруженный кусок истории одного чата (PRODUCT.md §4.3).
 *
 * История — непрерывный отрезок сообщений, который растёт в обе стороны:
 * [loadOlder] — вверх, [loadNewer] — вниз, пока не дойдём до последнего сообщения.
 * `getChatHistory` сам выбирает, сколько вернуть, поэтому «пришло меньше, чем просили» —
 * не конец; конец — только пустой ответ.
 */
class ChatHistory(private val chatId: Long, private val td: TdClient) {

    data class State(
        /** Сначала новые. */
        val messages: List<Message> = emptyList(),
        val reachedOldest: Boolean = false,
        val reachedNewest: Boolean = false,
        /** Первое непрочитанное на момент открытия; `null` — открыли с конца. */
        val firstUnreadId: Long? = null,
        val opened: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val olderLock = Mutex()
    private val newerLock = Mutex()

    /**
     * Открывает историю: с первого непрочитанного, если [lastReadInboxId] указан и за ним есть сообщения,
     * иначе с конца.
     */
    suspend fun open(lastReadInboxId: Long?) {
        if (lastReadInboxId != null && lastReadInboxId != 0L) {
            // PAGE более новых, само прочитанное и чуть старше
            val around = fetch(lastReadInboxId, offset = -PAGE, limit = PAGE * 2)
            val unread = around.filter { it.id > lastReadInboxId }
            if (unread.isNotEmpty()) {
                _state.value = State(
                    messages = around.sortedByDescending { it.id },
                    reachedNewest = unread.size < PAGE,
                    firstUnreadId = unread.minOf { it.id },
                    opened = true,
                )
                return
            }
        }
        _state.value = State(messages = fetch(0, 0, PAGE).sortedByDescending { it.id }, reachedNewest = true, opened = true)
    }

    suspend fun loadOlder() {
        if (!olderLock.tryLock()) return
        try {
            val current = _state.value
            if (!current.opened || current.reachedOldest) return
            val oldest = current.messages.lastOrNull()?.id ?: 0
            val older = fetch(oldest, 0, PAGE).filter { oldest == 0L || it.id < oldest }
            _state.update { s -> if (older.isEmpty()) s.copy(reachedOldest = true) else s.merge(older) }
        } finally {
            olderLock.unlock()
        }
    }

    suspend fun loadNewer() {
        if (!newerLock.tryLock()) return
        try {
            val current = _state.value
            if (!current.opened || current.reachedNewest) return
            val newest = current.messages.firstOrNull()?.id ?: return
            val newer = fetch(newest, offset = -PAGE, limit = PAGE + 1).filter { it.id > newest }
            _state.update { s -> if (newer.isEmpty()) s.copy(reachedNewest = true) else s.merge(newer) }
        } finally {
            newerLock.unlock()
        }
    }

    private suspend fun fetch(fromMessageId: Long, offset: Int, limit: Int): List<Message> =
        runCatching { td.send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false)) }
            .onFailure { Log.e(TAG, "getChatHistory($chatId) failed", it) }
            .getOrNull()?.messages.orEmpty().map { it.toModel() }

    private fun State.merge(added: List<Message>): State {
        val byId = messages.associateBy { it.id } + added.associateBy { it.id }
        return copy(messages = byId.values.sortedByDescending { it.id })
    }

    private companion object {
        const val TAG = "ChatHistory"
        const val PAGE = 50
    }
}
