package io.github.iufb.reach.newchat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.ContactPicker
import io.github.iufb.reach.ui.theme.reachBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(onBack: () -> Unit, onCreated: (Long) -> Unit, viewModel: NewChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contacts by viewModel.contactList.collectAsStateWithLifecycle()
    val usernameStatus by viewModel.usernameStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val privacyText = stringResource(R.string.new_chat_privacy_failed)
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { error ->
            snackbar.showSnackbar(if (error.startsWith(NewChatViewModel.PRIVACY_MARKER)) privacyText else error)
        }
    }
    val created by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) { viewModel.done.collect { created(it) } }
    val back = { if (!viewModel.back()) onBack() }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }

    val canNext = !state.busy && when (state.step) {
        NewChatStep.Members -> true
        NewChatStep.Info -> state.title.isNotBlank()
        NewChatStep.Visibility -> !state.isPublic || usernameStatus?.isOk == true
        NewChatStep.Subscribers -> true
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(if (viewModel.isChannel) R.string.new_channel else R.string.new_group))
                        if (state.step == NewChatStep.Members || state.step == NewChatStep.Subscribers) {
                            Text(
                                pluralStringResource(R.plurals.new_chat_selected, state.selected.size, state.selected.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(enabled = canNext, onClick = viewModel::next) {
                            Text(
                                stringResource(
                                    when {
                                        state.step == NewChatStep.Info -> R.string.new_chat_create
                                        state.step == NewChatStep.Subscribers -> R.string.new_chat_done
                                        else -> R.string.schedule_next
                                    },
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (state.step) {
            NewChatStep.Members, NewChatStep.Subscribers -> {
                val list = contacts
                if (list == null) {
                    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    ContactPicker(list, state.selected, viewModel::toggle, modifier)
                }
            }
            NewChatStep.Info -> Column(
                modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = state.photo
                    if (photo != null) {
                        AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            stringResource(R.string.new_chat_photo),
                            modifier = Modifier.fillMaxSize().padding(top = 36.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (viewModel.isChannel) R.string.new_channel_title else R.string.new_group_title)) },
                    singleLine = true,
                )
                if (viewModel.isChannel) {
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::setDescription,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.profile_description)) },
                        maxLines = 4,
                    )
                }
            }
            NewChatStep.Visibility -> Column(
                modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceRow(stringResource(R.string.new_channel_private), stringResource(R.string.new_channel_private_hint), !state.isPublic) {
                    viewModel.setPublic(false)
                }
                ChoiceRow(stringResource(R.string.new_channel_public), stringResource(R.string.new_channel_public_hint), state.isPublic) {
                    viewModel.setPublic(true)
                }
                if (state.isPublic) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        modifier = Modifier.fillMaxWidth(),
                        prefix = { Text("t.me/") },
                        singleLine = true,
                        isError = usernameStatus?.isOk == false,
                        supportingText = { usernameStatus?.let { Text(stringResource(it.message)) } },
                    )
                } else if (state.inviteLink.isNotEmpty()) {
                    Text(stringResource(R.string.invite_link_title), style = MaterialTheme.typography.labelMedium)
                    Text(state.inviteLink, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
