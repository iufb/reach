package io.github.iufb.reach.chat

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.iufb.reach.R
import io.github.iufb.reach.media.VideoNoteCamera
import io.github.iufb.reach.media.VideoNoteCamera.Outcome
import io.github.iufb.reach.media.VideoNoteCamera.Phase
import io.github.iufb.reach.ui.components.glass
import io.github.iufb.reach.ui.components.glassBackdrop
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Запись кружка поверх ленты, как в Telegram iOS: лента уходит под размытый фон, крупный круг
 * камеры с тонким кольцом 60 с по краю, слева над полем записи — смена камеры и вспышка.
 * Управление записью (удержание, свайпы, замок, стоп) — кнопкой и полосой записи в поле ввода,
 * они рисуются поверх этого слоя ([composerHeight] — их высота).
 *
 * Анимации: круг вырастает с пружиной; отмена — сжимается и гаснет; отправка — улетает
 * на место кружка в ленте (справа внизу) и гаснет; смена камеры — переворот с размытым
 * последним кадром, пока новая камера не подберёт экспозицию. После «стоп» в зафиксированном
 * режиме кружок проигрывается в том же круге ([Phase.Recorded]).
 */
@Composable
internal fun VideoNoteCameraOverlay(camera: VideoNoteCamera, composerHeight: Dp, modifier: Modifier = Modifier) {
    val active = camera.phase != Phase.Idle && camera.outcome == null
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    var exit by remember { mutableStateOf(Outcome.Discard) }
    LaunchedEffect(active) {
        if (active) {
            leave.snapTo(0f)
            enter.animateTo(1f, spring(dampingRatio = ENTER_DAMPING, stiffness = ENTER_STIFFNESS))
        } else if (enter.value > 0f) {
            exit = camera.outcome ?: Outcome.Discard
            if (exit == Outcome.Send) {
                leave.animateTo(1f, tween(SEND_MS, easing = FastOutSlowInEasing))
            } else {
                leave.animateTo(1f, tween(CANCEL_MS, easing = FastOutLinearInEasing))
            }
            enter.snapTo(0f)
            leave.snapTo(0f)
        }
    }
    if (!active && enter.value == 0f) return
    OverlayContent(camera, active, composerHeight, { enter.value }, { leave.value }, { exit }, modifier)
}

@Composable
private fun OverlayContent(
    camera: VideoNoteCamera,
    active: Boolean,
    composerHeight: Dp,
    enter: () -> Float,
    leave: () -> Float,
    exit: () -> Outcome,
    modifier: Modifier,
) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(active) {
        if (!active || camera.phase != Phase.Starting) return@LaunchedEffect
        try {
            camera.open(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VideoNoteCamera", "camera unavailable", e)
            camera.finish(send = false)
        }
    }
    DisposableEffect(camera) { onDispose { camera.release() } }
    val colors = ReachTheme.colors
    val reviewing = camera.phase == Phase.Recorded
    // Отправленный или удалённый из предпросмотра кружок уходит с последним кадром, а не с чёрной заглушкой камеры
    val lastReview = remember { mutableStateOf<String?>(null) }
    SideEffect {
        val path = camera.recordedPath
        if (path != null) lastReview.value = path else if (active) lastReview.value = null
    }
    val reviewPath = camera.recordedPath ?: lastReview.value.takeIf { !active }
    val flips = remember { Channel<Unit>(Channel.CONFLATED) }
    val screenFlash by animateFloatAsState(
        if (camera.flash && camera.front && !reviewing && active) 1f else 0f,
        tween(FADE_MS),
        label = "screenFlash",
    )
    ScreenBrightness(camera.flash && camera.front && !reviewing && active)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Лента под фоном не нажимается; кнопки поверх получают касания раньше
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
    ) {
        val density = LocalDensity.current
        val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        // Круг почти во всю ширину; по высоте — между статус-баром и местом для кнопок и замка над полем
        val bottomReserve = composerHeight + BOTTOM_RESERVE
        val diameter = min(maxWidth * CIRCLE_WIDTH_FRACTION, maxHeight - statusTop - bottomReserve - 16.dp).coerceAtLeast(120.dp)
        val centerY = (maxHeight * CIRCLE_CENTER_FRACTION)
            .coerceAtMost(maxHeight - bottomReserve - diameter / 2)
            .coerceAtLeast(statusTop + 16.dp + diameter / 2)
        // Куда «улетает» отправленный кружок: на место нового исходящего в ленте
        val target = Offset(
            x = with(density) { (maxWidth - FEED_NOTE_END - FEED_NOTE_SIZE / 2 - maxWidth / 2).toPx() },
            y = with(density) { (maxHeight - composerHeight - FEED_NOTE_BOTTOM - FEED_NOTE_SIZE / 2 - centerY).toPx() },
        )
        val sendScale = FEED_NOTE_SIZE / diameter

        // Фон: размытая лента (мощные устройства) или обои почти непрозрачной заливкой
        val backdrop = Brush.verticalGradient(
            listOf(colors.wallpaperTop.copy(alpha = BACKDROP_ALPHA), colors.wallpaperBottom.copy(alpha = BACKDROP_ALPHA)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enter().coerceIn(0f, 1f) * (1f - leave()) }
                .glassBackdrop(BACKDROP_BLUR, colors.wallpaperBottom.copy(alpha = BACKDROP_TINT_ALPHA), backdrop),
        )
        // Вспышка фронтальной камеры: экран светит тёплым белым вокруг круга
        if (screenFlash > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = screenFlash * (1f - leave()) }
                    .background(SCREEN_FLASH),
            )
        }

        Box(
            Modifier
                .offset(x = (maxWidth - diameter) / 2, y = centerY - diameter / 2)
                .size(diameter)
                .graphicsLayer {
                    val e = enter()
                    val l = leave()
                    var scale = lerp(ENTER_FROM, 1f, e)
                    var a = (e / ENTER_ALPHA_PART).coerceIn(0f, 1f)
                    if (l > 0f) {
                        if (exit() == Outcome.Send) {
                            scale *= lerp(1f, sendScale, l)
                            translationX = target.x * l
                            translationY = target.y * l
                            a *= 1f - ((l - SEND_FADE_FROM) / (1f - SEND_FADE_FROM)).coerceIn(0f, 1f)
                        } else {
                            scale *= lerp(1f, CANCEL_TO, l)
                            a *= 1f - l
                        }
                    }
                    scaleX = scale
                    scaleY = scale
                    alpha = a
                },
        ) {
            if (reviewPath != null) {
                ReviewCircle(camera, reviewPath)
            } else {
                CameraCircle(camera, flips)
            }
        }

        // Смена камеры и вспышка — круглые стеклянные кнопки слева над полем записи
        val controlsAlpha by animateFloatAsState(if (reviewing || !active) 0f else 1f, tween(FADE_MS), label = "controls")
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = CONTROLS_START, bottom = composerHeight + CONTROLS_GAP)
                .graphicsLayer {
                    val shown = ((enter() - CONTROLS_DELAY_PART) / (1f - CONTROLS_DELAY_PART)).coerceIn(0f, 1f) * controlsAlpha
                    alpha = shown
                    val s = lerp(CONTROLS_FROM, 1f, shown)
                    scaleX = s
                    scaleY = s
                },
            horizontalArrangement = Arrangement.spacedBy(CONTROLS_SPACING),
        ) {
            val enabled = !reviewing && active
            CameraButton(R.drawable.ic_flip_camera, stringResource(R.string.video_note_flip), enabled) { flips.trySend(Unit) }
            if (camera.front || camera.torchAvailable) {
                CameraButton(
                    if (camera.flash) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
                    stringResource(R.string.video_note_flash),
                    enabled,
                    camera::toggleFlash,
                )
            }
        }
    }
}

/** Живое превью камеры в круге, кольцо записи и переворот при смене камеры. */
@Composable
private fun CameraCircle(camera: VideoNoteCamera, flips: ReceiveChannel<Unit>) {
    // Пока камера открывается (~0,8 с), вместо чёрного круга — заглушка со значком камеры,
    // настоящее превью проявляется поверх, когда экспозиция сошлась (#196)
    val ready by camera.previewReady.collectAsStateWithLifecycle()
    val previewAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(FADE_MS), label = "preview")
    val rotation = remember { Animatable(0f) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val snapshotAlpha = remember { Animatable(0f) }
    LaunchedEffect(flips) {
        // По одной смене за раз; нажатия во время переворота склеиваются в одно
        while (true) {
            flips.receive()
            // Последний кадр, сжатый до нескольких десятков точек: растянутый с фильтрацией, он выглядит размытым
            snapshot = runCatching { camera.previewView.bitmap?.let { tinyCopy(it).asImageBitmap() } }.getOrNull()
            snapshotAlpha.snapTo(if (snapshot != null) 1f else 0f)
            rotation.animateTo(90f, tween(FLIP_HALF_MS, easing = FastOutLinearInEasing))
            camera.flip()
            rotation.snapTo(-90f)
            rotation.animateTo(0f, tween(FLIP_HALF_MS, easing = LinearOutSlowInEasing))
            // Кадр новой камеры держим размытым, пока она не подберёт экспозицию
            withTimeoutOrNull(FLIP_EXPOSURE_TIMEOUT_MS) { camera.previewReady.first { it } }
            snapshotAlpha.animateTo(0f, tween(FADE_MS))
            snapshot = null
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = CAMERA_DISTANCE * density
            },
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_videocam),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White.copy(alpha = 0.4f),
            )
        }
        AndroidView(
            factory = { camera.previewView },
            modifier = Modifier.fillMaxSize().clip(CircleShape).graphicsLayer { alpha = previewAlpha },
        )
        snapshot?.let { frame ->
            Image(
                frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape).graphicsLayer { alpha = snapshotAlpha.value },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
            )
        }
        RecordingRing { recordingFraction(camera.startedAt) }
    }
}

/** Записанный кружок по кругу в том же круге; тап — пауза / продолжить. */
@Composable
private fun ReviewCircle(camera: VideoNoteCamera, path: String) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, camera.reviewPlaying) { player.playWhenReady = camera.reviewPlaying }
    var progress by remember(player) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            withFrameMillis { }
            val duration = player.duration
            progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
        }
    }
    val description = stringResource(R.string.video_note_review)
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color.Black)
            .clickable(onClickLabel = description, onClick = camera::toggleReviewPlayback),
    ) {
        VideoSurface(player, Modifier.fillMaxSize())
    }
    RecordingRing { progress }
}

/** Тонкое светлое кольцо по краю круга; [fraction] опрашивается каждый кадр, меняется только рисование. */
@Composable
private fun RecordingRing(fraction: () -> Float) {
    // Плавный ход: перерисовка каждый кадр, пока кольцо на экране
    var value by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { value = fraction() }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (value <= 0f) return@Canvas
        val stroke = RING_WIDTH.toPx()
        val inset = RING_INSET.toPx() + stroke / 2
        drawArc(
            color = RING_COLOR,
            startAngle = -90f,
            sweepAngle = 360f * value,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private fun recordingFraction(startedAt: Long): Float =
    if (startedAt == 0L) 0f else ((SystemClock.elapsedRealtime() - startedAt) / MAX_DURATION_MS.toFloat()).coerceIn(0f, 1f)

@Composable
private fun CameraButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(CONTROL_SIZE)
            .glass(CircleShape)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp), tint = ReachTheme.colors.label)
    }
}

/** Вспышка фронтальной камеры: яркость экрана на максимум, пока она включена. */
@Composable
private fun ScreenBrightness(on: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, on) {
        if (!on) return@DisposableEffect onDispose { }
        val attrs = window.attributes
        val previous = attrs.screenBrightness
        window.attributes = attrs.apply { screenBrightness = 1f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

private fun tinyCopy(source: Bitmap): Bitmap = Bitmap.createScaledBitmap(source, SNAPSHOT_PX, SNAPSHOT_PX, true)

// Геометрия (Mobbin, Telegram iOS: круг ~94 % ширины, центр на ~40 % высоты экрана)
private const val CIRCLE_WIDTH_FRACTION = 0.94f
private const val CIRCLE_CENTER_FRACTION = 0.42f
/** Над полем записи: кнопки камеры и капсула замка. */
private val BOTTOM_RESERVE = 140.dp
private val CONTROL_SIZE = 36.dp
private val CONTROLS_START = 16.dp
private val CONTROLS_GAP = 10.dp
private val CONTROLS_SPACING = 12.dp
private val RING_WIDTH = 3.dp
private val RING_INSET = 2.dp
private val RING_COLOR = Color.White.copy(alpha = 0.85f)
private val SCREEN_FLASH = Color(0xFFFFF6E5)
private val BACKDROP_BLUR = 40.dp
private const val BACKDROP_ALPHA = 0.96f
private const val BACKDROP_TINT_ALPHA = 0.45f
/** Кружок в ленте: VideoNoteView, 220 dp, у правого края над полем ввода. */
private val FEED_NOTE_SIZE = 220.dp
private val FEED_NOTE_END = 12.dp
private val FEED_NOTE_BOTTOM = 8.dp

// Тайминги — на глаз по поведению Telegram iOS, без видеорефа (#297); сверить, когда будет видео
private const val ENTER_FROM = 0.2f
private const val ENTER_ALPHA_PART = 0.35f
private const val ENTER_DAMPING = 0.72f
private const val ENTER_STIFFNESS = 380f
private const val CONTROLS_DELAY_PART = 0.5f
private const val CONTROLS_FROM = 0.6f
private const val CANCEL_MS = 220
private const val CANCEL_TO = 0.1f
private const val SEND_MS = 320
private const val SEND_FADE_FROM = 0.6f
private const val FADE_MS = 200
private const val FLIP_HALF_MS = 160
private const val FLIP_EXPOSURE_TIMEOUT_MS = 1_000L
private const val CAMERA_DISTANCE = 12f
private const val SNAPSHOT_PX = 24
private const val MAX_DURATION_MS = 60_000L
