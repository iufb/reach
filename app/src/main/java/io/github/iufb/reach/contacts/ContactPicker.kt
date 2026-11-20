package io.github.iufb.reach.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
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
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) {
        contacts
    } else {
        val q = query.trim().lowercase()
        contacts.filter { it.fullName.lowercase().contains(q) || it.username.lowercase().contains(q.removePrefix("@")) }
    }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.contacts_search)) },
            singleLine = true,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.id }) { user ->
                val locked = user.id in disabled
                UserRow(
                    user,
                    onClick = if (locked) null else ({ onToggle(user) }),
                    subtitle = if (locked) stringResource(R.string.picker_already_member) else null,
                    trailing = { Checkbox(checked = locked || user.id in selected, onCheckedChange = null, enabled = !locked) },
                )
            }
        }
    }
}
