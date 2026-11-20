package io.github.iufb.reach.chats

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R

/** Название типа для заглушки и превью: «Фото», «Стикер», «Опрос»… */
@Composable
fun contentKindText(kind: ContentKind): String = stringResource(kind.labelRes())

@StringRes
fun ContentKind.labelRes(): Int = when (this) {
    ContentKind.Photo -> R.string.content_photo
    ContentKind.Video -> R.string.content_video
    ContentKind.Document -> R.string.content_document
    ContentKind.Audio -> R.string.content_audio
    ContentKind.VoiceNote -> R.string.content_voice_note
    ContentKind.VideoNote -> R.string.content_video_note
    ContentKind.Animation -> R.string.content_animation
    ContentKind.Sticker -> R.string.content_sticker
    ContentKind.Poll -> R.string.content_poll
    ContentKind.Checklist -> R.string.content_checklist
    ContentKind.Location -> R.string.content_location
    ContentKind.LiveLocation -> R.string.content_live_location
    ContentKind.Contact -> R.string.content_contact
    ContentKind.Call -> R.string.content_call
    ContentKind.GroupCall -> R.string.content_group_call
    ContentKind.Dice -> R.string.content_dice
    ContentKind.Game -> R.string.content_game
    ContentKind.Story -> R.string.content_story
    ContentKind.Invoice -> R.string.content_invoice
    ContentKind.Gift -> R.string.content_gift
    ContentKind.Giveaway -> R.string.content_giveaway
    ContentKind.PaidMedia -> R.string.content_paid_media
    ContentKind.Expired -> R.string.content_expired
    ContentKind.Unknown -> R.string.content_unknown
}

@Composable
fun serviceKindText(kind: ServiceKind): String = stringResource(kind.labelRes())

@StringRes
fun ServiceKind.labelRes(): Int = when (this) {
    ServiceKind.ChatCreated -> R.string.service_chat_created
    ServiceKind.MembersAdded -> R.string.service_members_added
    ServiceKind.MemberJoined -> R.string.service_member_joined
    ServiceKind.MemberLeft -> R.string.service_member_left
    ServiceKind.TitleChanged -> R.string.service_title_changed
    ServiceKind.PhotoChanged -> R.string.service_photo_changed
    ServiceKind.PhotoDeleted -> R.string.service_photo_deleted
    ServiceKind.MessagePinned -> R.string.service_message_pinned
    ServiceKind.ScreenshotTaken -> R.string.service_screenshot
    ServiceKind.ContactRegistered -> R.string.service_contact_registered
    ServiceKind.VideoChat -> R.string.service_video_chat
    ServiceKind.Payment -> R.string.service_payment
    ServiceKind.Other -> R.string.service_other
}

/** Одна строка для превью в списке чатов. */
@Composable
fun bodySummary(body: MessageBody): String = summarize(body) { stringResource(it) }

@PublishedApi
internal fun joinParts(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(", ")

/** То же превью вне Compose (уведомления): строки берутся через [string]. */
inline fun summarize(body: MessageBody, string: (Int) -> String): String {
    return when (body) {
        is MessageBody.Text -> body.text.text
        is MessageBody.Unsupported -> joinParts(string(body.kind.labelRes()), body.caption.text)
        is MessageBody.Document -> joinParts(body.document.fileName.ifBlank { string(ContentKind.Document.labelRes()) }, body.caption.text)
        is MessageBody.Photo -> joinParts(string(ContentKind.Photo.labelRes()), body.caption.text)
        is MessageBody.Video -> joinParts(string(ContentKind.Video.labelRes()), body.caption.text)
        is MessageBody.Animation -> joinParts(string(ContentKind.Animation.labelRes()), body.caption.text)
        is MessageBody.Voice -> joinParts(string(ContentKind.VoiceNote.labelRes()), body.caption.text)
        is MessageBody.Sticker -> joinParts(body.sticker.emoji, string(ContentKind.Sticker.labelRes()))
        is MessageBody.Service -> string(body.kind.labelRes())
    }
}
