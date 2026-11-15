package io.github.iufb.reach.chats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Время последнего сообщения в списке: сегодня — часы, на этой неделе — день недели, иначе дата. */
internal fun formatChatTime(
    unixSeconds: Int,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    locale: Locale = Locale.getDefault(),
): String {
    val dateTime = Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}
