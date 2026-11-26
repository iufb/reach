package io.github.iufb.reach.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Форматы дат одного языка. Русский — как в Telegram на русском, время всегда 24-часовое;
 * английский — как в Telegram iOS на английском, 12 или 24 часа по системе ([Dates.use24Hour]).
 */
private class Formats(val locale: Locale, val h24: Boolean) {
    private val ru = locale.language == "ru"
    private val timePattern = if (ru || h24) "HH:mm" else "h:mm a"
    private fun of(ruPattern: String, enPattern: String) = DateTimeFormatter.ofPattern(if (ru) ruPattern else enPattern, locale)
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern(timePattern, locale)
    val date = of("d MMMM yyyy", "MMMM d, yyyy")
    val dateTime = of("d MMMM yyyy, $timePattern", "MMMM d, yyyy 'at' $timePattern")
    val numeric = of("dd.MM.yy", "M/d/yy")
    val dayMonth = of("d MMM", "MMM d")
    val day = of("d MMMM", "MMMM d")
    val wheelDay = of("EE, d MMM", "EEE MMM d")
    val dayYear = of("d MMMM yyyy", "MMMM d, yyyy")
}

/**
 * Единый форматтер дат и времени (#218). Все даты в интерфейсе — только через него: язык — язык
 * интерфейса ([AppLocale], #405), а не системы.
 */
object Dates {
    /** 24-часовое время в английском интерфейсе; задаёт активити по настройке системы. */
    @Volatile var use24Hour: Boolean = true

    @Volatile private var cached: Formats? = null

    private val f: Formats
        get() {
            val locale = AppLocale
            val h24 = use24Hour
            cached?.takeIf { it.locale == locale && it.h24 == h24 }?.let { return it }
            return Formats(locale, h24).also { cached = it }
        }

    fun at(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        Instant.ofEpochSecond(unixSeconds).atZone(zone)

    /** «17:36» / «5:36 PM» — время сообщения. */
    fun time(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(f.time)

    /** «21 ноября 2026» — дата периода статистики. */
    fun date(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(f.date)

    /** «21 ноября 2026, 17:36» — отложенная отправка, срок ссылки. */
    fun dateTime(unixSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String = at(unixSeconds, zone).format(f.dateTime)

    /** «21.11.26» — компактная дата («был(а) 21.11.26 в 17:36»). */
    fun numericDate(moment: ZonedDateTime): String = moment.format(f.numeric)

    fun time(moment: ZonedDateTime): String = moment.format(f.time)

    /** «21 ноября» / «21 ноября 2025» — плашка дня в ленте. */
    /** Строка барабана дат: «пн, 24 нояб.». */
    fun wheelDay(day: LocalDate): String = day.format(f.wheelDay)

    fun day(day: LocalDate, withYear: Boolean): String = day.format(if (withYear) f.dayYear else f.day)

    /** «21 нояб.» — подписи оси графиков, [millis] — миллисекунды. */
    fun chartDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(f.dayMonth)

    /** Время в списке чатов: сегодня — «17:36», на этой неделе — «пт», раньше — «21.11.26». */
    fun chatListTime(
        unixSeconds: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val moment = at(unixSeconds, zone)
        val date = moment.toLocalDate()
        return when {
            date == today -> moment.format(f.time)
            date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, AppLocale)
            else -> moment.format(f.numeric)
        }
    }
}
