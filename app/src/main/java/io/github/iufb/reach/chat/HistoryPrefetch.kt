package io.github.iufb.reach.chat

import android.os.SystemClock
import android.util.Log
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Первая страница истории, загруженная до перехода в чат. Список чатов по тапу просит её здесь и
 * открывает чат, когда она пришла: тогда лента собирается в первом кадре перехода, а не посреди
 * слайда (кадр со сборкой двух десятков пузырей — 60–70 мс, на нём анимация дёргалась).
 *
 * Запросы — те же, что делает [ChatHistory.open]; [ChatHistory] забирает готовый ответ вместо
 * повторного запроса. Ответ живёт [MAX_AGE_MS]: если чат так и не открыли, он не используется.
 */
@Singleton
class HistoryPrefetch @Inject constructor(private val td: TdClient) {

    /** Параметры `getChatHistory`. */
    data class Key(val chatId: Long, val fromMessageId: Long, val offset: Int, val limit: Int)

    private class Page(val messages: TdApi.Messages, val loadedAt: Long)

    private val pages = ConcurrentHashMap<Key, Page>()

    private class Pinned(val found: TdApi.FoundChatMessages, val loadedAt: Long)

    private val pinned = ConcurrentHashMap<Long, Pinned>()

    /**
     * Загружает то, с чего чат начнёт: историю — как [ChatHistory.open] (окрестность первого непрочитанного
     * или конец) и закреплённые для плашки под шапкой — иначе она появляется посреди перехода и сдвигает ленту.
     */
    suspend fun load(chat: Chat): Unit = coroutineScope {
        launch { requestPinned(chat.id) }
        val lastRead = chat.lastReadInboxMessageId.takeIf { chat.unreadCount > 0 && it != 0L }
        if (lastRead != null) {
            val around = request(aroundKey(chat.id, lastRead)) ?: return@coroutineScope
            if (around.messages.any { it.id > lastRead }) return@coroutineScope
        }
        request(latestKey(chat.id))
        Unit
    }

    /** Закреплённые чата, если загружены недавно; забираются один раз. */
    fun takePinned(chatId: Long): TdApi.FoundChatMessages? {
        val page = pinned.remove(chatId) ?: return null
        return page.found.takeIf { SystemClock.elapsedRealtime() - page.loadedAt < MAX_AGE_MS }
    }

    private suspend fun requestPinned(chatId: Long) {
        runCatching {
            td.send(TdApi.SearchChatMessages(chatId, ChatScope.Whole.toTd(), "", null, 0, 0, PINNED_LIMIT, TdApi.SearchMessagesFilterPinned()))
        }
            .onFailure { Log.w(TAG, "prefetch pinned $chatId failed", it) }
            .getOrNull()
            ?.let { pinned[chatId] = Pinned(it, SystemClock.elapsedRealtime()) }
    }

    /** Готовый ответ на [key], если он свежий; забирается один раз. */
    fun take(key: Key): TdApi.Messages? {
        val page = pages.remove(key) ?: return null
        return page.messages.takeIf { SystemClock.elapsedRealtime() - page.loadedAt < MAX_AGE_MS }
    }

    private suspend fun request(key: Key): TdApi.Messages? =
        runCatching { td.send(TdApi.GetChatHistory(key.chatId, key.fromMessageId, key.offset, key.limit, false)) }
            .onFailure { Log.w(TAG, "prefetch ${key.chatId} failed", it) }
            .getOrNull()
            ?.also { pages[key] = Page(it, SystemClock.elapsedRealtime()) }

    companion object {
        /** Страница истории; общая с [ChatHistory], чтобы запросы совпадали. */
        const val PAGE = 50

        /** Сколько закреплённых грузить для плашки; общий с чатом. */
        const val PINNED_LIMIT = 100

        fun aroundKey(chatId: Long, lastReadInboxId: Long) = Key(chatId, lastReadInboxId, -PAGE, PAGE * 2)

        fun latestKey(chatId: Long) = Key(chatId, 0, 0, PAGE)

        private const val TAG = "HistoryPrefetch"
        private const val MAX_AGE_MS = 3_000L
    }
}
