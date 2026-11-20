package io.github.iufb.reach.rights

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.ui.theme.reachBarColors
import io.github.iufb.reach.users.User
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlagsScaffold(
    title: String,
    user: User?,
    busy: Boolean,
    loaded: Boolean,
    events: SharedFlow<String?>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(events) { events.collect { if (it == null) back() else snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(title) },
                actions = { TextButton(enabled = loaded && !busy, onClick = onSave) { Text(stringResource(R.string.save)) } },
            )
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            user?.let { UserRow(it, onClick = null) }
            content()
        }
    }
}

@Composable
private fun FlagRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Права админа: флаги по типу чата, титул (группы), снятие с должности. */
@Composable
fun AdminRightsScreen(onBack: () -> Unit, viewModel: AdminRightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    FlagsScaffold(
        stringResource(R.string.member_edit_admin), user, state.busy, state.loaded, viewModel.events, onBack, viewModel::save,
    ) {
        Text(
            stringResource(R.string.rights_admin_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        state.shown.forEach { i ->
            FlagRow(stringResource(ADMIN_FLAGS[i].label), state.values[i] == true, enabled = i in state.editable) { viewModel.toggle(i, it) }
        }
        if (!state.isChannel) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                label = { Text(stringResource(R.string.rights_custom_title)) },
                singleLine = true,
            )
        }
        if (state.existing) {
            HorizontalDivider()
            TextButton(onClick = viewModel::dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(stringResource(R.string.rights_dismiss_admin), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Разрешения всех участников группы или ограничение одного участника со сроком. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, viewModel: PermissionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    FlagsScaffold(
        stringResource(if (viewModel.isDefault) R.string.permissions_title else R.string.member_restrict),
        if (viewModel.isDefault) null else user,
        state.busy, state.loaded, viewModel.events, onBack, viewModel::save,
    ) {
        Text(
            stringResource(if (viewModel.isDefault) R.string.permissions_hint else R.string.restrict_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        state.shown.forEach { i ->
            FlagRow(stringResource(PERMISSION_FLAGS[i].label), state.values[i] == true, enabled = i in state.editable) { viewModel.toggle(i, it) }
        }
        if (!viewModel.isDefault) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.restrict_duration),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val now = (System.currentTimeMillis() / 1000).toInt()
            RESTRICT_DURATIONS.forEach { (seconds, label) ->
                // Выбранный срок узнаём по дате окончания с допуском
                val selected = if (seconds == 0) state.untilDate == 0 else state.untilDate != 0 &&
                    kotlin.math.abs(state.untilDate - now - seconds) < seconds / 2
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected, role = Role.RadioButton) { viewModel.setDuration(seconds) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(stringResource(label), Modifier.padding(start = 12.dp))
                }
            }
            if (state.existing) {
                HorizontalDivider()
                TextButton(onClick = viewModel::lift, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(stringResource(R.string.restrict_lift), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private val RESTRICT_DURATIONS = listOf(
    0 to R.string.restrict_forever,
    86_400 to R.string.restrict_day,
    7 * 86_400 to R.string.restrict_week,
    31 * 86_400 to R.string.restrict_month,
)
