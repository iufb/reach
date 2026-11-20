package io.github.iufb.reach.chat

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.VideoInfo
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.PlaybackSlots
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.rememberAutoDownload
import io.github.iufb.reach.media.rememberVideoPlayers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Видео в пузыре: постер с пропорциями ролика, кнопка ▶ и длительность. */
@Composable
internal fun VideoThumbnail(video: VideoInfo, modifier: Modifier = Modifier, square: Boolean = false) {
    // Разрешено правилами — докачиваем ролик заранее, чтобы по тапу он стартовал сразу
    val autoDownload = rememberAutoDownload()
    val rules = currentAutoDownloadRules()
    LaunchedEffect(video.file, rules) {
        if (rules.allows(MediaKind.Video, video.size)) autoDownload.preload(video.file)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        PhotoThumbnail(video.poster, square = square, autoDownload = false)
        PlayBadge()
        Text(
            formatDuration(video.duration.toLong()),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PlayBadge() {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.video_play), tint = Color.White)
    }
}

/**
 * Страница видео в полноэкранном просмотре. Плеер создаётся, только пока страница открыта ([active]),
 * и освобождается при уходе с неё; вертикальный свайп закрывает просмотр.
 */
@Composable
internal fun VideoPage(video: VideoInfo, active: Boolean, onDismiss: () -> Unit, gif: Boolean = false) {
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dismissY.value }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (abs(dismissY.value) > size.height * DISMISS_FRACTION) dismiss()
                        else scope.launch { dismissY.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { dismissY.animateTo(0f) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { dismissY.snapTo(dismissY.value + dragAmount) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            VideoPlayback(video, gif)
        } else {
            Poster(video, Modifier.aspectRatio(video.ratio()))
            if (!gif) PlayBadge()
        }
    }
}

@Composable
private fun Poster(video: VideoInfo, modifier: Modifier) {
    AsyncImage(
        video.poster.full ?: video.poster.preview ?: video.poster.minithumbnail,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Плеер с управлением: тап показывает/прячет панель, ползунок перематывает. [gif] — по кругу и без звука. */
@Composable
private fun VideoPlayback(video: VideoInfo, gif: Boolean) {
    val players = rememberVideoPlayers()
    val player = remember(video.file) { players.create(video.file, muted = gif, loop = gif) }
    DisposableEffect(player) {
        onDispose {
            player.release()
            // GIF маленькие — пусть докачаются в кэш, а длинное видео дальше не качаем
            if (!gif) players.stopDownload(video.file)
        }
    }
    // Свернули приложение — пауза
    LifecycleStartEffect(player) { onStopOrDispose { player.pause() } }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(video.ratio()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) failed = false
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(video.duration * 1000L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { duration = it }
            delay(POSITION_TICK_MS)
        }
    }

    /** Значение ползунка, пока его тянут; `null` — показываем позицию плеера. */
    var seeking by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, playing, seeking) {
        if (controls && playing && seeking == null) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.aspectRatio(ratio)) {
            VideoSurface(player, Modifier.fillMaxSize())
            // До первого кадра — постер, чтобы не мигал чёрный экран
            if (!firstFrame) Poster(video, Modifier.fillMaxSize())
        }
        when {
            failed -> Text(stringResource(R.string.video_play_error), color = Color.White)
            buffering -> CircularProgressIndicator(color = Color.White)
        }
        if (controls && !gif) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        when {
                            ended -> {
                                player.seekTo(0)
                                player.play()
                            }
                            playing -> player.pause()
                            else -> {
                                if (failed) player.prepare()
                                player.play()
                            }
                        }
                    },
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (playing) R.string.video_pause else R.string.video_play),
                        tint = Color.White,
                    )
                }
                val shown = seeking?.let { (it * duration).toLong() } ?: position
                Text(
                    "${formatDuration(shown / 1000)} / ${formatDuration(duration / 1000)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = seeking ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { player.seekTo((it * duration).toLong()) }
                        seeking = null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            }
        }
    }
}

/** Кадры плеера. TextureView, а не SurfaceView: двигается вместе со свайпом и клипается по пузырю. */
@Composable
internal fun VideoSurface(player: Player, modifier: Modifier) {
    AndroidView(
        factory = { TextureView(it) },
        modifier = modifier,
        update = { player.setVideoTextureView(it) },
        onRelease = { player.clearVideoTextureView(it) },
    )
}

/**
 * GIF в пузыре: постер, а поверх — беззвучный плеер по кругу. Плеер живёт, только пока элемент
 * в ленте (LazyColumn убирает ушедшие с экрана), и только если досталось место в [PlaybackSlots].
 */
@Composable
internal fun AnimationThumbnail(animation: VideoInfo, modifier: Modifier = Modifier) {
    val rules = currentAutoDownloadRules()
    var requested by remember(animation.file) { mutableStateOf(false) }
    if (!requested && !rules.allows(MediaKind.Gif, animation.size)) {
        // Автозагрузка GIF выключена: постер и кнопка, по тапу — играть
        Box(modifier, contentAlignment = Alignment.Center) {
            PhotoThumbnail(animation.poster, autoDownload = false)
            DownloadBadge(animation.size, onClick = { requested = true })
        }
        return
    }
    val players = rememberVideoPlayers()
    val slots = players.inlineSlots
    val owner = remember { Any() }
    LaunchedEffect(owner) {
        // Повторяем попытку каждый раз, когда кто-то освобождает место
        snapshotFlow { slots.taken }.collect { slots.tryAcquire(owner) }
    }
    DisposableEffect(owner) { onDispose { slots.release(owner) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        PhotoThumbnail(animation.poster, autoDownload = false)
        if (slots.holds(owner)) InlineAnimationPlayer(animation, Modifier.matchParentSize())
        Text(
            stringResource(R.string.content_animation),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun InlineAnimationPlayer(animation: VideoInfo, modifier: Modifier) {
    val players = rememberVideoPlayers()
    val player = remember(animation.file) { players.create(animation.file, muted = true, loop = true) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleStartEffect(player) {
        player.play()
        onStopOrDispose { player.pause() }
    }
    var firstFrame by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Пока нет кадра, плеер прозрачен — виден постер под ним
    VideoSurface(player, modifier.graphicsLayer { alpha = if (firstFrame) 1f else 0f })
}

/** Пропорции кадра для разметки до старта плеера; неизвестные — 16:9. */
private fun VideoInfo.ratio(): Float =
    if (width > 0 && height > 0) width.toFloat() / height else 16f / 9

private const val POSITION_TICK_MS = 200L
private const val CONTROLS_HIDE_MS = 3000L
