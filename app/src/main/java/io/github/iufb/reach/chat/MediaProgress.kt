package io.github.iufb.reach.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R

/**
 * Загрузка медиа, как в Telegram iOS (#435): тёмный полупрозрачный круг поверх миниатюры, внутри
 * белое кольцо. [progress] 0…1 — доля загрузки, `null` — неизвестна (кольцо просто вращается);
 * [onCancel] — крестик в центре, тап отменяет.
 */
@Composable
internal fun MediaProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    cancelDescription: String = stringResource(R.string.upload_cancel),
) {
    Box(
        modifier
            .size(MEDIA_PROGRESS_SIZE)
            .clip(CircleShape)
            .background(MEDIA_PROGRESS_BACKGROUND)
            .then(if (onCancel != null) Modifier.clickable(role = Role.Button, onClickLabel = cancelDescription, onClick = onCancel) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        ProgressRing(progress, Color.White, Modifier.size(MEDIA_PROGRESS_SIZE - 8.dp))
        if (onCancel != null) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = cancelDescription, Modifier.size(20.dp), tint = Color.White)
        }
    }
}

/**
 * Кольцо прогресса без фона. Всегда медленно вращается, как в iOS; при известном [progress] дуга
 * плавно растёт, при `null` — короткая дуга крутится сама.
 */
@Composable
internal fun ProgressRing(progress: Float?, color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp) {
    val rotation by rememberInfiniteTransition(label = "ring").animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(ROTATION_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val sweep by animateFloatAsState((progress ?: INDETERMINATE_SWEEP).coerceIn(MIN_SWEEP, 1f), tween(PROGRESS_MS), label = "sweep")
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = 360f * sweep,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

internal val MEDIA_PROGRESS_SIZE = 48.dp
private val MEDIA_PROGRESS_BACKGROUND = Color.Black.copy(alpha = 0.45f)
private const val ROTATION_MS = 1200
private const val PROGRESS_MS = 250
private const val INDETERMINATE_SWEEP = 0.28f
private const val MIN_SWEEP = 0.04f
