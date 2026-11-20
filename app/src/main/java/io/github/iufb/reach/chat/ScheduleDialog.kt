package io.github.iufb.reach.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Выбор времени отложенной отправки: сначала дата, потом время.
 * [initial] — уже назначенное время (unix-секунды) при переносе; иначе — через час.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDialog(initial: Int?, onPick: (date: Int) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = remember(initial) {
        (initial?.toLong() ?: (System.currentTimeMillis() / 1000 + DEFAULT_DELAY_SEC)).let { Instant.ofEpochSecond(it).atZone(zone) }
    }
    // Дата выбрана — epochDay; null — ещё на шаге выбора даты
    var day by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickedDay = day
    if (pickedDay == null) {
        val today = LocalDate.now(zone)
        val first = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val last = today.plusDays(MAX_DAYS_AHEAD).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            // DatePicker считает в UTC-полночах
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in first..last
                override fun isSelectableYear(year: Int) = year in today.year..today.plusDays(MAX_DAYS_AHEAD).year
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() } },
                ) { Text(stringResource(R.string.schedule_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state)
        }
    } else {
        val time = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
        val picked = LocalDate.ofEpochDay(pickedDay).atTime(time.hour, time.minute).atZone(zone).toEpochSecond()
        // Telegram не принимает время в прошлом; небольшой запас на отправку запроса
        val valid = picked > System.currentTimeMillis() / 1000 + MIN_DELAY_SEC
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.schedule_time_title)) },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(enabled = valid, onClick = { onPick(picked.toInt()) }) { Text(stringResource(R.string.schedule_confirm)) }
            },
            dismissButton = { TextButton(onClick = { day = null }) { Text(stringResource(R.string.back)) } },
        )
    }
}

private const val DEFAULT_DELAY_SEC = 3600L
private const val MIN_DELAY_SEC = 30L
private const val MAX_DAYS_AHEAD = 365L
