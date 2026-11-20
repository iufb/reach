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
    /** Время последней правки, unix-секунды; 0 — не редактировалось. */
    val editDate: Int = 0,
    /** Автор оригинала, если сообщение переслано. */
    val forwardedFrom: MessageAuthor? = null,
    /** Общий id сообщений одного альбома; 0 — не альбом. */
    val mediaAlbumId: Long = 0,
    val reactions: List<Reaction> = emptyList(),
    val isPinned: Boolean = false,
)

/** Тип реакции. Кастомные эмодзи и платные (звёзды) пока рисуются заглушкой. */
sealed interface ReactionKey {
    data class Emoji(val emoji: String) : ReactionKey
    data class Custom(val customEmojiId: Long) : ReactionKey
    data object Paid : ReactionKey
}

/** Реакция под сообщением: сколько раз поставлена и стоит ли среди них своя. */
data class Reaction(val key: ReactionKey, val count: Int, val isChosen: Boolean)

internal fun TdApi.ReactionType.toKey(): ReactionKey = when (this) {
    is TdApi.ReactionTypeEmoji -> ReactionKey.Emoji(emoji)
    is TdApi.ReactionTypeCustomEmoji -> ReactionKey.Custom(customEmojiId)
    else -> ReactionKey.Paid
}

internal fun TdApi.MessageInteractionInfo?.toReactions(): List<Reaction> =
    this?.reactions?.reactions?.map { Reaction(it.type.toKey(), it.totalCount, it.isChosen) }.orEmpty()

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
    editDate = editDate,
    forwardedFrom = forwardInfo?.origin?.toAuthor(),
    mediaAlbumId = mediaAlbumId,
    reactions = interactionInfo.toReactions(),
    isPinned = isPinned,
)

/** Участник группы в подсказках упоминаний. */
data class MentionCandidate(
    val userId: Long,
    val name: String,
    /** Без «@»; пусто — упоминаем по имени. */
    val username: String,
    val photo: io.github.iufb.reach.files.TdFile?,
    val accentColorId: Int,
)

/** Скачанный файл, который надо открыть во внешнем приложении. */
data class OpenFile(val path: String, val mimeType: String)

/** Данные для подписи в шапке чата. */
data class HeaderInfo(
    val activities: Map<Sender, io.github.iufb.reach.presence.ActivityKind> = emptyMap(),
    /** Статус собеседника личного чата. */
    val userStatus: io.github.iufb.reach.users.UserStatus? = null,
    /** Участники группы или подписчики канала; 0 — неизвестно. */
    val memberCount: Int = 0,
    /** В сети сейчас (только для открытой группы). */
    val onlineCount: Int = 0,
)

/** Плашка закреплённого: показанное сообщение, его номер (0 — самое новое) и сколько всего. */
data class PinnedBanner(val message: Message, val index: Int, val count: Int)
