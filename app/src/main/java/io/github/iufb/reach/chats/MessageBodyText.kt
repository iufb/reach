package io.github.iufb.reach.chats

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
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
    ServiceKind.AutoDeleteSet -> R.string.service_auto_delete
    ServiceKind.TopicCreated -> R.string.service_topic_created
    ServiceKind.TopicRenamed -> R.string.service_topic_renamed
    ServiceKind.TopicClosed -> R.string.service_topic_closed
    ServiceKind.TopicReopened -> R.string.service_topic_reopened
    ServiceKind.Other -> R.string.service_other
}

/** Текст служебного сообщения в ленте: с подробностями, где они есть. */
@Composable
fun serviceText(body: MessageBody.Service): String = when (body.kind) {
    ServiceKind.AutoDeleteSet -> if (body.value > 0) {
        stringResource(R.string.service_auto_delete_on, autoDeleteLabel(body.value))
    } else {
        stringResource(R.string.service_auto_delete_off)
    }
    ServiceKind.TopicCreated -> stringResource(R.string.service_topic_created_named, body.text)
    ServiceKind.TopicRenamed -> stringResource(R.string.service_topic_renamed_named, body.text)
    else -> serviceKindText(body.kind)
}

/** «1 день», «1 неделя», «1 месяц» или точное значение. */
@Composable
fun autoDeleteLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.auto_delete_off)
    seconds % MONTH == 0 -> pluralStringResource(R.plurals.months, seconds / MONTH, seconds / MONTH)
    seconds % WEEK == 0 -> pluralStringResource(R.plurals.weeks, seconds / WEEK, seconds / WEEK)
    seconds % DAY == 0 -> pluralStringResource(R.plurals.days, seconds / DAY, seconds / DAY)
    seconds % HOUR == 0 -> pluralStringResource(R.plurals.hours, seconds / HOUR, seconds / HOUR)
    else -> pluralStringResource(R.plurals.minutes, seconds / 60, seconds / 60)
}

private const val HOUR = 3600
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
/** Telegram считает месяц за 31 день. */
private const val MONTH = 31 * DAY

/** Одна строка для превью в списке чатов. */
@Composable
fun bodySummary(body: MessageBody): String = summarize(body) { stringResource(it) }

@PublishedApi
internal fun joinParts(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(", ")

/** То же превью вне Compose (уведомления): строки берутся через [string]. */
inline fun summarize(body: MessageBody, string: (Int) -> String): String {
    return when (body) {
        is MessageBody.Text -> body.text.masked()
        is MessageBody.Unsupported -> joinParts(string(body.kind.labelRes()), body.caption.masked())
        is MessageBody.Document -> joinParts(body.document.fileName.ifBlank { string(ContentKind.Document.labelRes()) }, body.caption.masked())
        is MessageBody.Photo -> joinParts(string(ContentKind.Photo.labelRes()), body.caption.masked())
        is MessageBody.Video -> joinParts(string(ContentKind.Video.labelRes()), body.caption.masked())
        is MessageBody.Animation -> joinParts(string(ContentKind.Animation.labelRes()), body.caption.masked())
        is MessageBody.Audio -> joinParts(
            listOf(body.audio.performer, body.audio.displayTitle).filter { it.isNotBlank() }.joinToString(" — ")
                .ifBlank { string(ContentKind.Audio.labelRes()) },
            body.caption.masked(),
        )
        is MessageBody.Voice -> joinParts(string(ContentKind.VoiceNote.labelRes()), body.caption.masked())
        is MessageBody.AnimatedEmoji -> body.emoji
        is MessageBody.Poll -> joinParts(
            string(if (body.poll.quiz != null) R.string.content_quiz else ContentKind.Poll.labelRes()),
            body.poll.question.masked(),
        )
        is MessageBody.Sticker -> joinParts(body.sticker.emoji, string(ContentKind.Sticker.labelRes()))
        is MessageBody.Service -> string(body.kind.labelRes())
    }
}
