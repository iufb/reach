package io.github.iufb.reach.photoedit

import android.graphics.ColorMatrix

/** Готовые фильтры: матрица цвета до ручной коррекции. */
enum class FilterPreset { None, Mono, Noir, Sepia, Warm, Cool, Vintage, Vivid }

/** Ручная коррекция, каждая в −1..1; 0 — без изменений. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isNeutral: Boolean get() = this == Adjustments()
}

/**
 * Матрица цвета для фильтра и коррекции — одна для предпросмотра (Compose `ColorFilter`) и итога
 * (`ColorMatrixColorFilter`). `null` — цвет не меняется.
 */
fun colorMatrixFor(filter: FilterPreset, adjust: Adjustments): ColorMatrix? {
    if (filter == FilterPreset.None && adjust.isNeutral) return null
    val m = ColorMatrix()
    when (filter) {
        FilterPreset.None -> Unit
        FilterPreset.Mono -> m.setSaturation(0f)
        FilterPreset.Noir -> {
            m.setSaturation(0f)
            m.postConcat(contrast(1.35f))
        }
        FilterPreset.Sepia -> m.set(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        FilterPreset.Warm -> m.postConcat(warmth(0.6f))
        FilterPreset.Cool -> m.postConcat(warmth(-0.6f))
        FilterPreset.Vintage -> {
            m.setSaturation(0.6f)
            m.postConcat(contrast(0.85f))
            m.postConcat(warmth(0.5f))
            m.postConcat(brightness(0.05f))
        }
        FilterPreset.Vivid -> {
            m.setSaturation(1.4f)
            m.postConcat(contrast(1.1f))
        }
    }
    if (adjust.saturation != 0f) m.postConcat(ColorMatrix().apply { setSaturation(1f + adjust.saturation) })
    if (adjust.contrast != 0f) m.postConcat(contrast(1f + adjust.contrast * 0.6f))
    if (adjust.warmth != 0f) m.postConcat(warmth(adjust.warmth))
    if (adjust.brightness != 0f) m.postConcat(brightness(adjust.brightness * 0.35f))
    return m
}

/** Растяжение относительно середины: 1 — без изменений. */
private fun contrast(factor: Float): ColorMatrix {
    val t = 128f * (1f - factor)
    return ColorMatrix(
        floatArrayOf(
            factor, 0f, 0f, 0f, t,
            0f, factor, 0f, 0f, t,
            0f, 0f, factor, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Сдвиг яркости на долю полного диапазона. */
private fun brightness(amount: Float): ColorMatrix {
    val t = 255f * amount
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, t,
            0f, 1f, 0f, 0f, t,
            0f, 0f, 1f, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Тепло: больше красного, меньше синего; отрицательное — холоднее. */
private fun warmth(amount: Float): ColorMatrix {
    val shift = 30f * amount
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, shift,
            0f, 1f, 0f, 0f, shift * 0.3f,
            0f, 0f, 1f, 0f, -shift,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
