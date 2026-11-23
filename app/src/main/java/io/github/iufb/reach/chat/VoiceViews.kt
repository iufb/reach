package io.github.iufb.reach.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.VoiceInfo
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.rememberAutoDownload
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButtonDefaults

/**
 * Голосовое в пузыре: ▶/⏸, waveform (проигранная часть закрашена, тап/перетаскивание — перемотка),
 * время. Непрослушанное отмечено точкой. Расшифровка, если TDLib её уже знает, — под waveform.
 */
@Composable
internal fun VoiceNoteView(
    voice: VoiceInfo,
    isListened: Boolean,
    /** Состояние плеера, если играет именно это сообщение; иначе `null`. */
    playback: AudioState?,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = playback?.isPlaying == true
    // Голосовые маленькие: если правила разрешают — качаем заранее, тогда ▶ стартует мгновенно
    val autoDownload = rememberAutoDownload()
    val rules = currentAutoDownloadRules()
    LaunchedEffect(voice.file, rules) { if (rules.allows(MediaKind.Voice)) autoDownload.preload(voice.file) }
    Column(modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = bubbleStyle().accent,
                    contentColor = bubbleStyle().onAccent,
                ),
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.video_pause else R.string.video_play),
                )
            }
            Column(Modifier.padding(start = 8.dp)) {
                Waveform(
                    levels = voice.waveform,
                    progress = playback?.progress ?: 0f,
                    // Перематывать можно только то, что играет
                    onSeek = if (playback != null) onSeek else null,
                    modifier = Modifier
                        .width(WAVEFORM_WIDTH)
                        .height(28.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val seconds = if (playback != null) playback.positionMs / 1000 else voice.duration.toLong()
                    Text(
                        formatDuration(seconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = bubbleStyle().secondary,
                    )
                    if (!isListened) {
                        Box(Modifier.size(6.dp).background(bubbleStyle().accent, CircleShape))
                    }
                }
            }
        }
        voice.transcript?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

/** Столбики 0..31; до [progress] — основным цветом. [onSeek] `null` — не перематывается. */
@Composable
internal fun Waveform(
    levels: List<Int>,
    progress: Float,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    played: Color = bubbleStyle().accent,
    rest: Color = bubbleStyle().secondary.copy(alpha = 0.4f),
) {
    val seekModifier = if (onSeek == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(onSeek) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    }
    Canvas(modifier.then(seekModifier)) {
        val bars = resample(levels, (size.width / BAR_STEP_DP.dp.toPx()).toInt().coerceAtLeast(1))
        val step = size.width / bars.size
        val barWidth = (step * 0.6f).coerceAtLeast(1f)
        bars.forEachIndexed { i, level ->
            val h = (size.height * (level / 31f)).coerceAtLeast(2.dp.toPx())
            val x = i * step
            drawRoundRect(
                color = if ((i + 0.5f) / bars.size <= progress) played else rest,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Подгоняет число столбиков под ширину: берёт максимум в каждом отрезке. Пустой waveform — тишина. */
private fun resample(levels: List<Int>, count: Int): List<Int> {
    if (levels.isEmpty()) return List(count) { 0 }
    return List(count) { i ->
        val from = i * levels.size / count
        val to = ((i + 1) * levels.size / count).coerceAtLeast(from + 1).coerceAtMost(levels.size)
        levels.subList(from, to).maxOrNull() ?: 0
    }
}

private val WAVEFORM_WIDTH = 180.dp
/** Шаг столбиков, dp. */
private const val BAR_STEP_DP = 4
