package io.github.iufb.reach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

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
fun ReachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) NightColors else DayColors
    val scheme = remember(colors) { colors.toMaterial() }
    CompositionLocalProvider(LocalReachColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = ReachTypography, content = content)
    }
}
