package io.github.iufb.reach.presence

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.users.UserStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** «в сети», «был(а) в 14:05», «был(а) недавно»…; `null` — статус неизвестен. */
@Composable
fun userStatusText(status: UserStatus, nowSeconds: Long = System.currentTimeMillis() / 1000): String? = when (status) {
    is UserStatus.Online -> stringResource(R.string.status_online)
    is UserStatus.Offline -> lastSeenText(status.wasOnline.toLong(), nowSeconds)
    UserStatus.Recently -> stringResource(R.string.status_recently)
    UserStatus.LastWeek -> stringResource(R.string.status_last_week)
    UserStatus.LastMonth -> stringResource(R.string.status_last_month)
    UserStatus.Unknown -> null
}

@Composable
private fun lastSeenText(wasOnline: Long, now: Long): String {
    if (wasOnline <= 0) return stringResource(R.string.status_long_ago)
    if (now - wasOnline < JUST_NOW_SECONDS) return stringResource(R.string.status_just_now)
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochSecond(wasOnline).atZone(zone)
    val today = LocalDate.now(zone)
    val time = Dates.time(moment)
    return when (moment.toLocalDate()) {
        today -> stringResource(R.string.status_today, time)
        today.minusDays(1) -> stringResource(R.string.status_yesterday, time)
        else -> stringResource(R.string.status_date, Dates.numericDate(moment))
    }
}

/** Текст одного действия без имени: «печатает…», «отправляет фото…». */
@Composable
fun activityKindText(kind: ActivityKind): String = stringResource(
    when (kind) {
        ActivityKind.Typing -> R.string.activity_typing
        ActivityKind.RecordingVoice -> R.string.activity_recording_voice
        ActivityKind.RecordingVideo -> R.string.activity_recording_video
        ActivityKind.UploadingPhoto -> R.string.activity_uploading_photo
        ActivityKind.UploadingFile -> R.string.activity_uploading_file
        ActivityKind.ChoosingSticker -> R.string.activity_choosing_sticker
    },
)

/**
 * Строка действий чата. В личке — только действие; в группе — с именами: «Аня печатает…»,
 * «Аня и Боря печатают…», «3 человека печатают…». `null` — никто ничего не делает.
 */
@Composable
fun activityText(activities: Map<Sender, ActivityKind>, withNames: Boolean, name: (Sender) -> String): String? {
    if (activities.isEmpty()) return null
    val entries = activities.entries.toList()
    if (!withNames) return activityKindText(entries.first().value)
    return when (entries.size) {
        1 -> stringResource(R.string.activity_named, name(entries[0].key), activityKindText(entries[0].value))
        2 -> stringResource(R.string.activity_two, name(entries[0].key), name(entries[1].key))
        else -> pluralStringResource(R.plurals.activity_many, entries.size, entries.size)
    }
}

private const val JUST_NOW_SECONDS = 60

