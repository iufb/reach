package io.github.iufb.reach.chat

import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.toBody
import org.drinkless.tdlib.TdApi

sealed interface Sender {
    data class User(val id: Long) : Sender
    data class Chat(val id: Long) : Sender
}

/** Статус отправки своего сообщения. Отправленное — без статуса. */
enum class SendingState { Pending, Failed }

data class Message(
    val id: Long,
    val sender: Sender,
    /** Unix-время, секунды. */
    val date: Int,
    val isOutgoing: Boolean,
    val content: MessageBody,
    /** Подпись автора поста в канале, если включена. */
    val authorSignature: String,
    /** Просмотры поста в канале; 0 — неизвестно. */
    val viewCount: Int,
    val sendingState: SendingState? = null,
)

internal fun TdApi.Message.toModel() = Message(
    id = id,
    sender = when (val s = senderId) {
        is TdApi.MessageSenderUser -> Sender.User(s.userId)
        is TdApi.MessageSenderChat -> Sender.Chat(s.chatId)
        else -> Sender.Chat(chatId)
    },
    date = date,
    isOutgoing = isOutgoing,
    content = content.toBody(),
    authorSignature = authorSignature,
    viewCount = interactionInfo?.viewCount ?: 0,
    sendingState = when (sendingState) {
        is TdApi.MessageSendingStatePending -> SendingState.Pending
        is TdApi.MessageSendingStateFailed -> SendingState.Failed
        else -> null
    },
)
