package io.github.iufb.reach.chat

import android.os.SystemClock
import android.util.Log
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.core.td.TdClient
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

    /** Загружает то, с чего [ChatHistory.open] начнёт этот чат: окрестность первого непрочитанного или конец. */
    suspend fun load(chat: Chat) {
        val lastRead = chat.lastReadInboxMessageId.takeIf { chat.unreadCount > 0 && it != 0L }
        if (lastRead != null) {
            val around = request(aroundKey(chat.id, lastRead)) ?: return
            if (around.messages.any { it.id > lastRead }) return
        }
        request(latestKey(chat.id))
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

        fun aroundKey(chatId: Long, lastReadInboxId: Long) = Key(chatId, lastReadInboxId, -PAGE, PAGE * 2)

        fun latestKey(chatId: Long) = Key(chatId, 0, 0, PAGE)

        private const val TAG = "HistoryPrefetch"
        private const val MAX_AGE_MS = 3_000L
    }
}
