package io.github.iufb.reach.chats

import org.drinkless.tdlib.TdApi

/** Краткое содержимое сообщения для строки списка чатов. */
sealed interface MessagePreview {
    data class Text(val text: String) : MessagePreview

    /** Всё, кроме текста: название типа («Фото», «Стикер»…) и подпись, если есть. */
    data class Media(val kind: MediaKind, val caption: String = "") : MessagePreview
}

enum class MediaKind { Photo, Video, Document, Audio, VoiceNote, VideoNote, Animation, Sticker, Poll, Location, Contact, Call, Service, Other }

internal fun TdApi.MessageContent.toPreview(): MessagePreview = when (this) {
    is TdApi.MessageText -> MessagePreview.Text(text.text)
    is TdApi.MessagePhoto -> MessagePreview.Media(MediaKind.Photo, caption.text)
    is TdApi.MessageVideo -> MessagePreview.Media(MediaKind.Video, caption.text)
    is TdApi.MessageDocument -> MessagePreview.Media(MediaKind.Document, caption.text)
    is TdApi.MessageAudio -> MessagePreview.Media(MediaKind.Audio, caption.text)
    is TdApi.MessageVoiceNote -> MessagePreview.Media(MediaKind.VoiceNote, caption.text)
    is TdApi.MessageVideoNote -> MessagePreview.Media(MediaKind.VideoNote)
    is TdApi.MessageAnimation -> MessagePreview.Media(MediaKind.Animation, caption.text)
    is TdApi.MessageSticker -> MessagePreview.Media(MediaKind.Sticker, sticker.emoji)
    is TdApi.MessagePoll -> MessagePreview.Media(MediaKind.Poll, poll.question.text)
    is TdApi.MessageLocation, is TdApi.MessageVenue -> MessagePreview.Media(MediaKind.Location)
    is TdApi.MessageContact -> MessagePreview.Media(MediaKind.Contact)
    is TdApi.MessageCall -> MessagePreview.Media(MediaKind.Call)
    else -> MessagePreview.Media(MediaKind.Other)
}
