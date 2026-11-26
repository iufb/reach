package io.github.iufb.reach.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.format.AppLanguage
import io.github.iufb.reach.ui.format.AppLanguages
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * «Настройки → Язык» (#405), как в Telegram iOS: название языка на нём самом, под ним — на текущем
 * языке интерфейса, отмечен выбранный. Выбор сразу пересоздаёт экран на новом языке.
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Галочка переезжает, когда язык применён (#430)
    val current = remember(AppLanguages.revision.intValue) { AppLanguages.current(context) }
    val colors = ReachTheme.colors
    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                GroupedSection {
                    AppLanguage.entries.forEachIndexed { index, language ->
                        val name = languageName(language)
                        GroupedCell(
                            name,
                            onClick = { if (language != current && activity != null) AppLanguages.set(activity, language) },
                            // «English / English», «Русский / Русский» — подпись не нужна
                            subtitle = languageSubtitle(language).takeIf { it != name },
                            chevron = false,
                            divider = index < AppLanguage.entries.lastIndex,
                            trailing = {
                                if (language == current) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = colors.accent,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            ReachTopBar(
                title = stringResource(R.string.language_title),
                navigation = { BackPill(onBack) },
                background = colors.groupedBackground,
            )
        }
    }
}

/** Название языка на нём самом — его узнают, даже не понимая текущий язык интерфейса. */
@Composable
internal fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.language_system)
    AppLanguage.English -> stringResource(R.string.language_name_en)
    AppLanguage.Russian -> stringResource(R.string.language_name_ru)
}

@Composable
private fun languageSubtitle(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.language_system_hint)
    AppLanguage.English -> stringResource(R.string.language_english)
    AppLanguage.Russian -> stringResource(R.string.language_russian)
}
