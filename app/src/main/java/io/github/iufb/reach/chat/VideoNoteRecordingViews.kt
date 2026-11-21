package io.github.iufb.reach.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.iufb.reach.R
import io.github.iufb.reach.media.VideoNoteCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Запись кружка поверх чата: круглое превью камеры, кольцо — сколько из 60 с прошло, смена камеры.
 * Управление записью — кнопкой в поле ввода (удержание, свайпы), как у голосовых.
 */
@Composable
internal fun VideoNoteCameraOverlay(camera: VideoNoteCamera, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(camera) {
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
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Круг вырастает из кнопки записи; пока камера открывается (~0,8 с), вместо чёрного круга — заглушка
            // со значком камеры, настоящее превью проявляется поверх, когда экспозиция сошлась (#196)
            val ready by camera.previewReady.collectAsStateWithLifecycle()
            val grow = remember { Animatable(GROW_FROM) }
            LaunchedEffect(Unit) { grow.animateTo(1f, tween(GROW_MS)) }
            val previewAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(FADE_MS), label = "preview")
            Box(
                Modifier.size(PREVIEW_SIZE).graphicsLayer {
                    scaleX = grow.value
                    scaleY = grow.value
                    alpha = ((grow.value - GROW_FROM) / (1f - GROW_FROM)).coerceIn(0f, 1f)
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(ReachTheme.colors.fill), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_videocam),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = ReachTheme.colors.secondaryLabel,
                    )
                }
                AndroidView(
                    factory = { camera.previewView },
                    modifier = Modifier.fillMaxSize().clip(CircleShape).graphicsLayer { alpha = previewAlpha },
                )
                RecordingRing(camera.startedAt)
            }
            IconButton(onClick = camera::flip) {
                Icon(painterResource(R.drawable.ic_flip_camera), stringResource(R.string.video_note_flip), tint = Color.White)
            }
        }
    }
}

/** Кольцо по краю превью: доля от предела в 60 с. */
@Composable
private fun RecordingRing(startedAt: Long) {
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(startedAt) {
        if (startedAt == 0L) return@LaunchedEffect
        while (true) {
            fraction = ((SystemClock.elapsedRealtime() - startedAt) / MAX_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            delay(RING_TICK_MS)
        }
    }
    val color = ReachTheme.colors.destructive
    Canvas(Modifier.fillMaxSize()) {
        val stroke = RING_WIDTH.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private val PREVIEW_SIZE = 280.dp
private const val GROW_FROM = 0.6f
private const val GROW_MS = 220
private const val FADE_MS = 180
private val RING_WIDTH = 4.dp
private const val MAX_DURATION_MS = 60_000L
private const val RING_TICK_MS = 50L
