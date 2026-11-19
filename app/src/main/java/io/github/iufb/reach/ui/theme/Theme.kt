package io.github.iufb.reach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Day = lightColorScheme(
    primary = Color(0xFF1976A5),
    onPrimary = Color.White,
    secondary = Color(0xFF467798),
    onSecondary = Color.White,
    primaryContainer = Color(0xFFE1F6CD),
    onPrimaryContainer = Color(0xFF192A18),
    secondaryContainer = Color(0xFFD7E8DC),
    tertiaryContainer = Color(0xFFC6DCCF),
    surface = Color.White,
    background = Color.White,
    onSurface = Color(0xFF182530),
    onSurfaceVariant = Color(0xFF586A76),
    outlineVariant = Color(0xFFDFE6EA),
    surfaceVariant = Color(0xFFEAF0F3),
    surfaceContainerHighest = Color(0xFFE2EBED),
    surfaceContainerHigh = Color(0xFFF3F7F9),
    surfaceContainer = Color(0xFFF5F8FA),
)

private val Night = darkColorScheme(
    primary = Color(0xFF80C8F5),
    onPrimary = Color(0xFF082C42),
    secondary = Color(0xFF233849),
    onSecondary = Color.White,
    primaryContainer = Color(0xFF294E65),
    onPrimaryContainer = Color(0xFFEBF5FF),
    secondaryContainer = Color(0xFF172A32),
    tertiaryContainer = Color(0xFF101E2C),
    surface = Color(0xFF20303D),
    background = Color(0xFF20303D),
    onSurface = Color(0xFFEEF4F8),
    onSurfaceVariant = Color(0xFFB7C8D3),
    outlineVariant = Color(0xFF344757),
    surfaceVariant = Color(0xFF334956),
    surfaceContainerHighest = Color(0xFF334957),
    surfaceContainerHigh = Color(0xFF2A3B49),
    surfaceContainer = Color(0xFF283946),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun reachBarColors() = with(MaterialTheme.colorScheme) {
    TopAppBarDefaults.topAppBarColors(
        containerColor = secondary,
        titleContentColor = onSecondary,
        navigationIconContentColor = onSecondary,
        actionIconContentColor = onSecondary,
    )
}

@Composable
fun ReachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Night else Day, content = content)
}
