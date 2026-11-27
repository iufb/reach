package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Содержимое сообщения в виде, понятном UI (PRODUCT.md §3, принцип 4).
 *
 * Клиент пока рисует только текст. Всё остальное — заглушка с понятным названием типа,
 * а не пустота и не краш: любой `MessageContent`, даже появившийся в новой TDLib, попадает
 * в одну из веток.
 */
sealed interface MessageBody {
    /** [preview] — карточка ссылки из текста, если сервер её собрал. */
    data class Text(val text: RichText, val preview: LinkPreview? = null) : MessageBody

    data class Photo(val photo: PhotoInfo, val caption: RichText, val hasSpoiler: Boolean = false) : MessageBody

    data class Video(val video: VideoInfo, val caption: RichText, val hasSpoiler: Boolean = false) : MessageBody

    /** GIF — в Telegram это беззвучный MP4; модель та же, что у видео. */
    data class Animation(val animation: VideoInfo, val caption: RichText, val hasSpoiler: Boolean = false) : MessageBody

    /** Голосовое; [isListened] — собеседник (или мы) уже прослушал. */
    data class Voice(val voice: VoiceInfo, val caption: RichText, val isListened: Boolean) : MessageBody

    /** Видеокружок; [isViewed] — уже просмотрен (нами или собеседником). */
    data class VideoNote(val note: VideoNoteInfo, val isViewed: Boolean) : MessageBody

    /** Музыка (`MessageAudio`). */
    data class Audio(val audio: AudioInfo, val caption: RichText) : MessageBody

    /**
     * Сообщение из одного эмодзи с анимацией: [animation] — TGS-стикер (null — анимации нет,
     * рисуем крупный символ), [emoji] — сам символ.
     */
    data class AnimatedEmoji(val emoji: String, val animation: TdFile?) : MessageBody

    /** Стикер — без пузыря и без подписи. */
    data class Sticker(val sticker: StickerInfo) : MessageBody

    data class Document(val document: DocumentInfo, val caption: RichText) : MessageBody

    /** Сообщение, которое клиент ещё не умеет показать. [caption] — подпись или эмодзи, если есть. */
    /** Звонок в истории (#412): итог и длительность, секунды; направление — `Message.isOutgoing`. */
    data class Call(val isVideo: Boolean, val outcome: CallOutcome, val duration: Int) : MessageBody

    data class Unsupported(val kind: ContentKind, val caption: RichText = RichText.Empty) : MessageBody

    /** Служебное событие чата: вступление, смена названия, закреп… */
    data class Poll(val poll: PollInfo) : MessageBody

    /** [value] — число для части видов (секунды автоудаления), [text] — строка (название топика). */
    data class Service(val kind: ServiceKind, val value: Int = 0, val text: String = "") : MessageBody
}

/** Чем кончился звонок: из `messageCall.discardReason`. */
enum class CallOutcome { Ended, Missed, Declined }

enum class ContentKind {
    Photo, Video, Document, Audio, VoiceNote, VideoNote, Animation, Sticker, Poll, Checklist,
    Location, LiveLocation, Contact, Call, GroupCall, Dice, Game, Story, Invoice, Gift, Giveaway,
    PaidMedia, Expired, Unknown,
}

enum class ServiceKind {
    ChatCreated, ChannelCreated, MembersAdded, MemberJoined, MemberLeft, TitleChanged, PhotoChanged, PhotoDeleted,
    MessagePinned, ScreenshotTaken, ContactRegistered, VideoChat, Payment, AutoDeleteSet,
    TopicCreated, TopicRenamed, TopicClosed, TopicReopened, Other,
}

/**
 * Содержимое с учётом самого сообщения: создание канала TDLib присылает тем же
 * `MessageSupergroupChatCreate`, что и у супергруппы, отличает только `isChannelPost` (#221).
 */
internal fun TdApi.Message.body(): MessageBody {
    val body = content.toBody()
    return if (isChannelPost && body is MessageBody.Service && body.kind == ServiceKind.ChatCreated) body.copy(kind = ServiceKind.ChannelCreated) else body
}

internal fun TdApi.MessageContent.toBody(): MessageBody = when (this) {
    is TdApi.MessageText -> MessageBody.Text(text.toRichText(), linkPreview?.toModel())
    is TdApi.MessageAnimatedEmoji -> MessageBody.AnimatedEmoji(
        emoji,
        animatedEmoji.sticker?.takeIf { it.format is TdApi.StickerFormatTgs }?.sticker?.toTdFile(),
    )

    is TdApi.MessagePhoto -> MessageBody.Photo(photo.toInfo(), caption.toRichText(), hasSpoiler)
    is TdApi.MessageVideo -> MessageBody.Video(toInfo(), caption.toRichText(), hasSpoiler)
    is TdApi.MessageDocument -> MessageBody.Document(document.toInfo(), caption.toRichText())
    is TdApi.MessageAudio -> MessageBody.Audio(audio.toAudioInfo(), caption.toRichText())
    is TdApi.MessageVoiceNote -> MessageBody.Voice(voiceNote.toInfo(), caption.toRichText(), isListened)
    is TdApi.MessageAnimation -> MessageBody.Animation(toInfo(), caption.toRichText(), hasSpoiler)
    is TdApi.MessagePaidMedia -> unsupported(ContentKind.PaidMedia, caption)
    // Самоуничтожающиеся кружки — заглушкой
    is TdApi.MessageVideoNote -> if (isSecret) MessageBody.Unsupported(ContentKind.VideoNote) else MessageBody.VideoNote(videoNote.toInfo(), isViewed)
    is TdApi.MessageSticker -> MessageBody.Sticker(sticker.toInfo())
    is TdApi.MessagePoll -> MessageBody.Poll(poll.toInfo())
    is TdApi.MessageChecklist -> unsupported(ContentKind.Checklist, list.title)
    is TdApi.MessageLocation, is TdApi.MessageVenue -> MessageBody.Unsupported(ContentKind.Location)
    is TdApi.MessageLiveLocation -> MessageBody.Unsupported(ContentKind.LiveLocation)
    is TdApi.MessageContact -> unsupported(ContentKind.Contact, "${contact.firstName} ${contact.lastName}".trim())
    is TdApi.MessageCall -> MessageBody.Call(
        isVideo,
        when (discardReason) {
            is TdApi.CallDiscardReasonMissed -> CallOutcome.Missed
            is TdApi.CallDiscardReasonDeclined -> CallOutcome.Declined
            else -> CallOutcome.Ended
        },
        duration,
    )
    is TdApi.MessageGroupCall -> MessageBody.Unsupported(ContentKind.GroupCall)
    is TdApi.MessageDice -> unsupported(ContentKind.Dice, emoji)
    is TdApi.MessageGame -> unsupported(ContentKind.Game, game.title)
    is TdApi.MessageStory -> MessageBody.Unsupported(ContentKind.Story)
    is TdApi.MessageInvoice -> MessageBody.Unsupported(ContentKind.Invoice)
    is TdApi.MessageGift, is TdApi.MessageUpgradedGift, is TdApi.MessageGiftedPremium,
    is TdApi.MessagePremiumGiftCode, is TdApi.MessageGiftedStars -> MessageBody.Unsupported(ContentKind.Gift)
    is TdApi.MessageGiveaway, is TdApi.MessageGiveawayCreated, is TdApi.MessageGiveawayCompleted,
    is TdApi.MessageGiveawayWinners -> MessageBody.Unsupported(ContentKind.Giveaway)
    is TdApi.MessageExpiredPhoto, is TdApi.MessageExpiredVideo, is TdApi.MessageExpiredVideoNote,
    is TdApi.MessageExpiredVoiceNote -> MessageBody.Unsupported(ContentKind.Expired)

    is TdApi.MessageForumTopicCreated -> MessageBody.Service(ServiceKind.TopicCreated, text = name)
    // Сменился только значок — имени в апдейте нет
    is TdApi.MessageForumTopicEdited -> if (name.isNotEmpty()) MessageBody.Service(ServiceKind.TopicRenamed, text = name) else service(ServiceKind.Other)
    is TdApi.MessageForumTopicIsClosedToggled -> service(if (isClosed) ServiceKind.TopicClosed else ServiceKind.TopicReopened)
    is TdApi.MessageChatSetMessageAutoDeleteTime -> MessageBody.Service(ServiceKind.AutoDeleteSet, value = messageAutoDeleteTime)
    is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> service(ServiceKind.ChatCreated)
    is TdApi.MessageChatAddMembers -> service(ServiceKind.MembersAdded)
    is TdApi.MessageChatJoinByLink, is TdApi.MessageChatJoinByRequest -> service(ServiceKind.MemberJoined)
    is TdApi.MessageChatDeleteMember -> service(ServiceKind.MemberLeft)
    is TdApi.MessageChatChangeTitle -> service(ServiceKind.TitleChanged)
    is TdApi.MessageChatChangePhoto -> service(ServiceKind.PhotoChanged)
    is TdApi.MessageChatDeletePhoto -> service(ServiceKind.PhotoDeleted)
    is TdApi.MessagePinMessage -> service(ServiceKind.MessagePinned)
    is TdApi.MessageScreenshotTaken -> service(ServiceKind.ScreenshotTaken)
    is TdApi.MessageContactRegistered -> service(ServiceKind.ContactRegistered)
    is TdApi.MessageVideoChatScheduled, is TdApi.MessageVideoChatStarted, is TdApi.MessageVideoChatEnded,
    is TdApi.MessageInviteVideoChatParticipants -> service(ServiceKind.VideoChat)
    is TdApi.MessagePaymentSuccessful, is TdApi.MessagePaymentSuccessfulBot,
    is TdApi.MessagePaymentRefunded -> service(ServiceKind.Payment)
    is TdApi.MessageChatUpgradeTo, is TdApi.MessageChatUpgradeFrom, is TdApi.MessageChatSetTheme,
    is TdApi.MessageChatSetBackground, is TdApi.MessageChatBoost,
    is TdApi.MessageCustomServiceAction,
    is TdApi.MessageGameScore, is TdApi.MessageBotWriteAccessAllowed, is TdApi.MessageProximityAlertTriggered,
    is TdApi.MessageChatOwnerLeft, is TdApi.MessageChatOwnerChanged -> service(ServiceKind.Other)

    // MessageUnsupported (TDLib сама не знает тип) и всё, что появится позже
    else -> MessageBody.Unsupported(ContentKind.Unknown)
}

private fun unsupported(kind: ContentKind, caption: TdApi.FormattedText?) =
    MessageBody.Unsupported(kind, caption.toRichText())

private fun unsupported(kind: ContentKind, caption: String) = MessageBody.Unsupported(kind, RichText(caption))

private fun service(kind: ServiceKind) = MessageBody.Service(kind)
