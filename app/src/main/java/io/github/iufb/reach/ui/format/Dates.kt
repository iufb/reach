package io.github.iufb.reach.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Язык интерфейса. Он же — язык приложения для системы ([io.github.iufb.reach.ReachApp], #218). */
const val APP_LANGUAGE = "ru"

/** Локаль дат и чисел: всегда язык интерфейса, а не системы (#218). */
val AppLocale: Locale = Locale.forLanguageTag(APP_LANGUAGE)

/**
 * Единый форматтер дат и времени (#218). Все даты в интерфейсе — только через него: язык и вид
 * не зависят от системной локали. Время везде 24-часовое, как в Telegram на русском.
 */
object Dates {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", AppLocale)
    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", AppLocale)
    private val DATE_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", AppLocale)
    private val NUMERIC = DateTimeFormatter.ofPattern("dd.MM.yy", AppLocale)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", AppLocale)
    private val DAY = DateTimeFormatter.ofPattern("d MMMM", AppLocale)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", AppLocale)

    fun at(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        Instant.ofEpochSecond(unixSeconds).atZone(zone)

    /** «17:36» — время сообщения. */
    fun time(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(TIME)

    /** «21 ноября 2026» — дата периода статистики. */
    fun date(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(DATE)

    /** «21 ноября 2026, 17:36» — отложенная отправка, срок ссылки. */
    fun dateTime(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(DATE_TIME)

    /** «21.11.26» — компактная дата («был(а) 21.11.26 в 17:36»). */
    fun numericDate(moment: ZonedDateTime): String = moment.format(NUMERIC)

    fun time(moment: ZonedDateTime): String = moment.format(TIME)

    /** «21 ноября» / «21 ноября 2025» — плашка дня в ленте. */
    fun day(day: LocalDate, withYear: Boolean): String = day.format(if (withYear) DAY_YEAR else DAY)

    /** «21 нояб.» — подписи оси графиков, [millis] — миллисекунды. */
    fun chartDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DAY_MONTH)

    /** Время в списке чатов: сегодня — «17:36», на этой неделе — «пт», раньше — «21.11.26». */
    fun chatListTime(
        unixSeconds: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val moment = at(unixSeconds, zone)
        val date = moment.toLocalDate()
        return when {
            date == today -> moment.format(TIME)
            date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, AppLocale)
            else -> moment.format(NUMERIC)
        }
    }
}
