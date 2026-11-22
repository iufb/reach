package io.github.iufb.reach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.github.iufb.reach.fonts.CustomTextFont
import io.github.iufb.reach.fonts.DeviceFonts

/** Доступ к токенам темы: `ReachTheme.colors.accent`. */
object ReachTheme {
    val colors: ReachColors
        @Composable @ReadOnlyComposable get() = LocalReachColors.current
}

/**
 * Шапка как в Telegram iOS: светлая (тёмная ночью) подложка, заголовок цветом текста, значки — акцентом.
 * Статус-бар над ней — тёмными значками днём и светлыми ночью ([enableEdgeToEdge][androidx.activity.enableEdgeToEdge]
 * выбирает их по системной теме, а шапка теперь следует той же теме).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun reachBarColors() = with(ReachTheme.colors) {
    TopAppBarDefaults.topAppBarColors(
        containerColor = bar,
        scrolledContainerColor = bar,
        titleContentColor = label,
        navigationIconContentColor = accent,
        actionIconContentColor = accent,
    )
}

@Composable
fun ReachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Свой текстовый шрифт из Настройки → Шрифт (InterfaceFonts.text), `null` — Inter. Без горячей замены.
    textFont: CustomTextFont? = null,
    // Свои шрифты из загрузчика: здесь нужен emoji-шрифт для Inter и кода (#254), `null` — стандартные
    fonts: DeviceFonts? = null,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NightColors else DayColors
    val scheme = remember(colors) { colors.toMaterial() }
    val context = LocalContext.current
    val families = remember(fonts) { DeviceFontFamilies.of(context, fonts) }
    val typography = remember(textFont, families) {
        // Свой текстовый шрифт сам несёт emoji-шрифт (CustomTextFont); Inter — через DeviceFontFamilies
        if (textFont == null && families != null) InterTypography.withFontFamily(families.text) else reachTypography(textFont)
    }
    CompositionLocalProvider(
        LocalReachColors provides colors,
        LocalMonospace provides (families?.monospace ?: FontFamily.Monospace),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
