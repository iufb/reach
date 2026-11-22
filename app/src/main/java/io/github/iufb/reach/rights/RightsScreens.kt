package io.github.iufb.reach.rights

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.theme.ReachTheme
import io.github.iufb.reach.users.User
import kotlinx.coroutines.flow.SharedFlow

/**
 * Общий каркас экранов прав (Telegram iOS): шапка с «Сохранить», карточка пользователя,
 * ниже — [content] (ленивые секции).
 */
@Composable
private fun FlagsScaffold(
    title: String,
    user: User?,
    busy: Boolean,
    loaded: Boolean,
    events: SharedFlow<String?>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(events) { events.collect { if (it == null) back() else snackbar.showSnackbar(it) } }
    ListScreen(
        title = title,
        onBack = onBack,
        snackbar = snackbar,
        loading = !loaded,
        actions = { PillTextButton(stringResource(R.string.save), onClick = onSave, bold = true, enabled = loaded && !busy) },
    ) {
        user?.let { u -> item(key = "user") { GroupedSection { UserRow(u, onClick = null) } } }
        content()
    }
}

/** Переключатели прав одной карточкой; недоступные — приглушены. */
private fun LazyListScope.flagsSection(
    key: String,
    footer: String,
    flags: List<Triple<String, Boolean, Boolean>>,
    onToggle: (Int, Boolean) -> Unit,
) {
    item(key = key) {
        GroupedSection(footer = footer) {
            flags.forEachIndexed { index, (label, checked, enabled) ->
                GroupedCell(
                    label,
                    modifier = Modifier
                        .toggleable(checked, enabled = enabled, role = Role.Switch) { onToggle(index, it) }
                        .alpha(if (enabled) 1f else DISABLED_ALPHA),
                    chevron = false,
                    divider = index < flags.lastIndex,
                    trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
                )
            }
        }
    }
}

/** Права админа: флаги по типу чата, титул (группы), снятие с должности. */
@Composable
fun AdminRightsScreen(onBack: () -> Unit, viewModel: AdminRightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val labels = state.shown.map { stringResource(ADMIN_FLAGS[it].label) }
    val hint = stringResource(R.string.rights_admin_hint)
    val titleHint = stringResource(R.string.rights_custom_title)
    val dismissLabel = stringResource(R.string.rights_dismiss_admin)
    FlagsScaffold(
        stringResource(R.string.member_edit_admin), user, state.busy, state.loaded, viewModel.events, onBack, viewModel::save,
    ) {
        flagsSection(
            "flags",
            footer = hint,
            flags = state.shown.mapIndexed { n, i -> Triple(labels[n], state.values[i] == true, i in state.editable) },
            onToggle = { n, on -> viewModel.toggle(state.shown[n], on) },
        )
        if (!state.isChannel) {
            item(key = "title") {
                GroupedSection(header = titleHint) {
                    GroupedTextField(state.title, viewModel::setTitle, placeholder = titleHint, divider = false)
                }
            }
        }
        if (state.existing) {
            item(key = "dismiss") {
                GroupedSection {
                    GroupedCell(dismissLabel, onClick = viewModel::dismiss.takeIf { !state.busy }, destructive = true, chevron = false, divider = false)
                }
            }
        }
    }
}

/** Разрешения всех участников группы или ограничение одного участника со сроком. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, viewModel: PermissionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val labels = state.shown.map { stringResource(PERMISSION_FLAGS[it].label) }
    val hint = stringResource(if (viewModel.isDefault) R.string.permissions_hint else R.string.restrict_hint)
    val durationTitle = stringResource(R.string.restrict_duration)
    val durationLabels = RESTRICT_DURATIONS.map { stringResource(it.second) }
    val liftLabel = stringResource(R.string.restrict_lift)
    FlagsScaffold(
        stringResource(if (viewModel.isDefault) R.string.permissions_title else R.string.member_restrict),
        if (viewModel.isDefault) null else user,
        state.busy, state.loaded, viewModel.events, onBack, viewModel::save,
    ) {
        flagsSection(
            "flags",
            footer = hint,
            flags = state.shown.mapIndexed { n, i -> Triple(labels[n], state.values[i] == true, i in state.editable) },
            onToggle = { n, on -> viewModel.toggle(state.shown[n], on) },
        )
        if (!viewModel.isDefault) {
            item(key = "duration") {
                val now = (System.currentTimeMillis() / 1000).toInt()
                GroupedSection(header = durationTitle) {
                    RESTRICT_DURATIONS.forEachIndexed { index, (seconds, _) ->
                        // Выбранный срок узнаём по дате окончания с допуском
                        val selected = if (seconds == 0) state.untilDate == 0 else state.untilDate != 0 &&
                            kotlin.math.abs(state.untilDate - now - seconds) < seconds / 2
                        GroupedCell(
                            durationLabels[index],
                            modifier = Modifier.selectable(selected, role = Role.RadioButton) { viewModel.setDuration(seconds) },
                            chevron = false,
                            divider = index < RESTRICT_DURATIONS.lastIndex,
                            trailing = {
                                if (selected) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = ReachTheme.colors.accent)
                                }
                            },
                        )
                    }
                }
            }
            if (state.existing) {
                item(key = "lift") {
                    GroupedSection {
                        GroupedCell(liftLabel, onClick = viewModel::lift.takeIf { !state.busy }, destructive = true, chevron = false, divider = false)
                    }
                }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.5f

private val RESTRICT_DURATIONS = listOf(
    0 to R.string.restrict_forever,
    86_400 to R.string.restrict_day,
    7 * 86_400 to R.string.restrict_week,
    31 * 86_400 to R.string.restrict_month,
)
