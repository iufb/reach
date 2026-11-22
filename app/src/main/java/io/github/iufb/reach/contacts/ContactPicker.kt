package io.github.iufb.reach.contacts

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
                    leading = { RoundCheck(selected = locked || user.id in selected, enabled = !locked) },
                    divider = user != shown.last(),
                )
            }
        }
    }
}
