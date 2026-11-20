package io.github.iufb.reach.photoedit

/**
 * Правки фото перед отправкой (PRODUCT.md §4.4, ADR-0009) — описание, а не пиксели: исходник
 * не меняется, «Сбросить» — это просто [PhotoEdit.None], итог собирает [PhotoEditRenderer].
 *
 * Координаты — доли (0..1) «повёрнутого» изображения: после [rotation] и [flipped], до кропа.
 * Поэтому рисунок не съезжает при смене рамки, а при повороте всё пересчитывается вместе.
 */
data class PhotoEdit(
    /** Поворот по часовой, градусы: 0, 90, 180, 270. Применяется до отражения. */
    val rotation: Int = 0,
    /** Отражение по горизонтали. */
    val flipped: Boolean = false,
    val crop: NormRect = NormRect.Full,
    /** Пропорции рамки (ширина / высота в пикселях); `null` — свободная. */
    val aspect: Float? = null,
) {
    val isEmpty: Boolean get() = this == None

    /**
     * Поворот на 90° по часовой того, что на экране: рамка поворачивается вместе с картинкой.
     * Отражение применяется после поворота, поэтому у отражённой картинки угол идёт в обратную сторону.
     */
    fun rotatedClockwise(): PhotoEdit = copy(
        rotation = (rotation + if (flipped) 270 else 90) % 360,
        crop = crop.rotatedClockwise(),
        aspect = aspect?.let { 1f / it },
    )

    fun flippedHorizontally(): PhotoEdit = copy(flipped = !flipped, crop = crop.flippedHorizontally())

    companion object {
        val None = PhotoEdit()
    }
}

/** Прямоугольник в долях изображения. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Точка (x, y) → (1 − y, x). */
    fun rotatedClockwise() = NormRect(1f - bottom, left, 1f - top, right)

    fun flippedHorizontally() = NormRect(1f - right, top, 1f - left, bottom)

    companion object {
        val Full = NormRect(0f, 0f, 1f, 1f)

        /** Самая большая рамка с пропорциями [aspect] по центру изображения [imageWidth]×[imageHeight]. */
        fun centered(aspect: Float, imageWidth: Int, imageHeight: Int): NormRect {
            val imageAspect = imageWidth.toFloat() / imageHeight
            return if (aspect > imageAspect) {
                val h = imageAspect / aspect
                NormRect(0f, (1f - h) / 2, 1f, (1f + h) / 2)
            } else {
                val w = aspect / imageAspect
                NormRect((1f - w) / 2, 0f, (1f + w) / 2, 1f)
            }
        }
    }
}
