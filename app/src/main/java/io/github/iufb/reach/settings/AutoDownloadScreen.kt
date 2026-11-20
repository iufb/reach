package io.github.iufb.reach.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import io.github.iufb.reach.ui.theme.reachBarColors
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AutoDownloadViewModel @Inject constructor(private val autoDownload: AutoDownload) : ViewModel() {
    val rules: StateFlow<Map<NetworkKind, NetworkRules>> = autoDownload.rules

    fun update(network: NetworkKind, rules: NetworkRules) = autoDownload.update(network, rules)
}

/** Настройки автозагрузки: отдельно для мобильной сети, Wi-Fi и роуминга. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDownloadScreen(onBack: () -> Unit, viewModel: AutoDownloadViewModel = hiltViewModel()) {
    val all by viewModel.rules.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(NetworkKind.Mobile) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.autodownload_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                NetworkKind.entries.forEach { network ->
                    Tab(selected = network == tab, onClick = { tab = network }, text = { Text(stringResource(network.label())) })
                }
            }
            val rules = all.getValue(tab)
            val update = { changed: NetworkRules -> viewModel.update(tab, changed) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SwitchRow(R.string.autodownload_enabled, rules.enabled) { update(rules.copy(enabled = it)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LimitRow(R.string.autodownload_photos, rules.enabled, rules.maxPhoto, PHOTO_LIMITS, NetworkRules.DEFAULT_PHOTO) {
                    update(rules.copy(maxPhoto = it))
                }
                LimitRow(R.string.autodownload_videos, rules.enabled, rules.maxVideo, LIMITS, NetworkRules.DEFAULT_VIDEO) {
                    update(rules.copy(maxVideo = it))
                }
                LimitRow(R.string.autodownload_files, rules.enabled, rules.maxFile, LIMITS, NetworkRules.DEFAULT_FILE) {
                    update(rules.copy(maxFile = it))
                }
                SwitchRow(R.string.autodownload_gifs, rules.gifs, rules.enabled) { update(rules.copy(gifs = it)) }
                SwitchRow(R.string.autodownload_voice, rules.voice, rules.enabled) { update(rules.copy(voice = it)) }
                SwitchRow(R.string.autodownload_stickers, rules.stickers, rules.enabled) { update(rules.copy(stickers = it)) }
                Text(
                    stringResource(R.string.autodownload_hint),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Вид медиа: вкл/выкл (лимит 0 — выключено) и «до N МБ». */
@Composable
private fun LimitRow(
    @StringRes label: Int,
    enabled: Boolean,
    limit: Long,
    options: List<Long>,
    default: Long,
    onChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (limit > 0) {
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled) {
                    Text(stringResource(R.string.autodownload_up_to, Formatter.formatShortFileSize(context, limit)))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(Formatter.formatShortFileSize(context, option)) },
                            onClick = {
                                menu = false
                                onChange(option)
                            },
                        )
                    }
                }
            }
        }
        Switch(checked = limit > 0, enabled = enabled, onCheckedChange = { onChange(if (it) default else 0) })
    }
}

@StringRes
private fun NetworkKind.label(): Int = when (this) {
    NetworkKind.Mobile -> R.string.autodownload_mobile
    NetworkKind.WiFi -> R.string.autodownload_wifi
    NetworkKind.Roaming -> R.string.autodownload_roaming
}

private val PHOTO_LIMITS = listOf(1 * MB, 2 * MB, 5 * MB, 10 * MB)
private val LIMITS = listOf(1 * MB, 5 * MB, 10 * MB, 50 * MB, 100 * MB, 500 * MB, 1500 * MB)
