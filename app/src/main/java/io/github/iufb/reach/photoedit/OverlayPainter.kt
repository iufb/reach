package io.github.iufb.reach.photoedit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/**
 * Рисунок и надписи поверх фото — одним кодом для предпросмотра в редакторе и для итога
 * ([PhotoEditRenderer]), чтобы они совпадали. [area] — где на холсте лежит повёрнутое изображение.
 */
object OverlayPainter {

    /** Шрифт надписей: жирный SF Pro, если он выбран в Настройки → Шрифт (ставит MainActivity при старте). */
    @Volatile var typeface: Typeface = Typeface.DEFAULT_BOLD

    fun draw(canvas: Canvas, edit: PhotoEdit, area: RectF, extraStroke: BrushStroke? = null) {
        drawStrokes(canvas, if (extraStroke == null) edit.strokes else edit.strokes + extraStroke, area)
        drawTexts(canvas, edit.texts, area)
    }

    /** Штрихи — в отдельном слое: ластик стирает только их, а не фото под ними. */
    fun drawStrokes(canvas: Canvas, strokes: List<BrushStroke>, area: RectF) {
        if (strokes.isEmpty()) return
        val minSide = min(area.width(), area.height())
        val layer = canvas.saveLayer(area, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            paint.strokeWidth = stroke.width * minSide
            paint.color = stroke.color
            paint.xfermode = if (stroke.eraser) CLEAR else null
            path.reset()
            val first = stroke.points.first()
            path.moveTo(area.left + first.x * area.width(), area.top + first.y * area.height())
            if (stroke.points.size == 1) {
                // Точка от тапа: короткий отрезок с круглыми концами
                path.lineTo(area.left + first.x * area.width() + 0.1f, area.top + first.y * area.height())
            }
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                path.lineTo(area.left + p.x * area.width(), area.top + p.y * area.height())
            }
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(layer)
    }

    fun drawTexts(canvas: Canvas, texts: List<TextItem>, area: RectF) {
        if (texts.isEmpty()) return
        val minSide = min(area.width(), area.height())
        val paint = textPaint()
        for (item in texts) {
            paint.textSize = item.size * minSide
            paint.color = item.color
            // Тень — чтобы белый текст читался на светлом фоне
            paint.setShadowLayer(paint.textSize * 0.08f, 0f, 0f, SHADOW)
            val lines = item.text.lines()
            val lineHeight = paint.fontSpacing
            canvas.save()
            canvas.translate(area.left + item.center.x * area.width(), area.top + item.center.y * area.height())
            canvas.rotate(item.rotation)
            // Блок строк по центру точки
            val metrics = paint.fontMetrics
            var y = -lineHeight * (lines.size - 1) / 2 - (metrics.ascent + metrics.descent) / 2
            for (line in lines) {
                canvas.drawText(line, 0f, y, paint)
                y += lineHeight
            }
            canvas.restore()
        }
    }

    /** Половина ширины и высоты надписи в пикселях при стороне [minSide] — для выбора тапом и рамки. */
    fun textHalfSize(item: TextItem, minSide: Float): Pair<Float, Float> {
        val paint = textPaint().apply { textSize = item.size * minSide }
        val lines = item.text.lines()
        val width = lines.maxOf { paint.measureText(it) }
        return width / 2 to paint.fontSpacing * lines.size / 2
    }

    private fun textPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@OverlayPainter.typeface
        textAlign = Paint.Align.CENTER
    }

    private val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private const val SHADOW = 0x66000000
}
