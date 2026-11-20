package io.github.iufb.reach.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.reachBarColors
import javax.inject.Inject

@HiltViewModel
class ContactSyncViewModel @Inject constructor(private val sync: ContactSync) : ViewModel() {
    val state = sync.state
    val hasPermission: Boolean get() = sync.hasPermission
    fun enable() = sync.enable()
    fun disable() = sync.disable()
}

/** Синхронизация с телефонной книгой: что делает, переключатель, итог. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSyncScreen(onBack: () -> Unit, viewModel: ContactSyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enable() else denied = true
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.contact_sync_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.contact_sync_explain), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(state.enabled, role = Role.Switch) { on ->
                        when {
                            !on -> viewModel.disable()
                            viewModel.hasPermission -> viewModel.enable()
                            else -> {
                                denied = false
                                permission.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.contact_sync_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.enabled, onCheckedChange = null)
            }
            state.lastCount?.takeIf { state.enabled }?.let { count ->
                Text(
                    pluralStringResource(R.plurals.contact_sync_done, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (denied) {
                Text(
                    stringResource(R.string.contact_sync_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
