package io.github.iufb.reach.chat

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.iufb.reach.R
import io.github.iufb.reach.media.PhotoCamera
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Новый `PreviewView` для превью камеры: TextureView — обрезается по ячейке и анимируется. */
internal fun cameraPreviewView(context: android.content.Context) = PreviewView(context).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
}

/**
 * Первая ячейка сетки скрепки, как в Telegram iOS (#421): живое превью задней камеры, значок камеры
 * в углу. Без разрешения — тёмная ячейка со значком; тап запрашивает доступ.
 */
@Composable
internal fun CameraCell(
    camera: PhotoCamera,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color.Black)
            .clickable(onClickLabel = stringResource(R.string.attach_camera), role = Role.Button, onClick = onClick),
    ) {
        if (granted) {
            AndroidView(
                factory = { context -> cameraPreviewView(context).also { camera.attach(it, full = false) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            painterResource(R.drawable.ic_camera),
            contentDescription = null,
            modifier = Modifier.align(if (granted) Alignment.TopEnd else Alignment.Center).padding(8.dp).size(if (granted) 24.dp else 32.dp),
            tint = Color.White,
        )
    }
}

/**
 * Камера на весь экран: раскрывается из ячейки [from] (координаты окна), сворачивается обратно в неё.
 * Сверху — закрыть и вспышка, снизу — спуск и смена камеры. Снимок уходит в [onShot].
 */
@Composable
internal fun FullScreenCamera(
    camera: PhotoCamera,
    from: Rect,
    onClosed: () -> Unit,
    onShot: (File) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(EXPAND_MS, easing = FastOutSlowInEasing)) }
    val collapse: () -> Unit = {
        scope.launch {
            progress.animateTo(0f, tween(EXPAND_MS, easing = FastOutSlowInEasing))
            onClosed()
        }
    }
    BackHandler(onBack = collapse)
    Box(
        Modifier
            .layout { measurable, constraints ->
                // Прямоугольник между ячейкой и всем экраном
                val p = progress.value
                val width = lerp(from.width, constraints.maxWidth.toFloat(), p).roundToInt()
                val height = lerp(from.height, constraints.maxHeight.toFloat(), p).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(IntOffset(lerp(from.left, 0f, p).roundToInt(), lerp(from.top, 0f, p).roundToInt()))
                }
            }
            .background(Color.Black)
            // Касания не проходят к шторке под камерой
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
    ) {
        AndroidView(
            factory = { context -> cameraPreviewView(context).also { camera.attach(it, full = true) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraButton(R.drawable.ic_close, stringResource(R.string.close), collapse)
                Box(Modifier.weight(1f))
                if (camera.flashAvailable && !camera.front) {
                    CameraButton(
                        if (camera.flash) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
                        stringResource(R.string.video_note_flash),
                        camera::toggleFlash,
                    )
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Shutter(enabled = !camera.capturing) {
                    scope.launch { runCatching { camera.takePhoto() }.onSuccess(onShot) }
                }
                CameraButton(
                    R.drawable.ic_flip_camera,
                    stringResource(R.string.video_note_flip),
                    camera::flip,
                    Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun CameraButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = Color.White)
    }
}

/** Спуск как в камере iOS: белый круг в белом кольце; во время снимка — приглушён. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.attach_camera_take)
    Box(
        Modifier
            .size(SHUTTER)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick),
    )
}

private const val EXPAND_MS = 280
private val SHUTTER = 76.dp
