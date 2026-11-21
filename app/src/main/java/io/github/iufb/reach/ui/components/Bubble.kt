package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Пузырь сообщения Telegram iOS: скругление [radius], у последнего в группе — хвостик внизу
 * со стороны отправителя. Хвостик выступает за тело на [TailWidth]: тело пузыря стоит с отступом
 * [TailWidth] с этой стороны всегда, чтобы пузыри с хвостиком и без не прыгали по горизонтали.
 */
class BubbleShape(
    private val outgoing: Boolean,
    private val tail: Boolean,
    private val radius: Dp = BubbleRadius,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val tw = with(density) { TailWidth.toPx() }
        // Хвостик справа у исходящих в LTR; RTL отражает
        val right = outgoing != (layoutDirection == LayoutDirection.Rtl)
        val left = if (right) 0f else tw
        val body = RoundRect(left, 0f, left + size.width - tw, size.height, CornerRadius(r))
        val path = Path().apply { addRoundRect(body) }
        if (!tail) return Outline.Generic(path)

        val tailPath = Path().apply {
            val bottom = size.height
            if (right) {
                val edge = body.right
                moveTo(edge - r, bottom)
                // Нижний край тела уходит в острие справа внизу и плавно возвращается к боковой стороне
                lineTo(edge + tw - 1f, bottom)
                quadraticTo(edge + tw * 0.3f, bottom - tw * 0.2f, edge, bottom - tw * 2.4f)
                lineTo(edge, bottom - r)
                close()
            } else {
                val edge = body.left
                moveTo(edge + r, bottom)
                lineTo(edge - tw + 1f, bottom)
                quadraticTo(edge - tw * 0.3f, bottom - tw * 0.2f, edge, bottom - tw * 2.4f)
                lineTo(edge, bottom - r)
                close()
            }
        }
        return Outline.Generic(Path().apply { op(path, tailPath, PathOperation.Union) })
    }

    override fun equals(other: Any?) =
        other is BubbleShape && other.outgoing == outgoing && other.tail == tail && other.radius == radius

    override fun hashCode() = (outgoing.hashCode() * 31 + tail.hashCode()) * 31 + radius.hashCode()
}

val BubbleRadius = 17.dp
val TailWidth = 6.dp

/**
 * Пузырь с цветом по направлению и внутренним отступом; ширину и выравнивание задаёт лента.
 * Отступ со стороны хвостика больше на [TailWidth] — там место под него.
 */
@Composable
fun MessageBubble(
    outgoing: Boolean,
    tail: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = ReachTheme.colors
    val shape = BubbleShape(outgoing, tail)
    val start = if (outgoing) 12.dp else 12.dp + TailWidth
    val end = if (outgoing) 12.dp + TailWidth else 12.dp
    Box(
        modifier
            .background(if (outgoing) colors.bubbleOutgoing else colors.bubbleIncoming, shape)
            .padding(start = start, end = end, top = 6.dp, bottom = 6.dp),
        content = content,
    )
}
