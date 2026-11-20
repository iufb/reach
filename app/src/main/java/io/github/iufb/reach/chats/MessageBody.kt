package io.github.iufb.reach.chats

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

    data class Photo(val photo: PhotoInfo, val caption: RichText) : MessageBody

    data class Video(val video: VideoInfo, val caption: RichText) : MessageBody

    /** GIF — в Telegram это беззвучный MP4; модель та же, что у видео. */
    data class Animation(val animation: VideoInfo, val caption: RichText) : MessageBody

    /** Стикер — без пузыря и без подписи. */
    data class Sticker(val sticker: StickerInfo) : MessageBody

    data class Document(val document: DocumentInfo, val caption: RichText) : MessageBody

    /** Сообщение, которое клиент ещё не умеет показать. [caption] — подпись или эмодзи, если есть. */
    data class Unsupported(val kind: ContentKind, val caption: RichText = RichText.Empty) : MessageBody

    /** Служебное событие чата: вступление, смена названия, закреп… */
    data class Service(val kind: ServiceKind) : MessageBody
}

enum class ContentKind {
    Photo, Video, Document, Audio, VoiceNote, VideoNote, Animation, Sticker, Poll, Checklist,
    Location, LiveLocation, Contact, Call, GroupCall, Dice, Game, Story, Invoice, Gift, Giveaway,
    PaidMedia, Expired, Unknown,
}

enum class ServiceKind {
    ChatCreated, MembersAdded, MemberJoined, MemberLeft, TitleChanged, PhotoChanged, PhotoDeleted,
    MessagePinned, ScreenshotTaken, ContactRegistered, VideoChat, Payment, Other,
}

internal fun TdApi.MessageContent.toBody(): MessageBody = when (this) {
    is TdApi.MessageText -> MessageBody.Text(text.toRichText(), linkPreview?.toModel())
    // Одиночный анимированный эмодзи — это текст
    is TdApi.MessageAnimatedEmoji -> MessageBody.Text(RichText(emoji))

    is TdApi.MessagePhoto -> MessageBody.Photo(photo.toInfo(), caption.toRichText())
    is TdApi.MessageVideo -> MessageBody.Video(toInfo(), caption.toRichText())
    is TdApi.MessageDocument -> MessageBody.Document(document.toInfo(), caption.toRichText())
    // Плеер — в v0.4, пока аудио — файл
    is TdApi.MessageAudio -> MessageBody.Document(audio.toInfo(), caption.toRichText())
    is TdApi.MessageVoiceNote -> unsupported(ContentKind.VoiceNote, caption)
    is TdApi.MessageAnimation -> MessageBody.Animation(toInfo(), caption.toRichText())
    is TdApi.MessagePaidMedia -> unsupported(ContentKind.PaidMedia, caption)
    is TdApi.MessageVideoNote -> MessageBody.Unsupported(ContentKind.VideoNote)
    is TdApi.MessageSticker -> MessageBody.Sticker(sticker.toInfo())
    is TdApi.MessagePoll -> unsupported(ContentKind.Poll, poll.question)
    is TdApi.MessageChecklist -> unsupported(ContentKind.Checklist, list.title)
    is TdApi.MessageLocation, is TdApi.MessageVenue -> MessageBody.Unsupported(ContentKind.Location)
    is TdApi.MessageLiveLocation -> MessageBody.Unsupported(ContentKind.LiveLocation)
    is TdApi.MessageContact -> unsupported(ContentKind.Contact, "${contact.firstName} ${contact.lastName}".trim())
    is TdApi.MessageCall -> MessageBody.Unsupported(ContentKind.Call)
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
    is TdApi.MessageChatSetBackground, is TdApi.MessageChatSetMessageAutoDeleteTime, is TdApi.MessageChatBoost,
    is TdApi.MessageForumTopicCreated, is TdApi.MessageForumTopicEdited, is TdApi.MessageCustomServiceAction,
    is TdApi.MessageGameScore, is TdApi.MessageBotWriteAccessAllowed, is TdApi.MessageProximityAlertTriggered,
    is TdApi.MessageChatOwnerLeft, is TdApi.MessageChatOwnerChanged -> service(ServiceKind.Other)

    // MessageUnsupported (TDLib сама не знает тип) и всё, что появится позже
    else -> MessageBody.Unsupported(ContentKind.Unknown)
}

private fun unsupported(kind: ContentKind, caption: TdApi.FormattedText?) =
    MessageBody.Unsupported(kind, caption.toRichText())

private fun unsupported(kind: ContentKind, caption: String) = MessageBody.Unsupported(kind, RichText(caption))

private fun service(kind: ServiceKind) = MessageBody.Service(kind)
