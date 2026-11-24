package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * Шрифтовой эмодзи, чей рисунок по размеру и центру совпадает с лицом анимированного эмодзи (#372):
 * при подмене текста анимацией ничего не прыгает. Круг лица в TGS — [faceFraction] стороны холста
 * [box] (у эмодзи Telegram ≈ 0,78: 399 из 512).
 *
 * Шрифты рисуют эмодзи разного размера относительно кегля (Apple ≈ 0,91, Noto ≈ 1,07), поэтому рисунок
 * текущего emoji-шрифта измеряется один раз — по непрозрачным пикселям — и кегль подбирается под него.
 */
@Composable
internal fun FittedEmoji(emoji: String, box: Dp, faceFraction: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = LocalTextStyle.current
    val ink = remember(style.fontFamily) { inkCache.getOrPut(style.fontFamily ?: FontFamily.Default) { measureEmojiInk(measurer, style) } }
    val density = LocalDensity.current
    val face = box * faceFraction
    val fontPx = with(density) { face.toPx() } / ink.size
    Box(modifier.size(box), contentAlignment = Alignment.Center) {
        Text(
            emoji,
            // Без лишних полей шрифта: центр рисунка сдвигаем ровно в центр холста
            Modifier
                .wrapContentSize(unbounded = true)
                .offset { ink.offsetToCenter(fontPx) },
            // Кегль в пикселях, не sp: размер не зависит от масштаба шрифта системы
            style = style.copy(fontSize = with(density) { fontPx.toSp() }, lineHeight = with(density) { fontPx.toSp() }),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Рисунок эмодзи при кегле 1: [size] — сторона рисунка (по ширине), [dx]/[dy] — насколько его центр
 * смещён от центра строки текста.
 */
private class EmojiInk(val size: Float, val dx: Float, val dy: Float) {
    fun offsetToCenter(fontPx: Float) = IntOffset(
        (-dx * fontPx).toInt(),
        (-dy * fontPx).toInt(),
    )
}

/** Замер на шрифт — один на процесс: элементы ленты создаются заново при каждой прокрутке. */
private val inkCache = java.util.concurrent.ConcurrentHashMap<FontFamily, EmojiInk>()

/** Сколько рисунка даёт шрифт: эталонный эмодзи кеглем [PROBE_PX] в битмап, рамка по альфе. */
private fun measureEmojiInk(measurer: TextMeasurer, style: TextStyle): EmojiInk = runCatching {
    val unit = Density(1f)
    val layout = measurer.measure(
        PROBE,
        style.copy(fontSize = PROBE_PX.sp, lineHeight = PROBE_PX.sp),
        density = unit,
        softWrap = false,
        maxLines = 1,
    )
    val w = layout.size.width
    val h = layout.size.height
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(unit, LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        drawText(layout)
    }
    val pixels = bitmap.toPixelMap()
    var left = w
    var right = -1
    var top = h
    var bottom = -1
    for (y in 0 until h) for (x in 0 until w) {
        if (pixels[x, y].alpha > ALPHA_THRESHOLD) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return@runCatching FALLBACK
    EmojiInk(
        size = (right - left + 1) / PROBE_PX,
        dx = ((left + right + 1) / 2f - w / 2f) / PROBE_PX,
        dy = ((top + bottom + 1) / 2f - h / 2f) / PROBE_PX,
    )
}.getOrDefault(FALLBACK)

/** Лицо целиком в круге — эталон для любых круглых эмодзи-смайлов. */
private const val PROBE = "😀"
private const val PROBE_PX = 128f
private const val ALPHA_THRESHOLD = 0.15f

/** Нечего измерить (шрифт ещё не загрузился): как у Noto. */
private val FALLBACK = EmojiInk(size = 1.07f, dx = 0f, dy = 0f)
