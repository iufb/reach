package io.github.iufb.reach.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme
import javax.inject.Inject

@HiltViewModel
class ContactSyncViewModel @Inject constructor(private val sync: ContactSync) : ViewModel() {
    val state = sync.state
    val hasPermission: Boolean get() = sync.hasPermission
    fun enable() = sync.enable()
    fun disable() = sync.disable()
}

/** Синхронизация с телефонной книгой: карточка с переключателем, пояснение и итог — подписями под ней. */
@Composable
fun ContactSyncScreen(onBack: () -> Unit, viewModel: ContactSyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enable() else denied = true
    }
    val colors = ReachTheme.colors
    val toggle = { on: Boolean ->
        when {
            !on -> viewModel.disable()
            viewModel.hasPermission -> viewModel.enable()
            else -> {
                denied = false
                permission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight)
                    .navigationBarsPadding(),
            ) {
                val count = state.lastCount?.takeIf { state.enabled }
                GroupedSection(
                    footer = listOfNotNull(
                        stringResource(R.string.contact_sync_explain),
                        count?.let { pluralStringResource(R.plurals.contact_sync_done, it, it) },
                    ).joinToString("\n\n"),
                ) {
                    GroupedCell(
                        stringResource(R.string.contact_sync_switch),
                        modifier = Modifier.toggleable(state.enabled, role = Role.Switch, onValueChange = toggle),
                        chevron = false,
                        divider = false,
                        trailing = { Switch(checked = state.enabled, onCheckedChange = null) },
                    )
                }
                if (denied) {
                    Text(
                        stringResource(R.string.contact_sync_denied),
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.destructive,
                    )
                }
            }
            ReachTopBar(
                title = stringResource(R.string.contact_sync_title),
                navigation = { BackPill(onBack) },
                background = colors.groupedBackground,
            )
        }
    }
}
