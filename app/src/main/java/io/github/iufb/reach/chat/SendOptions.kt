package io.github.iufb.reach.chat

import org.drinkless.tdlib.TdApi

/**
 * Опции отправки, общие для всех типов сообщений (PRODUCT.md §4.3).
 * Собираются в одном месте — [ChatViewModel] — и уходят в каждый `send*` [ChatHistory].
 */
data class SendOptions(
    /** Без звука у получателей (`disable_notification`). */
    val silent: Boolean = false,
) {
    fun toTd(): TdApi.MessageSendOptions? {
        if (this == SendOptions()) return null
        return TdApi.MessageSendOptions(null, silent, false, false, false, 0, false, null, 0, 0, false)
    }
}
