package io.github.iufb.reach.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player
import io.github.iufb.reach.chats.VideoNoteInfo
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.rememberVideoPlayers
import kotlinx.coroutines.delay

/** Режим кружка: в ленте беззвучно по кругу, по тапу — со звуком с начала, повторный тап — пауза. */
@Stable
internal class VideoNoteState {
    enum class Mode { Inline, Playing, Paused }

    var mode by mutableStateOf(Mode.Inline)
        private set

    /** Тап: возвращает `true`, если начался просмотр со звуком с начала. */
    fun toggle(): Boolean {
        val started = mode == Mode.Inline
        mode = when (mode) {
            Mode.Inline, Mode.Paused -> Mode.Playing
            Mode.Playing -> Mode.Paused
        }
        return started
    }

    fun finish() {
        mode = Mode.Inline
    }
}

@Composable
internal fun rememberVideoNoteState(note: VideoNoteInfo): VideoNoteState = remember(note.file) { VideoNoteState() }

/**
 * Видеокружок без пузыря (PRODUCT.md §4.4). Тапы обрабатывает вызывающий через [state]:
 * так долгое нажатие и выделение работают как у остальных сообщений.
 * [isViewed] — у входящих непросмотренных рядом с длительностью точка.
 */
@Composable
internal fun VideoNoteView(note: VideoNoteInfo, state: VideoNoteState, isViewed: Boolean, modifier: Modifier = Modifier) {
    val rules = currentAutoDownloadRules()
    var requested by remember(note.file) { mutableStateOf(false) }
    val autoPlay = requested || rules.allows(MediaKind.Video, note.size)
    var positionMs by remember(note.file) { mutableLongStateOf(0L) }
    var progress by remember(note.file) { mutableFloatStateOf(0f) }
    Box(modifier.size(NOTE_SIZE), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape)) {
            PhotoThumbnail(note.poster, Modifier.fillMaxSize(), square = true, autoDownload = false)
            when (state.mode) {
                VideoNoteState.Mode.Inline -> if (autoPlay) InlineNotePlayer(note)
                else -> ActiveNotePlayer(
                    note,
                    playing = state.mode == VideoNoteState.Mode.Playing,
                    onProgress = { position, fraction ->
                        positionMs = position
                        progress = fraction
                    },
                    onEnded = {
                        state.finish()
                        progress = 0f
                        positionMs = 0
                    },
                )
            }
        }
        if (state.mode != VideoNoteState.Mode.Inline) {
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val stroke = RING_WIDTH.toPx()
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (!autoPlay && state.mode == VideoNoteState.Mode.Inline) {
            DownloadBadge(note.size, onClick = { requested = true })
        }
        // Длительность; во время просмотра — сколько осталось
        val shown = if (state.mode == VideoNoteState.Mode.Inline) note.duration.toLong() else note.duration - positionMs / 1000
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatDuration(shown), color = Color.White, style = MaterialTheme.typography.labelSmall)
            if (!isViewed) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(6.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
    }
}

/** Беззвучно по кругу, пока в ленте и есть место в [io.github.iufb.reach.media.PlaybackSlots]. */
@Composable
private fun InlineNotePlayer(note: VideoNoteInfo) {
    val players = rememberVideoPlayers()
    val slots = players.inlineSlots
    val owner = remember { Any() }
    LaunchedEffect(owner) {
        snapshotFlow { slots.taken }.collect { slots.tryAcquire(owner) }
    }
    DisposableEffect(owner) { onDispose { slots.release(owner) } }
    if (!slots.holds(owner)) return
    val player = remember(note.file) { players.create(note.file, muted = true, loop = true) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleStartEffect(player) {
        player.play()
        onStopOrDispose { player.pause() }
    }
    PlayerSurface(player)
}

/** Со звуком с начала; [playing] — играет или на паузе. По окончании — [onEnded]. */
@Composable
private fun ActiveNotePlayer(
    note: VideoNoteInfo,
    playing: Boolean,
    onProgress: (positionMs: Long, fraction: Float) -> Unit,
    onEnded: () -> Unit,
) {
    val players = rememberVideoPlayers()
    val player = remember(note.file) { players.create(note.file, playWhenReady = true) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(playing) { if (playing) player.play() else player.pause() }
    LifecycleStartEffect(player) {
        onStopOrDispose { player.pause() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration.takeIf { it > 0 } ?: (note.duration * 1000L).coerceAtLeast(1)
            val position = player.currentPosition
            onProgress(position, (position.toFloat() / duration).coerceIn(0f, 1f))
            delay(PROGRESS_TICK_MS)
        }
    }
    PlayerSurface(player)
}

/** Поверхность прозрачна до первого кадра — под ней виден постер. */
@Composable
private fun PlayerSurface(player: Player) {
    var firstFrame by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    VideoSurface(player, Modifier.fillMaxSize().graphicsLayer { alpha = if (firstFrame) 1f else 0f })
}

private val NOTE_SIZE = 220.dp
private val RING_WIDTH = 3.dp
private const val PROGRESS_TICK_MS = 50L
