package io.github.iufb.reach.chat

import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.toRichText
import io.github.iufb.reach.chats.toBody
import org.drinkless.tdlib.TdApi

sealed interface Sender {
    data class User(val id: Long) : Sender
    data class Chat(val id: Long) : Sender
}

/** Автор исходного сообщения: известный отправитель или скрытое имя (пользователь запретил ссылку на себя). */
sealed interface MessageAuthor {
    data class Known(val sender: Sender) : MessageAuthor
    data class Hidden(val name: String) : MessageAuthor
}

internal fun TdApi.MessageOrigin.toAuthor(): MessageAuthor = when (this) {
    is TdApi.MessageOriginUser -> MessageAuthor.Known(Sender.User(senderUserId))
    is TdApi.MessageOriginChat -> MessageAuthor.Known(Sender.Chat(senderChatId))
    is TdApi.MessageOriginChannel -> MessageAuthor.Known(Sender.Chat(chatId))
    is TdApi.MessageOriginHiddenUser -> MessageAuthor.Hidden(senderName)
    else -> MessageAuthor.Hidden("")
}

/** На какое сообщение отвечает это. */
data class ReplyTo(
    val messageId: Long,
    /** Ответ на сообщение из другого чата: исходное в этой ленте не найти. */
    val isExternal: Boolean,
    /** Процитированный кусок, если отвечали на часть сообщения. */
    val quote: RichText?,
    /** Содержимое и автор приходят от TDLib только для сообщений из другого чата; иначе ищем по [messageId]. */
    val content: MessageBody?,
    val origin: MessageAuthor?,
)

/** [ownChatId] — чат отвечающего сообщения. */
internal fun TdApi.MessageReplyTo.toReplyTo(ownChatId: Long): ReplyTo? = when (this) {
    is TdApi.MessageReplyToMessage -> ReplyTo(
        messageId = messageId,
        // chatId = 0 — исходное недоступно; тогда TDLib тоже присылает content
        isExternal = chatId != ownChatId,
        quote = quote?.text?.toRichText()?.takeUnless { it.isBlank() },
        content = content?.toBody(),
        origin = origin?.toAuthor(),
    )
    // Ответ на историю (story) — пока не показываем
    else -> null
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
    val replyTo: ReplyTo? = null,
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
    replyTo = replyTo?.toReplyTo(chatId),
)
