package io.github.iufb.reach.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Цвета в терминах Telegram iOS (ADR-0010). Material-схема ниже выводится из них, чтобы стандартные
 * компоненты сразу выглядели как надо; новые экраны берут цвета отсюда через [ReachTheme.colors].
 *
 * Светлая тема снята с экранов Telegram iOS в Mobbin; тёмной там нет — это системная тёмная палитра iOS
 * с ночным акцентом Telegram.
 */
@Immutable
data class ReachColors(
    /** Акцент: кнопки, ссылки, выбранная вкладка, переключатели. */
    val accent: Color,
    val onAccent: Color,
    /** Основной текст. */
    val label: Color,
    /** Вторичный текст: превью сообщений, подписи, значения в ячейках. */
    val secondaryLabel: Color,
    /** Неактивный текст и плейсхолдеры. */
    val tertiaryLabel: Color,
    /** Фон обычных экранов и списков (список чатов). */
    val background: Color,
    /** Фон под сгруппированными списками (настройки, профиль). */
    val groupedBackground: Color,
    /** Ячейка сгруппированного списка, лист action sheet. */
    val cell: Color,
    /** Подсветка нажатой или выбранной строки. */
    val cellSelected: Color,
    /** Разделитель строк. */
    val separator: Color,
    /** Заливка поля поиска, пилюль на светлом фоне. */
    val fill: Color,
    /** Подложка шапки и tab bar под размытием. */
    val bar: Color,
    /** Удаление, выход, ошибки. */
    val destructive: Color,
    /** Прочитано, «в сети». */
    val positive: Color,
    val bubbleIncoming: Color,
    val bubbleOutgoing: Color,
    /** Время и галочки во входящем пузыре. */
    val bubbleIncomingMeta: Color,
    /** Время и галочки в исходящем пузыре. */
    val bubbleOutgoingMeta: Color,
    /** Обои чата — градиент сверху вниз. */
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
    /** Подложка служебных плашек на обоях (дата, «Непрочитанные»). */
    val serviceBubble: Color,
    val onServiceBubble: Color,
    val isDark: Boolean,
)

val DayColors = ReachColors(
    accent = Color(0xFF0088FF),
    onAccent = Color.White,
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF8E8E93),
    tertiaryLabel = Color(0xFFC4C4C7),
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF0F0F4),
    cell = Color(0xFFFFFFFF),
    cellSelected = Color(0xFFE7F0F9),
    separator = Color(0xFFDCDCE0),
    fill = Color(0xFFEEEEEF),
    bar = Color(0xF2F9F9F9),
    destructive = Color(0xFFFF3B30),
    positive = Color(0xFF21C004),
    bubbleIncoming = Color(0xFFFFFFFF),
    bubbleOutgoing = Color(0xFFE1FFC7),
    bubbleIncomingMeta = Color(0xFF8E8E93),
    bubbleOutgoingMeta = Color(0xFF4AAB3A),
    wallpaperTop = Color(0xFFD3DCB2),
    wallpaperBottom = Color(0xFF94C38C),
    serviceBubble = Color(0x66356F3F),
    onServiceBubble = Color.White,
    isDark = false,
)

val NightColors = ReachColors(
    accent = Color(0xFF3E88F7),
    onAccent = Color.White,
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF8D8E93),
    tertiaryLabel = Color(0xFF5A5A5F),
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    cell = Color(0xFF1C1C1D),
    cellSelected = Color(0xFF2C2C2E),
    separator = Color(0xFF38383A),
    fill = Color(0xFF2C2C2E),
    bar = Color(0xF21C1C1D),
    destructive = Color(0xFFFF453A),
    positive = Color(0xFF30D158),
    bubbleIncoming = Color(0xFF262628),
    bubbleOutgoing = Color(0xFF2B5A93),
    bubbleIncomingMeta = Color(0xFF8D8E93),
    bubbleOutgoingMeta = Color(0xFFA9C6EC),
    wallpaperTop = Color(0xFF1A1A1C),
    wallpaperBottom = Color(0xFF0B0B0C),
    serviceBubble = Color(0x99333336),
    onServiceBubble = Color.White,
    isDark = true,
)

internal val LocalReachColors = staticCompositionLocalOf { DayColors }

/**
 * Material-схема из [ReachColors]: Material-компоненты (кнопки, поля, диалоги) получают iOS-цвета.
 * Привязки, на которые опирается старый код: исходящий пузырь — primaryContainer, обои — secondary/tertiaryContainer.
 */
internal fun ReachColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = bubbleOutgoing,
        onPrimaryContainer = label,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = wallpaperTop,
        onSecondaryContainer = if (isDark) label else Color(0xFF1E3A22),
        tertiary = positive,
        onTertiary = Color.White,
        tertiaryContainer = wallpaperBottom,
        onTertiaryContainer = label,
        background = background,
        onBackground = label,
        surface = background,
        onSurface = label,
        surfaceVariant = fill,
        onSurfaceVariant = secondaryLabel,
        surfaceTint = Color.Transparent,
        surfaceBright = cell,
        surfaceDim = groupedBackground,
        surfaceContainerLowest = cell,
        surfaceContainerLow = cell,
        surfaceContainer = if (isDark) cell else groupedBackground,
        surfaceContainerHigh = if (isDark) Color(0xFF242426) else cell,
        surfaceContainerHighest = fill,
        outline = secondaryLabel,
        outlineVariant = separator,
        error = destructive,
        onError = Color.White,
        errorContainer = destructive.copy(alpha = 0.12f),
        onErrorContainer = destructive,
        scrim = Color.Black,
    )
}
