package io.github.iufb.reach.fonts

/**
 * Как iOS рисует SF Pro (#253): начертания, оптический размер и трекинг по кеглю.
 * Кегль — в pt iOS, у нас это sp той же величины (шкала Dynamic Type в `Type.kt`).
 */
object SfProMetrics {

    /**
     * Значение оси `wght` для веса начертания. В `SFNS.ttf` именованные экземпляры Medium и Semibold
     * лежат на 510 и 590, а не на 500 и 600; Regular — 400, Bold — 700.
     */
    fun weightAxis(weight: Int): Int = when (weight) {
        500 -> 510
        600 -> 590
        else -> weight
    }

    /**
     * Значение оси `opsz`. В SFNS ось в pt, как её выставляет CoreText: opsz = кегль.
     * Диапазон 17…96 (по умолчанию 28); `avar` переводит Text в Display между 19 и 24 pt,
     * всё мельче 17 pt — дизайн SF Text, как и на iOS. Без явного значения Android взял бы 28 — Display на всех кеглях.
     */
    fun opticalSize(size: Int): Int = size.coerceIn(OPSZ_MIN, OPSZ_MAX)

    /**
     * Трекинг в pt по таблице Apple HIG (Typography → Tracking values, SF Pro, iOS).
     * Те же значения даёт таблица `trak` (трек 0) самого SFNS: трекинг × кегль / 2048.
     */
    fun tracking(size: Int): Double = TRACKING[size] ?: error("no HIG tracking for $size pt")

    /** Трекинг в em — для `letterSpacing`, чтобы он шёл за кеглем. */
    fun trackingEm(size: Int): Double = tracking(size) / size

    private const val OPSZ_MIN = 17
    private const val OPSZ_MAX = 96

    private val TRACKING = mapOf(
        6 to 0.24,
        7 to 0.23,
        8 to 0.21,
        9 to 0.17,
        10 to 0.12,
        11 to 0.06,
        12 to 0.0,
        13 to -0.08,
        14 to -0.15,
        15 to -0.23,
        16 to -0.31,
        17 to -0.43,
        18 to -0.44,
        19 to -0.45,
        20 to -0.45,
        21 to -0.36,
        22 to -0.26,
        23 to -0.10,
        24 to 0.07,
        25 to 0.15,
        26 to 0.22,
        27 to 0.29,
        28 to 0.38,
        32 to 0.41,
        34 to 0.40,
        36 to 0.37,
    )
}
