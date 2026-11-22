package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.AccentPillButton
import io.github.iufb.reach.ui.components.WheelPicker
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * «Отправить позже» как в Telegram iOS: шторка снизу с тремя барабанами — день («Сегодня», «Завтра»,
 * дальше датами на год вперёд), часы, минуты — и кнопкой «Отправить сегодня в 18:00».
 * [initial] — уже назначенное время (unix-секунды) при переносе; иначе — через час.
 */
@Composable
internal fun ScheduleDialog(initial: Int?, onPick: (date: Int) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    val start = remember(initial) {
        (initial?.toLong() ?: (System.currentTimeMillis() / 1000 + DEFAULT_DELAY_SEC)).let { Instant.ofEpochSecond(it).atZone(zone) }
    }
    var dayIndex by rememberSaveable { mutableIntStateOf((start.toLocalDate().toEpochDay() - today.toEpochDay()).toInt().coerceIn(0, MAX_DAYS_AHEAD)) }
    var hour by rememberSaveable { mutableIntStateOf(start.hour) }
    var minute by rememberSaveable { mutableIntStateOf(start.minute) }
    val todayLabel = stringResource(R.string.schedule_today)
    val tomorrowLabel = stringResource(R.string.schedule_tomorrow)
    val days = remember(today) {
        (0..MAX_DAYS_AHEAD).map { offset ->
            when (offset) {
                0 -> todayLabel
                1 -> tomorrowLabel
                else -> Dates.wheelDay(today.plusDays(offset.toLong()))
            }
        }
    }
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }

    val day = today.plusDays(dayIndex.toLong())
    val picked = day.atTime(hour, minute).atZone(zone).toEpochSecond()
    // Telegram не принимает время в прошлом; небольшой запас на отправку запроса
    val valid = picked > System.currentTimeMillis() / 1000 + MIN_DELAY_SEC
    val dayText = when (dayIndex) {
        0 -> stringResource(R.string.schedule_send_today)
        1 -> stringResource(R.string.schedule_send_tomorrow)
        else -> Dates.day(day, withYear = day.year != today.year)
    }
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Тап по самой шторке её не закрывает
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .background(colors.cell, RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.schedule_title),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.label,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WheelPicker(days, dayIndex, { dayIndex = it }, Modifier.weight(2f))
                    WheelPicker(hours, hour, { hour = it }, Modifier.weight(1f))
                    WheelPicker(minutes, minute, { minute = it }, Modifier.weight(1f))
                }
                AccentPillButton(
                    if (valid) stringResource(R.string.schedule_send_at, dayText, "%02d:%02d".format(hour, minute))
                    else stringResource(R.string.schedule_in_past),
                    onClick = { onPick(picked.toInt()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = valid,
                )
            }
        }
    }
}

private val SHEET_CORNER = 26.dp
private const val DEFAULT_DELAY_SEC = 3600L
private const val MIN_DELAY_SEC = 30L
private const val MAX_DAYS_AHEAD = 365
