package io.github.iufb.reach.fonts

import org.junit.Assert.assertEquals
import org.junit.Test

class SfProMetricsTest {

    /** Кегли шкалы iOS из `reachTypography`. */
    private val scale = listOf(34, 28, 22, 20, 17, 16, 15, 13, 12, 11)

    @Test fun higTrackingForScale() {
        val hig = mapOf(
            34 to 0.40, 28 to 0.38, 22 to -0.26, 20 to -0.45, 17 to -0.43,
            16 to -0.31, 15 to -0.23, 13 to -0.08, 12 to 0.0, 11 to 0.06,
        )
        scale.forEach { assertEquals("$it pt", hig.getValue(it), SfProMetrics.tracking(it), 1e-9) }
    }

    /** Таблица HIG совпадает с `trak` (трек 0) из SFNS.ttf: единицы × кегль / 2048, с точностью до округления. */
    @Test fun higMatchesTrakTable() {
        val trak = mapOf(11 to 12, 12 to 0, 13 to -12, 15 to -32, 16 to -40, 17 to -52, 20 to -46, 22 to -24, 28 to 28)
        trak.forEach { (size, units) ->
            assertEquals("$size pt", units * size / 2048.0, SfProMetrics.tracking(size), 0.006)
        }
    }

    @Test fun trackingEmFollowsSize() =
        assertEquals(-0.43 / 17, SfProMetrics.trackingEm(17), 1e-12)

    @Test fun opticalSizeClampsToAxis() {
        assertEquals(17, SfProMetrics.opticalSize(11))
        assertEquals(17, SfProMetrics.opticalSize(17))
        assertEquals(20, SfProMetrics.opticalSize(20))
        assertEquals(34, SfProMetrics.opticalSize(34))
        assertEquals(96, SfProMetrics.opticalSize(120))
        // Всё ≤ 17 — один Typeface: 17, 20, 22, 28, 34
        assertEquals(5, scale.map(SfProMetrics::opticalSize).distinct().size)
    }

    @Test fun weightsMatchSfnsInstances() {
        assertEquals(listOf(400, 510, 590, 700), listOf(400, 500, 600, 700).map(SfProMetrics::weightAxis))
    }
}
