package io.github.iufb.reach.chat

import org.drinkless.tdlib.TdApi

/**
 * Опции отправки, общие для всех типов сообщений (PRODUCT.md §4.3).
 * Собираются в одном месте — [ChatViewModel] — и уходят в каждый `send*` [ChatHistory].
 */
data class SendOptions(
    /** Без звука у получателей (`disable_notification`). */
    val silent: Boolean = false,
    /** Отложенная отправка; `null` — сразу. */
    val schedule: Schedule? = null,
) {
    fun toTd(): TdApi.MessageSendOptions? {
        if (this == SendOptions()) return null
        return TdApi.MessageSendOptions(null, silent, false, false, false, 0, false, schedule?.toTd(), 0, 0, false)
    }
}

/** Когда отправить отложенное сообщение. */
sealed interface Schedule {
    /** В [date] (unix-секунды). */
    data class At(val date: Int) : Schedule

    /** Как только собеседник появится в сети (только личные чаты). */
    data object WhenOnline : Schedule

    fun toTd(): TdApi.MessageSchedulingState = when (this) {
        is At -> TdApi.MessageSchedulingStateSendAtDate(date, 0)
        WhenOnline -> TdApi.MessageSchedulingStateSendWhenOnline()
    }
}

internal fun TdApi.MessageSchedulingState.toModel(): Schedule? = when (this) {
    is TdApi.MessageSchedulingStateSendAtDate -> Schedule.At(sendDate)
    is TdApi.MessageSchedulingStateSendWhenOnline -> Schedule.WhenOnline
    else -> null
}
