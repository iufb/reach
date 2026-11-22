package io.github.iufb.reach.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.RoundCheck
import io.github.iufb.reach.ui.components.SheetHeader
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.users.User

/**
 * Выбор нескольких контактов с поиском по имени и username: создание группы, добавление участников.
 * [disabled] — уже участники: видны, но не выбираются.
 */
@Composable
fun ContactPicker(
    contacts: List<User>,
    selected: Set<Long>,
    onToggle: (User) -> Unit,
    modifier: Modifier = Modifier,
    disabled: Set<Long> = emptySet(),
    contentPadding: PaddingValues = PaddingValues(),
    /** Круглые отметки слева; `false` — выбор одним тапом, без отметок. */
    checks: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) {
        contacts
    } else {
        val q = query.trim().lowercase()
        contacts.filter { it.fullName.lowercase().contains(q) || it.username.lowercase().contains(q.removePrefix("@")) }
    }
    Column(modifier) {
        SearchField(
            query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = stringResource(R.string.contacts_search),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = contentPadding) {
            items(shown, key = { it.id }) { user ->
                val locked = user.id in disabled
                UserRow(
                    user,
                    onClick = if (locked) null else ({ onToggle(user) }),
                    subtitle = if (locked) stringResource(R.string.picker_already_member) else null,
                    leading = if (checks) ({ RoundCheck(selected = locked || user.id in selected, enabled = !locked) }) else null,
                    divider = user != shown.last(),
                )
            }
        }
    }
}

/**
 * Шторка выбора контактов во весь экран (добавить участников, заблокировать): сверху «Отмена»,
 * заголовок и [confirm] справа; ниже — [ContactPicker]. [confirm] `null` — выбор одним тапом
 * (тогда [onToggle] сразу выполняет действие).
 */
@Composable
fun ContactPickerSheet(
    title: String,
    contacts: List<User>,
    selected: Set<Long>,
    onToggle: (User) -> Unit,
    onDismiss: () -> Unit,
    confirm: Pair<String, () -> Unit>? = null,
    disabled: Set<Long> = emptySet(),
) {
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            SheetHeader(
                title = title,
                cancel = stringResource(R.string.cancel),
                onCancel = onDismiss,
                action = confirm?.first,
                actionEnabled = selected.isNotEmpty(),
                onAction = { confirm?.second?.invoke() },
            )
            ContactPicker(
                contacts,
                selected,
                onToggle,
                Modifier.weight(1f),
                disabled = disabled,
                checks = confirm != null,
            )
        }
    }
}
