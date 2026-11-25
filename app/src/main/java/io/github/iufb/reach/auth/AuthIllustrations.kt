package io.github.iufb.reach.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import io.github.iufb.reach.SplashGate

/** Иллюстрация шага входа (#375): синий круг знака Reach и белый символ по смыслу шага. */
enum class AuthIllustration { Phone, Code, Password }

/**
 * Круг цветов знака Reach (ADR-0012) с белым символом шага. Круг «выпрыгивает» при появлении шага,
 * символ живёт своей короткой петлёй: телефон звонит, в сообщении печатают и всплывает «1»,
 * дужка замка поднимается и защёлкивается. Символы нарисованы примитивами в сетке 24×24.
 */
@Composable
internal fun AuthBadge(kind: AuthIllustration, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // На холодном старте шаг собирается под сплэшем: прыжок — когда тот уйдёт
        SplashGate.awaitDismissed()
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f))
    }
    Box(
        modifier
            .size(BADGE_SIZE)
            .graphicsLayer {
                val s = POP_FROM + (1f - POP_FROM) * pop.value
                scaleX = s
                scaleY = s
                alpha = pop.value.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            AuthIllustration.Phone -> PhoneGlyph()
            AuthIllustration.Code -> CodeGlyph()
            AuthIllustration.Password -> LockGlyph()
        }
    }
}

/** Круг знака и белый символ в нём; [glyph] рисует в сетке 24×24 (единица — [u]). */
@Composable
private fun BadgeCanvas(modifier: Modifier = Modifier, glyph: DrawScope.(u: Float) -> Unit) {
    Canvas(modifier.size(BADGE_SIZE)) {
        drawCircle(Brush.linearGradient(listOf(BRAND_LIGHT, BRAND_DARK), Offset.Zero, Offset(size.width, size.height)))
        val box = size.minDimension * GLYPH_SHARE
        val u = box / 24f
        translate((size.width - box) / 2, (size.height - box) / 2) { glyph(u) }
    }
}

/** Телефон «звонит»: покачивается сериями, от круга расходятся две волны. */
@Composable
private fun PhoneGlyph() {
    val t = rememberInfiniteTransition(label = "phone")
    val wobble = t.loop(PHONE_MS) {
        0f at 0
        -12f at PHONE_MS * 10 / 100
        12f at PHONE_MS * 20 / 100
        -12f at PHONE_MS * 30 / 100
        12f at PHONE_MS * 40 / 100
        -12f at PHONE_MS * 50 / 100
        0f at PHONE_MS * 60 / 100
    }
    val wave1 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(PHONE_MS, easing = LinearEasing)), label = "wave1")
    val wave2 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(PHONE_MS, easing = LinearEasing), initialStartOffset = StartOffset(PHONE_MS / 4)), label = "wave2")
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(BADGE_SIZE)) {
            listOf(wave1.value, wave2.value).forEach { w ->
                drawCircle(
                    BRAND_LIGHT.copy(alpha = 0.7f * (1f - w)),
                    radius = size.minDimension / 2 * (0.95f + 0.4f * w),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        BadgeCanvas { u ->
            rotate(wobble.value, pivot = Offset(12 * u, 14 * u)) {
                drawRoundRect(Color.White, Offset(5.5f * u, 2 * u), Size(13 * u, 20 * u), CornerRadius(2.8f * u))
                drawRoundRect(BRAND_DARK, Offset(10.2f * u, 18 * u), Size(3.6f * u, 1.4f * u), CornerRadius(0.7f * u))
            }
        }
    }
}

/**
 * Код отправлен: сообщение один раз «приходит» с отскоком, в нём печатают, точки сменяются строками
 * текста, и на угол пузыря выпрыгивает бейдж «1». Дальше всё стоит, бейдж изредка мягко пульсирует.
 */
@Composable
private fun CodeGlyph() {
    val arrive = remember { Animatable(0f) }
    val typing = remember { Animatable(1f) }
    val badge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        SplashGate.awaitDismissed()
        arrive.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f))
        delay(TYPING_SHOWN_MS)
        typing.animateTo(0f, tween(TYPING_FADE_MS))
        badge.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
    }
    val t = rememberInfiniteTransition(label = "code")
    val dots = List(3) { i ->
        t.animateFloat(
            0f,
            0f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = TYPING_MS
                    1f at TYPING_MS * 30 / 100
                    0f at TYPING_MS * 60 / 100
                },
                initialStartOffset = StartOffset(i * TYPING_MS / 6),
            ),
            label = "dot$i",
        )
    }
    val pulse = t.animateFloat(
        1f,
        1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = PULSE_MS
                1f at PULSE_MS * 80 / 100
                1.14f at PULSE_MS * 87 / 100
                1f at PULSE_MS * 94 / 100
            },
        ),
        label = "pulse",
    )
    val measurer = rememberTextMeasurer()
    val one = remember(measurer) {
        measurer.measure("1", TextStyle(color = Color.White, fontSize = BADGE_TEXT, fontWeight = FontWeight.Bold))
    }
    BadgeCanvas { u ->
        val a = arrive.value
        val k = a.coerceIn(0f, 1f)
        translate(0f, (1f - k) * 3f * u) {
            scale(0.6f + 0.4f * a, pivot = Offset(7 * u, 20 * u)) {
                val bubble = Color.White.copy(alpha = k)
                drawCircle(bubble, radius = 8.6f * u, center = Offset(12 * u, 12.4f * u))
                // Хвостик пузыря слева снизу
                drawPath(
                    Path().apply {
                        moveTo(5.6f * u, 16f * u)
                        lineTo(3.6f * u, 20.8f * u)
                        lineTo(8.8f * u, 19.6f * u)
                        close()
                    },
                    bubble,
                )
                val w = typing.value
                dots.forEachIndexed { i, d ->
                    val x = (8.4f + 3.6f * i) * u
                    drawCircle(BRAND_DARK.copy(alpha = k * w * (0.35f + 0.65f * d.value)), 1.2f * u, Offset(x, (12.4f - 1.1f * d.value) * u))
                }
                // Пришло: две строки «текста» вместо точек
                val lines = (1f - w) * k
                if (lines > 0f) {
                    drawRoundRect(BRAND_DARK.copy(alpha = lines * 0.9f), Offset(7.6f * u, 10.2f * u), Size(8.8f * u * lines, 1.7f * u), CornerRadius(0.85f * u))
                    drawRoundRect(BRAND_DARK.copy(alpha = lines * 0.45f), Offset(7.6f * u, 13.2f * u), Size(5.8f * u * lines, 1.7f * u), CornerRadius(0.85f * u))
                }
                // Бейдж «1» на правом верхнем углу пузыря; тонкая синяя кайма отделяет его от пузыря
                val bs = badge.value * pulse.value
                if (bs > 0f) {
                    val c = Offset(18.6f * u, 5.6f * u)
                    scale(bs, pivot = c) {
                        drawCircle(BRAND_DARK, radius = 4.3f * u, center = c)
                        drawCircle(BADGE_RED, radius = 3.5f * u, center = c)
                        drawText(one, topLeft = Offset(c.x - one.size.width / 2f, c.y - one.size.height / 2f))
                    }
                }
            }
        }
    }
}

/** Дужка поднимается с наклоном и защёлкивается, корпус чуть приседает от удара. */
@Composable
private fun LockGlyph() {
    val t = rememberInfiniteTransition(label = "lock")
    val open = t.loop(LOCK_MS) {
        0f at LOCK_MS * 10 / 100
        1f at LOCK_MS * 25 / 100 using FastOutSlowInEasing
        1f at LOCK_MS * 45 / 100
        -0.12f at LOCK_MS * 58 / 100 using FastOutSlowInEasing
        0f at LOCK_MS * 64 / 100
    }
    val squash = t.loop(LOCK_MS) {
        0f at LOCK_MS * 56 / 100
        1f at LOCK_MS * 60 / 100
        0f at LOCK_MS * 68 / 100
    }
    BadgeCanvas { u ->
        val o = open.value
        // Ноги дужки уходят в корпус: поднятая дужка не отрывается от него
        translate(0f, -3.2f * u * o) {
            rotate(16f * o.coerceAtLeast(0f), pivot = Offset(15.6f * u, 13 * u)) {
                drawPath(
                    Path().apply {
                        moveTo(8.4f * u, 13 * u)
                        lineTo(8.4f * u, 7.6f * u)
                        arcTo(androidx.compose.ui.geometry.Rect(8.4f * u, 4f * u, 15.6f * u, 11.2f * u), 180f, 180f, false)
                        lineTo(15.6f * u, 13 * u)
                    },
                    Color.White,
                    style = Stroke(2.3f * u, cap = StrokeCap.Round),
                )
            }
        }
        val s = squash.value
        scale(1f + 0.06f * s, 1f - 0.07f * s, pivot = Offset(12 * u, 21 * u)) {
            drawRoundRect(Color.White, Offset(4.5f * u, 10.4f * u), Size(15 * u, 11.2f * u), CornerRadius(2.3f * u))
            drawCircle(BRAND_DARK, 1.4f * u, Offset(12 * u, 15.4f * u))
            drawRoundRect(BRAND_DARK, Offset(11.3f * u, 15.4f * u), Size(1.4f * u, 2.6f * u), CornerRadius(0.5f * u))
        }
    }
}

/** Бесконечная петля по ключевым кадрам длиной [durationMs]. */
@Composable
private fun androidx.compose.animation.core.InfiniteTransition.loop(
    durationMs: Int,
    frames: androidx.compose.animation.core.KeyframesSpec.KeyframesSpecConfig<Float>.() -> Unit,
): State<Float> = animateFloat(
    0f,
    0f,
    InfiniteRepeatableSpec(keyframes { this.durationMillis = durationMs; frames() }, RepeatMode.Restart),
    label = "loop",
)

/** Как знак Reach на экране входа (ADR-0012), чуть крупнее — иллюстрация главная на шаге. */
internal val BADGE_SIZE = 104.dp
private const val GLYPH_SHARE = 0.5f
private const val POP_FROM = 0.6f
private const val PHONE_MS = 1_600
private const val TYPING_MS = 900
private const val TYPING_SHOWN_MS = 1_300L
private const val TYPING_FADE_MS = 220
private const val PULSE_MS = 3_000
private val BADGE_TEXT = 10.sp
private const val LOCK_MS = 2_800
private val BRAND_LIGHT = Color(0xFF3D9BFF)
private val BRAND_DARK = Color(0xFF0059E8)
private val BADGE_RED = Color(0xFFFF3B30)
