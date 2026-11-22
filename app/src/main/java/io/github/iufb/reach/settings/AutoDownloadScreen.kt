package io.github.iufb.reach.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.media.AutoDownload
import io.github.iufb.reach.media.NetworkKind
import io.github.iufb.reach.media.NetworkRules
import io.github.iufb.reach.media.NetworkRules.Companion.MB
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SegmentedControl
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.StateFlow
import java.text.NumberFormat
import javax.inject.Inject

@HiltViewModel
class AutoDownloadViewModel @Inject constructor(private val autoDownload: AutoDownload) : ViewModel() {
    val rules: StateFlow<Map<NetworkKind, NetworkRules>> = autoDownload.rules

    fun update(network: NetworkKind, rules: NetworkRules) = autoDownload.update(network, rules)
}

/** Какой лимит сейчас выбирается в листе. */
private enum class LimitKind { Photo, Video, File }

/**
 * Автозагрузка медиа: сеть выбирается сегментом сверху, ниже — карточки «вкл/выкл» и типов медиа.
 * У фото, видео и файлов под названием — лимит размера, тап по строке открывает лист с вариантами.
 */
@Composable
fun AutoDownloadScreen(onBack: () -> Unit, viewModel: AutoDownloadViewModel = hiltViewModel()) {
    val all by viewModel.rules.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(NetworkKind.Mobile) }
    var choosing by rememberSaveable { mutableStateOf<LimitKind?>(null) }
    val colors = ReachTheme.colors
    val rules = all.getValue(tab)
    val update = { changed: NetworkRules -> viewModel.update(tab, changed) }
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
                SegmentedControl(
                    items = NetworkKind.entries.map { stringResource(it.label()) },
                    selected = tab.ordinal,
                    onSelect = { tab = NetworkKind.entries[it] },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                GroupedSection {
                    SwitchCell(R.string.autodownload_enabled, rules.enabled, divider = false) { update(rules.copy(enabled = it)) }
                }
                GroupedSection(
                    header = stringResource(R.string.autodownload_types).uppercase(),
                    footer = stringResource(R.string.autodownload_hint),
                    modifier = Modifier.alpha(if (rules.enabled) 1f else DISABLED_ALPHA),
                ) {
                    LimitCell(R.string.autodownload_photos, rules.enabled, rules.maxPhoto, NetworkRules.DEFAULT_PHOTO, { choosing = LimitKind.Photo }) {
                        update(rules.copy(maxPhoto = it))
                    }
                    LimitCell(R.string.autodownload_videos, rules.enabled, rules.maxVideo, NetworkRules.DEFAULT_VIDEO, { choosing = LimitKind.Video }) {
                        update(rules.copy(maxVideo = it))
                    }
                    LimitCell(R.string.autodownload_files, rules.enabled, rules.maxFile, NetworkRules.DEFAULT_FILE, { choosing = LimitKind.File }) {
                        update(rules.copy(maxFile = it))
                    }
                    SwitchCell(R.string.autodownload_gifs, rules.gifs, rules.enabled) { update(rules.copy(gifs = it)) }
                    SwitchCell(R.string.autodownload_voice, rules.voice, rules.enabled) { update(rules.copy(voice = it)) }
                    SwitchCell(R.string.autodownload_stickers, rules.stickers, rules.enabled, divider = false) { update(rules.copy(stickers = it)) }
                }
            }
            ReachTopBar(
                title = stringResource(R.string.autodownload_title),
                navigation = { BackPill(onBack) },
                background = colors.groupedBackground,
            )
        }
    }
    choosing?.let { kind ->
        val options = if (kind == LimitKind.Photo) PHOTO_LIMITS else LIMITS
        ActionSheet(
            title = stringResource(R.string.autodownload_limit_title),
            actions = options.map { option ->
                val current = when (kind) {
                    LimitKind.Photo -> rules.maxPhoto
                    LimitKind.Video -> rules.maxVideo
                    LimitKind.File -> rules.maxFile
                }
                SheetAction(sizeLabel(option), bold = option == current) {
                    update(
                        when (kind) {
                            LimitKind.Photo -> rules.copy(maxPhoto = option)
                            LimitKind.Video -> rules.copy(maxVideo = option)
                            LimitKind.File -> rules.copy(maxFile = option)
                        },
                    )
                }
            },
            onDismiss = { choosing = null },
        )
    }
}

@Composable
private fun SwitchCell(@StringRes label: Int, checked: Boolean, enabled: Boolean = true, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    GroupedCell(
        stringResource(label),
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        chevron = false,
        divider = divider,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

/** Вид медиа: переключатель (лимит 0 — выключено), под названием «до N МБ»; тап по строке — выбрать лимит. */
@Composable
private fun LimitCell(
    @StringRes label: Int,
    enabled: Boolean,
    limit: Long,
    default: Long,
    onChooseLimit: () -> Unit,
    onChange: (Long) -> Unit,
) {
    val on = limit > 0
    GroupedCell(
        stringResource(label),
        onClick = if (enabled && on) onChooseLimit else null,
        subtitle = if (on) stringResource(R.string.autodownload_up_to, sizeLabel(limit)) else null,
        chevron = false,
        trailing = { Switch(checked = on, enabled = enabled, onCheckedChange = { onChange(if (it) default else 0) }) },
    )
}

/** Лимит в двоичных мегабайтах, как в Telegram: «1 МБ», «500 МБ», «1,5 ГБ». */
@Composable
private fun sizeLabel(bytes: Long): String {
    val mb = bytes / MB
    if (mb < 1024) return stringResource(R.string.size_mb, mb.toString())
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
    return stringResource(R.string.size_gb, format.format(mb / 1024.0))
}

@StringRes
private fun NetworkKind.label(): Int = when (this) {
    NetworkKind.Mobile -> R.string.autodownload_mobile
    NetworkKind.WiFi -> R.string.autodownload_wifi
    NetworkKind.Roaming -> R.string.autodownload_roaming
}

private val PHOTO_LIMITS = listOf(1 * MB, 2 * MB, 5 * MB, 10 * MB)
private const val DISABLED_ALPHA = 0.5f
private val LIMITS = listOf(1 * MB, 5 * MB, 10 * MB, 50 * MB, 100 * MB, 500 * MB, 1500 * MB)
