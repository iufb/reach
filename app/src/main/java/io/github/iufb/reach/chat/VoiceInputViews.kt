package io.github.iufb.reach.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.RecorderState
import kotlinx.coroutines.delay

/** Запись голосового в поле ввода. */
internal class VoiceInput(
    val state: RecorderState,
    /** Палец лёг на микрофон. */
    val onStart: () -> Unit,
    /** Свайп вверх: запись без удержания. */
    val onLock: () -> Unit,
    /** Палец отпущен без фиксации — отправить. */
    val onRelease: () -> Unit,
    /** «Стоп» в зафиксированном режиме — предпрослушивание. */
    val onStop: () -> Unit,
    val onSend: () -> Unit,
    /** Свайп влево или «удалить». */
    val onDiscard: () -> Unit,
    val onTogglePreview: () -> Unit,
    /** Кнопка записывает видеокружок, а не голосовое. */
    val videoMode: Boolean = false,
    /** Короткий тап — сменить голосовое ↔ кружок; `null` — кружки недоступны, запись сразу по касанию. */
    val onToggleMode: (() -> Unit)? = null,
)

/**
 * Микрофон или камера: удержание — запись, отпустить — отправить, влево — отмена, вверх — фиксация,
 * короткий тап — смена голосовое ↔ кружок ([VoiceInput.onToggleMode]).
 * Без разрешений (`RECORD_AUDIO`, для кружка ещё `CAMERA`) нажатие только запрашивает их.
 */
@Composable
internal fun MicButton(voice: VoiceInput) {
    val context = LocalContext.current
    val current by rememberUpdatedState(voice)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (current.videoMode && !result.values.all { it }) {
            Toast.makeText(context, R.string.video_note_permission, Toast.LENGTH_LONG).show()
        }
    }
    val description = stringResource(if (voice.videoMode) R.string.video_note_record else R.string.voice_record)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val cancelPx = CANCEL_DISTANCE.toPx()
                val lockPx = LOCK_DISTANCE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val toggle = current.onToggleMode
                    if (toggle != null) {
                        // null — держат дольше порога (запись), true — отпустили раньше (смена режима)
                        val released = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() != null }
                        if (released == true) toggle()
                        if (released != null) return@awaitEachGesture
                    }
                    val needed = if (current.videoMode) {
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                    val missing = needed.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permission.launch(missing.toTypedArray())
                        return@awaitEachGesture
                    }
                    current.onStart()
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            current.onRelease()
                            break
                        }
                        total += change.positionChange()
                        change.consume()
                        if (total.x < -cancelPx) {
                            current.onDiscard()
                            break
                        }
                        if (total.y < -lockPx) {
                            current.onLock()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val recording = voice.state is RecorderState.Recording
        Icon(
            painterResource(if (voice.videoMode) R.drawable.ic_videocam else R.drawable.ic_mic),
            contentDescription = null,
            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Вместо поля ввода, пока идёт запись или открыто предпрослушивание. */
@Composable
internal fun RecordingBar(voice: VoiceInput, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = voice.state) {
            is RecorderState.Recording -> {
                if (state.locked) {
                    IconButton(onClick = voice.onDiscard) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.voice_delete))
                    }
                }
                Box(
                    Modifier
                        .padding(start = if (state.locked) 0.dp else 12.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
                RecordingTimer(state.startedAt)
                LevelBar(state.level, Modifier.weight(1f))
                if (state.locked) {
                    IconButton(onClick = voice.onStop) {
                        Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.voice_stop))
                    }
                } else {
                    Text(
                        stringResource(R.string.voice_slide_to_cancel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        painterResource(R.drawable.ic_lock),
                        contentDescription = stringResource(R.string.voice_lock),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is RecorderState.Recorded -> {
                IconButton(onClick = voice.onDiscard) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.voice_delete))
                }
                IconButton(onClick = voice.onTogglePreview) {
                    val playing = state.previewPlaying
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        stringResource(if (playing) R.string.video_pause else R.string.video_play),
                    )
                }
                Waveform(
                    levels = io.github.iufb.reach.chats.decodeWaveform(state.recording.waveform),
                    progress = 0f,
                    onSeek = null,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                )
                Text(formatDuration(state.recording.durationSec.toLong()), style = MaterialTheme.typography.labelMedium)
            }
            RecorderState.Idle -> Unit
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(TIMER_TICK_MS)
        }
    }
    Text(formatDuration((now - startedAt) / 1000), style = MaterialTheme.typography.titleSmall)
}

/** Полоска громкости микрофона. */
@Composable
private fun LevelBar(level: Float, modifier: Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0.02f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.error),
        )
    }
}

private val CANCEL_DISTANCE = 120.dp
/** Дольше — запись, короче — тап. */
private const val HOLD_MS = 250L
private val LOCK_DISTANCE = 80.dp
private const val TIMER_TICK_MS = 200L
