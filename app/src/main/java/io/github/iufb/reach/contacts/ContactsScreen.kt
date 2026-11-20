package io.github.iufb.reach.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.reachBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit, viewModel: ContactsViewModel = hiltViewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val notFoundText = stringResource(R.string.contacts_not_found)
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.notFound.collect { snackbar.showSnackbar(notFoundText) } }
    val open by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { open(it) } }
    var adding by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.contacts_title)) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.contacts_search)) },
                singleLine = true,
            )
            val current = list
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (query.isBlank()) R.string.contacts_empty else R.string.contacts_nothing_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(current, key = { it.id }) { user -> UserRow(user, onClick = { viewModel.open(user) }) }
                }
            }
        }
    }
    if (adding) {
        AddContactDialog(
            onAdd = { phone, first, last ->
                adding = false
                viewModel.addByPhone(phone, first, last)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AddContactDialog(onAdd: (phone: String, first: String, last: String) -> Unit, onDismiss: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("+") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.contacts_phone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                OutlinedTextField(value = first, onValueChange = { first = it }, label = { Text(stringResource(R.string.contacts_first_name)) }, singleLine = true)
                OutlinedTextField(value = last, onValueChange = { last = it }, label = { Text(stringResource(R.string.contacts_last_name)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = phone.count { it.isDigit() } >= MIN_PHONE_DIGITS && first.isNotBlank(),
                onClick = { onAdd(phone, first, last) },
            ) { Text(stringResource(R.string.contacts_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MIN_PHONE_DIGITS = 5

/** Имя и фамилия контакта: добавление из профиля или переименование. */
@Composable
fun ContactNameDialog(
    title: String,
    initialFirst: String,
    initialLast: String,
    onSave: (first: String, last: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf(initialFirst) }
    var last by rememberSaveable { mutableStateOf(initialLast) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = first, onValueChange = { first = it }, label = { Text(stringResource(R.string.contacts_first_name)) }, singleLine = true)
                OutlinedTextField(value = last, onValueChange = { last = it }, label = { Text(stringResource(R.string.contacts_last_name)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = first.isNotBlank(), onClick = { onSave(first, last) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
