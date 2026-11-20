package io.github.iufb.reach.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.PhotoViewer
import io.github.iufb.reach.chat.RichTextView
import io.github.iufb.reach.contacts.ContactNameDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.github.iufb.reach.chats.autoDeleteLabel
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.reachBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenGallery: (chatId: Long) -> Unit,
    onOpenMembers: (chatId: Long) -> Unit = {},
    onOpenPermissions: (chatId: Long) -> Unit = {},
    onOpenInviteLinks: (chatId: Long) -> Unit = {},
    onOpenStatistics: (chatId: Long) -> Unit = {},
    /** Вышли из группы или удалили её. */
    onLeft: () -> Unit = onBack,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val leftCallback by rememberUpdatedState(onLeft)
    LaunchedEffect(viewModel) { viewModel.left.collect { leftCallback() } }
    var confirmLeave by remember { mutableStateOf<Boolean?>(null) }
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { open(it) } }
    var viewingPhoto by remember { mutableStateOf(false) }
    var choosingAutoDelete by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.error.collect { snackbar.showSnackbar(it) } }

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
                title = { Text(stringResource(R.string.profile_title)) },
                actions = {
                    val current = profile
                    val isContact = current?.isContact
                    if (isContact != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.chat_list_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (isContact) R.string.contact_edit else R.string.contact_add)) },
                                    onClick = {
                                        menuOpen = false
                                        editingContact = true
                                    },
                                )
                                if (isContact) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.contact_remove), color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.removeContact()
                                        },
                                    )
                                }
                                current?.isBlocked?.let { blocked ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(if (blocked) R.string.unblock else R.string.block),
                                                color = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            if (blocked) viewModel.setBlocked(false) else confirmBlock = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = profile
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                current.title,
                current.photo,
                current.accentColorId,
                modifier = Modifier.clickable(enabled = current.fullPhoto != null) { viewingPhoto = true },
                size = 112.dp,
            )
            Text(
                current.title.ifBlank { stringResource(R.string.chat_deleted_account) },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (current.memberCount > 0) {
                Text(
                    pluralStringResource(
                        if (current.isChannel) R.plurals.subscribers else R.plurals.members,
                        current.memberCount,
                        current.memberCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.canWrite) {
                Button(onClick = viewModel::write) { Text(stringResource(R.string.profile_write)) }
            }
            HorizontalDivider()
            TextButton(onClick = { onOpenGallery(viewModel.chatId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_gallery), Modifier.fillMaxWidth())
            }
            current.isMuted?.let { muted ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.profile_notifications), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = !muted, onCheckedChange = viewModel::setNotificationsEnabled)
                }
            }
            if (current.canOpenMembers) {
                TextButton(onClick = { onOpenMembers(viewModel.chatId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (current.isChannel) R.string.members_subscribers else R.string.members_title),
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            if (current.canGetStatistics) {
                TextButton(onClick = { onOpenStatistics(viewModel.chatId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_title), Modifier.fillMaxWidth())
                }
            }
            if (current.canManageInvites) {
                TextButton(onClick = { onOpenInviteLinks(viewModel.chatId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invite_links_title), Modifier.fillMaxWidth())
                }
            }
            if (current.canEditPermissions) {
                TextButton(onClick = { onOpenPermissions(viewModel.chatId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.permissions_title), Modifier.fillMaxWidth())
                }
            }
            current.autoDeleteTime?.let { seconds ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = current.canChangeAutoDelete) { choosingAutoDelete = true },
                ) {
                    Text(stringResource(R.string.auto_delete_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        autoDeleteLabel(seconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (current.phoneNumber.isNotBlank()) InfoRow("+${current.phoneNumber}", R.string.profile_phone)
            if (current.username.isNotBlank()) InfoRow("@${current.username}", R.string.profile_username)
            if (current.username.isNotBlank() && !current.isUser) {
                InfoRow("https://t.me/${current.username}", R.string.profile_link)
            }
            if (!current.about.isBlank()) {
                Column(Modifier.fillMaxWidth()) {
                    RichTextView(current.about, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (current.isUser) R.string.profile_bio else R.string.profile_description),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (current.isMember && !current.isUser) {
                HorizontalDivider()
                TextButton(onClick = { confirmLeave = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (current.isChannel) R.string.leave_channel else R.string.leave_group),
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (current.isOwner) {
                    TextButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(if (current.isChannel) R.string.delete_channel else R.string.delete_group),
                            Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        confirmLeave?.let { delete ->
            AlertDialog(
                onDismissRequest = { confirmLeave = null },
                text = { Text(stringResource(if (delete) R.string.delete_chat_confirm else R.string.leave_chat_confirm, current.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = null
                        if (delete) viewModel.deleteChat() else viewModel.leave()
                    }) { Text(stringResource(if (delete) R.string.message_action_delete else R.string.leave), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (viewingPhoto) current.fullPhoto?.let { PhotoViewer(listOf(it), 0) { viewingPhoto = false } }
        if (confirmBlock) {
            AlertDialog(
                onDismissRequest = { confirmBlock = false },
                text = { Text(stringResource(R.string.block_confirm, current.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmBlock = false
                        viewModel.setBlocked(true)
                    }) { Text(stringResource(R.string.block), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (editingContact) {
            ContactNameDialog(
                title = stringResource(if (current.isContact == true) R.string.contact_edit else R.string.contact_add),
                initialFirst = current.firstName,
                initialLast = current.lastName,
                onSave = { first, last ->
                    editingContact = false
                    viewModel.saveContact(first, last)
                },
                onDismiss = { editingContact = false },
            )
        }
        if (choosingAutoDelete) {
            AutoDeleteDialog(
                current = current.autoDeleteTime ?: 0,
                onPick = {
                    choosingAutoDelete = false
                    viewModel.setAutoDelete(it)
                },
                onDismiss = { choosingAutoDelete = false },
            )
        }
    }
}

@Composable
private fun InfoRow(value: String, @StringRes label: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Выбор таймера автоудаления: выкл, сутки, неделя, месяц. */
@Composable
private fun AutoDeleteDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = buildList {
        addAll(AUTO_DELETE_OPTIONS)
        // Значение, выставленное в другом клиенте, тоже показываем
        if (current !in AUTO_DELETE_OPTIONS) add(current)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_delete_title)) },
        text = {
            Column {
                options.forEach { seconds ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = seconds == current, role = Role.RadioButton) { onPick(seconds) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = seconds == current, onClick = null)
                        Text(autoDeleteLabel(seconds), Modifier.padding(start = 12.dp))
                    }
                }
                Text(
                    stringResource(R.string.auto_delete_hint),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val AUTO_DELETE_OPTIONS = listOf(0, 86_400, 7 * 86_400, 31 * 86_400)
