package io.github.iufb.reach.chat

import io.github.iufb.reach.chats.MessagePreview
import io.github.iufb.reach.chats.toPreview
import org.drinkless.tdlib.TdApi

sealed interface Sender {
    data class User(val id: Long) : Sender
    data class Chat(val id: Long) : Sender
}

data class Message(
    val id: Long,
    val sender: Sender,
    /** Unix-время, секунды. */
    val date: Int,
    val isOutgoing: Boolean,
    val content: MessagePreview,
    /** Подпись автора поста в канале, если включена. */
    val authorSignature: String,
    /** Просмотры поста в канале; 0 — неизвестно. */
    val viewCount: Int,
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
    content = content.toPreview(),
    authorSignature = authorSignature,
    viewCount = interactionInfo?.viewCount ?: 0,
)
