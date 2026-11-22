package io.github.iufb.reach.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.R
import io.github.iufb.reach.fonts.CustomTextFont
import io.github.iufb.reach.fonts.SfProMetrics

/**
 * Inter вместо SF Pro (ADR-0010): один вариативный файл, начертания — через ось `wght`.
 * Лицензия — SIL OFL, `assets/licenses/inter-OFL.txt`.
 */
private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

/**
 * Начертание своего шрифта (ADR-0011) для Compose: готовый android-Typeface с осями и emoji-fallback.
 * `Font(file)` из Compose не годится — он собирает Typeface с системным fallback и теряет emoji-шрифт.
 */
@OptIn(ExperimentalTextApi::class)
private class LoadedFont(
    override val weight: FontWeight,
    val typeface: android.graphics.Typeface,
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont) = (font as LoadedFont).typeface
        override suspend fun awaitLoad(context: Context, font: AndroidFont) = loadBlocking(context, font)
    }
}

/** Семейства своего шрифта по кеглю: у SF своё на каждое значение `opsz`, у прочих одно на все кегли. */
fun customFamilies(font: CustomTextFont): (size: Int) -> FontFamily {
    val families = HashMap<Int, FontFamily>()
    return { size ->
        families.getOrPut(font.opticalKey(size)) {
            FontFamily(CustomTextFont.WEIGHTS.map { LoadedFont(FontWeight(it), font.typeface(size, it)) })
        }
    }
}

/**
 * Шкала iOS (Dynamic Type, размер по умолчанию), разложенная по слотам Material:
 * Large Title 34 · Title1 28 · Title2 22 · Title3 20 · Headline 17 semibold · Body 17 ·
 * Callout 16 · Subhead 15 · Footnote 13 · Caption1 12 · Caption2 11.
 *
 * [family] — семейство для кегля (у SF Pro оптический размер зависит от кегля). [isSf] — трекинг iOS
 * по таблице HIG ([SfProMetrics.tracking]); иначе — подобранный под Inter: Inter шире SF,
 * на крупных кеглях iOS ещё и сжимает трекинг.
 */
fun reachTypography(family: (size: Int) -> FontFamily, isSf: Boolean): Typography {
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, interTracking: Double = -0.01) = TextStyle(
        fontFamily = family(size),
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = (if (isSf) SfProMetrics.trackingEm(size) else interTracking).em,
    )
    return Typography(
        displayLarge = style(34, 41, FontWeight.Bold, interTracking = -0.025),
        displayMedium = style(34, 41, FontWeight.Bold, interTracking = -0.025),
        displaySmall = style(28, 34, FontWeight.Bold, interTracking = -0.02),
        headlineLarge = style(28, 34, FontWeight.Bold, interTracking = -0.02),
        headlineMedium = style(22, 28, FontWeight.Bold, interTracking = -0.015),
        headlineSmall = style(20, 25, FontWeight.SemiBold, interTracking = -0.015),
        // titleLarge — заголовок в шапке (iOS: 17 semibold по центру)
        titleLarge = style(17, 22, FontWeight.SemiBold, interTracking = -0.02),
        titleMedium = style(17, 22, FontWeight.Medium, interTracking = -0.02),
        titleSmall = style(15, 20, FontWeight.SemiBold),
        bodyLarge = style(17, 22, interTracking = -0.02),
        bodyMedium = style(15, 20),
        bodySmall = style(13, 18),
        labelLarge = style(16, 21, FontWeight.Medium, interTracking = -0.015),
        labelMedium = style(13, 18),
        labelSmall = style(11, 13, interTracking = 0.0),
    )
}

/** Типографика на Inter — без своего шрифта. */
val InterTypography = reachTypography({ Inter }, isSf = false)

/** Типографика на своём шрифте: SF Pro с метриками iOS, прочие — с трекингом Inter. */
fun reachTypography(font: CustomTextFont?): Typography =
    if (font == null) InterTypography else reachTypography(customFamilies(font), font.isSfPro)
