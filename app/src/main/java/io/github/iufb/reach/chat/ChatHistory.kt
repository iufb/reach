package io.github.iufb.reach.chat

import android.util.Log
import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import io.github.iufb.reach.chats.toBody
import org.drinkless.tdlib.TdApi

/**
 * Загруженный кусок истории одного чата (PRODUCT.md §4.3).
 *
 * История — непрерывный отрезок сообщений, который растёт в обе стороны:
 * [loadOlder] — вверх, [loadNewer] — вниз, пока не дойдём до последнего сообщения.
 * `getChatHistory` сам выбирает, сколько вернуть, поэтому «пришло меньше, чем просили» —
 * не конец; конец — только пустой ответ.
 *
 * [followUpdates] держит отрезок в актуальном виде: новые, изменённые и удалённые сообщения.
 * Новые добавляются, только если отрезок доходит до конца истории — иначе они придут с [loadNewer].
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
                val reachedNewest = unread.size < PAGE
                _state.update { pending ->
                    State(
                        reachedNewest = reachedNewest,
                        firstUnreadId = unread.minOf { it.id },
                        opened = true,
                    ).merge(if (reachedNewest) around + pending.messages else around)
                }
                return
            }
        }
        val latest = fetch(0, 0, PAGE)
        // Пришедшие во время загрузки новые сообщения не теряем
        _state.update { pending -> State(reachedNewest = true, opened = true).merge(latest + pending.messages) }
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

    /** Применяет апдейты этого чата; работает, пока не отменят. Запускать до [open]. */
    suspend fun followUpdates() {
        td.updates.collect { update ->
            when (update) {
                is TdApi.UpdateNewMessage -> if (update.message.chatId == chatId) {
                    val message = update.message.toModel()
                    // До open копим всё: open решит, нужны ли они
                    _state.update { s -> if (s.reachedNewest || !s.opened) s.merge(listOf(message)) else s }
                }
                is TdApi.UpdateMessageContent -> if (update.chatId == chatId) {
                    val body = update.newContent.toBody()
                    edit(update.messageId) { copy(content = body) }
                }
                is TdApi.UpdateMessageInteractionInfo -> if (update.chatId == chatId) {
                    val views = update.interactionInfo?.viewCount ?: 0
                    edit(update.messageId) { copy(viewCount = views) }
                }
                // Отправка завершилась: временный id меняется на постоянный
                is TdApi.UpdateMessageSendSucceeded -> if (update.message.chatId == chatId) {
                    replace(update.oldMessageId, update.message.toModel())
                }
                is TdApi.UpdateMessageSendFailed -> if (update.message.chatId == chatId) {
                    Log.w(TAG, "send failed: ${update.error.code} ${update.error.message}")
                    replace(update.oldMessageId, update.message.toModel())
                }
                // fromCache = выгружено из кэша TDLib, а не удалено — такие оставляем
                is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                    val ids = update.messageIds.toSet()
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id in ids }) }
                }
            }
        }
    }

    /**
     * Отправляет текст. Сообщение сразу появляется со статусом «отправляется» (через `UpdateNewMessage`),
     * дальше статус меняют `UpdateMessageSendSucceeded` / `UpdateMessageSendFailed`.
     * Если открыт кусок из середины истории — сначала переходим к концу, иначе своё сообщение не увидеть.
     */
    suspend fun sendText(text: String) {
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
        val pending = td.send(TdApi.SendMessage(chatId, null, null, null, null, content))
        _state.update { it.merge(listOf(pending.toModel())) }
    }

    private fun replace(oldId: Long, message: Message) {
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == oldId || it.id == message.id }).merge(listOf(message)) }
    }

    private inline fun edit(messageId: Long, crossinline change: Message.() -> Message) {
        _state.update { s ->
            if (s.messages.none { it.id == messageId }) s
            else s.copy(messages = s.messages.map { if (it.id == messageId) it.change() else it })
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
