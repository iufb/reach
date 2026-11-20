package io.github.iufb.reach.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.UsernameStatus
import io.github.iufb.reach.contacts.ContactNameDialog
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.reachBarColors

/** Какой пункт профиля сейчас правится. */
private enum class Editing { Name, Bio, Username }

/** Настройки: свой профиль сверху, ниже — разделы. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAutoDownload: () -> Unit,
    onOpenBlocked: (() -> Unit)? = null,
    onOpenContactSync: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val bioMax by viewModel.bioMax.collectAsStateWithLifecycle()
    val usernameStatus by viewModel.usernameStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setPhoto)
    }

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
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        val me = profile
        if (me == null || loggingOut) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                Avatar(
                    me.user.fullName,
                    me.user.photo,
                    me.user.accentColorId,
                    modifier = Modifier.clickable { photoMenu = true },
                    size = 112.dp,
                )
                DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_photo_change)) },
                        onClick = {
                            photoMenu = false
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (me.hasPhoto) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_photo_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                photoMenu = false
                                viewModel.deletePhoto()
                            },
                        )
                    }
                }
            }
            Text(
                me.user.fullName,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.padding(4.dp))
            SettingRow(me.user.fullName, R.string.settings_name) { editing = Editing.Name }
            SettingRow(me.bio.ifBlank { stringResource(R.string.settings_bio_empty) }, R.string.profile_bio) { editing = Editing.Bio }
            SettingRow(
                if (me.user.username.isBlank()) stringResource(R.string.settings_username_empty) else "@${me.user.username}",
                R.string.profile_username,
            ) {
                viewModel.usernameInput.value = me.user.username
                editing = Editing.Username
            }
            // Номер виден, но не меняется: смена номера — вне скоупа
            SettingRow("+${me.user.phoneNumber}", R.string.profile_phone, onClick = null)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(R.string.autodownload_title, onClick = onOpenAutoDownload)
            onOpenBlocked?.let { ActionRow(R.string.blocked_title, onClick = it) }
            onOpenContactSync?.let { ActionRow(R.string.contact_sync_title, onClick = it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.logout),
                modifier = Modifier.fillMaxWidth().clickable { confirmLogOut = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (editing) {
            Editing.Name -> ContactNameDialog(
                title = stringResource(R.string.settings_name),
                initialFirst = me.user.firstName,
                initialLast = me.user.lastName,
                onSave = { first, last ->
                    editing = null
                    viewModel.setName(first, last)
                },
                onDismiss = { editing = null },
            )
            Editing.Bio -> TextDialog(
                title = stringResource(R.string.profile_bio),
                initial = me.bio,
                maxLength = bioMax,
                onSave = {
                    editing = null
                    viewModel.setBio(it)
                },
                onDismiss = { editing = null },
            )
            Editing.Username -> UsernameDialog(
                initial = me.user.username,
                status = usernameStatus,
                onChange = { viewModel.usernameInput.value = it },
                onSave = {
                    editing = null
                    viewModel.setUsername(it)
                },
                onDismiss = {
                    editing = null
                    viewModel.usernameInput.value = null
                },
            )
            null -> Unit
        }
    }
    if (confirmLogOut) {
        AlertDialog(
            onDismissRequest = { confirmLogOut = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogOut = false
                    viewModel.logOut()
                }) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingRow(value: String, @StringRes label: Int, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(@StringRes title: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextDialog(title: String, initial: String, maxLength: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                supportingText = { Text("${text.length} / $maxLength") },
                maxLines = 4,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Username с проверкой по мере ввода; пустой — убрать username. */
@Composable
fun UsernameDialog(
    initial: String,
    status: UsernameStatus?,
    onChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_username)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.removePrefix("@").filter { c -> c.isLetterOrDigit() || c == '_' }
                    onChange(text)
                },
                prefix = { Text("@") },
                singleLine = true,
                isError = status != null && !status.isOk,
                supportingText = {
                    when {
                        text.isEmpty() -> Text(stringResource(R.string.username_remove_hint))
                        status != null -> Text(stringResource(status.message))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isEmpty() || status?.isOk == true, onClick = { onSave(text) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
