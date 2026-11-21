package io.github.iufb.reach.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.R

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

/** Межбуквенный интервал: Inter шире SF, на крупных кеглях iOS ещё и сжимает трекинг. */
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = -0.01) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/**
 * Шкала iOS (Dynamic Type, размер по умолчанию), разложенная по слотам Material:
 * Large Title 34 · Title1 28 · Title2 22 · Title3 20 · Headline 17 semibold · Body 17 ·
 * Callout 16 · Subhead 15 · Footnote 13 · Caption1 12 · Caption2 11.
 */
val ReachTypography = Typography(
    displayLarge = style(34, 41, FontWeight.Bold, tracking = -0.025),
    displayMedium = style(34, 41, FontWeight.Bold, tracking = -0.025),
    displaySmall = style(28, 34, FontWeight.Bold, tracking = -0.02),
    headlineLarge = style(28, 34, FontWeight.Bold, tracking = -0.02),
    headlineMedium = style(22, 28, FontWeight.Bold, tracking = -0.015),
    headlineSmall = style(20, 25, FontWeight.SemiBold, tracking = -0.015),
    // titleLarge — заголовок в шапке (iOS: 17 semibold по центру)
    titleLarge = style(17, 22, FontWeight.SemiBold, tracking = -0.02),
    titleMedium = style(17, 22, FontWeight.Medium, tracking = -0.02),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(17, 22, tracking = -0.02),
    bodyMedium = style(15, 20),
    bodySmall = style(13, 18),
    labelLarge = style(16, 21, FontWeight.Medium, tracking = -0.015),
    labelMedium = style(13, 18),
    labelSmall = style(11, 13, tracking = 0.0),
)
