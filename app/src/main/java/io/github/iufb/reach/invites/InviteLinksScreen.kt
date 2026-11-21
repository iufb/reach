package io.github.iufb.reach.invites

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.reachBarColors
import kotlinx.coroutines.launch

/** Ссылки-приглашения: основная, дополнительные, отозванные и заявки на вступление. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteLinksScreen(onBack: () -> Unit, viewModel: InviteLinksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copy: (InviteLink) -> Unit = { link -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, link.link))) } }
    val share: (InviteLink) -> Unit = { link -> shareLink(context, link.link) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InviteLink?>(null) }

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
                title = { Text(stringResource(R.string.invite_links_title)) },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            state.primary?.let { primary ->
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Section(stringResource(R.string.invite_link_title))
                        Text(primary.link, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Row {
                            TextButton(onClick = { copy(primary) }) { Text(stringResource(R.string.message_action_copy)) }
                            TextButton(onClick = { share(primary) }) { Text(stringResource(R.string.invite_share)) }
                            TextButton(onClick = viewModel::replacePrimary) { Text(stringResource(R.string.invite_replace)) }
                        }
                        if (primary.memberCount > 0) {
                            Text(
                                pluralStringResource(R.plurals.invite_joined, primary.memberCount, primary.memberCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (requests.isNotEmpty()) {
                item { Section(stringResource(R.string.join_requests_title), Modifier.padding(16.dp)) }
                items(requests, key = { "request-${it.user.id}" }) { request ->
                    UserRow(
                        request.user,
                        onClick = null,
                        subtitle = request.bio.ifBlank { null },
                        trailing = {
                            Row {
                                TextButton(onClick = { viewModel.process(request, false) }) {
                                    Text(stringResource(R.string.join_request_decline), color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { viewModel.process(request, true) }) { Text(stringResource(R.string.join_request_approve)) }
                            }
                        },
                    )
                }
                item { HorizontalDivider() }
            }
            item {
                TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(stringResource(R.string.invite_create))
                }
            }
            items(state.links, key = { it.link }) { link ->
                LinkRow(link, onCopy = { copy(link) }, onShare = { share(link) }, onEdit = { editing = link }, onRevoke = { viewModel.revoke(link) })
            }
            if (state.revoked.isNotEmpty()) {
                item { Section(stringResource(R.string.invite_revoked), Modifier.padding(16.dp)) }
                items(state.revoked, key = { "revoked-${it.link}" }) { link ->
                    LinkRow(link, onDelete = { viewModel.deleteRevoked(link) })
                }
            }
        }
    }
    if (creating) {
        LinkDialog(null, onSave = { creating = false; viewModel.create(it) }, onDismiss = { creating = false })
    }
    editing?.let { link ->
        LinkDialog(link, onSave = { editing = null; viewModel.edit(link, it) }, onDismiss = { editing = null })
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LinkRow(
    link: InviteLink,
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRevoke: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().clickable { menu = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(link.name.ifBlank { link.link }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(linkDetails(link), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            onCopy?.let { DropdownMenuItem(text = { Text(stringResource(R.string.message_action_copy)) }, onClick = { menu = false; it() }) }
            onShare?.let { DropdownMenuItem(text = { Text(stringResource(R.string.invite_share)) }, onClick = { menu = false; it() }) }
            onEdit?.let { DropdownMenuItem(text = { Text(stringResource(R.string.message_action_edit)) }, onClick = { menu = false; it() }) }
            onRevoke?.let {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.invite_revoke), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; it() },
                )
            }
            onDelete?.let {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; it() },
                )
            }
        }
    }
}

@Composable
private fun linkDetails(link: InviteLink): String {
    val parts = buildList {
        add(
            if (link.memberLimit > 0) {
                stringResource(R.string.invite_usage_limit, link.memberCount, link.memberLimit)
            } else {
                pluralStringResource(R.plurals.invite_joined, link.memberCount, link.memberCount)
            },
        )
        if (link.createsJoinRequest) add(pluralStringResource(R.plurals.invite_pending, link.pendingRequests, link.pendingRequests))
        when {
            link.isRevoked -> add(stringResource(R.string.invite_status_revoked))
            link.isExpired -> add(stringResource(R.string.invite_status_expired))
            link.expirationDate != 0 -> add(
                stringResource(
                    R.string.invite_expires,
                    Dates.dateTime(link.expirationDate.toLong()),
                ),
            )
        }
    }
    return parts.joinToString(" · ")
}

/** Новая или изменяемая ссылка: название, срок, лимит или заявки. */
@Composable
private fun LinkDialog(existing: InviteLink?, onSave: (LinkDraft) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var expires by rememberSaveable { mutableStateOf(0) }
    var limit by rememberSaveable { mutableStateOf(existing?.memberLimit?.takeIf { it > 0 }?.toString().orEmpty()) }
    var requests by rememberSaveable { mutableStateOf(existing?.createsJoinRequest ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.invite_create else R.string.message_action_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(NAME_MAX) }, label = { Text(stringResource(R.string.invite_name)) }, singleLine = true)
                Row(
                    Modifier.fillMaxWidth().toggleable(requests, role = Role.Switch) { requests = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.invite_requests_switch), Modifier.weight(1f))
                    Switch(checked = requests, onCheckedChange = null)
                }
                if (!requests) {
                    OutlinedTextField(
                        limit,
                        { limit = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.invite_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(stringResource(R.string.invite_expiry), style = MaterialTheme.typography.labelMedium)
                EXPIRY_OPTIONS.forEach { (seconds, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(expires == seconds, role = Role.RadioButton) { expires = seconds },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = expires == seconds, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(LinkDraft(name, expires, limit.toIntOrNull()?.coerceIn(0, MAX_LIMIT) ?: 0, requests))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun shareLink(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    context.startActivity(Intent.createChooser(send, null))
}

private val EXPIRY_OPTIONS = listOf(
    0 to R.string.invite_expiry_never,
    3_600 to R.string.invite_expiry_hour,
    86_400 to R.string.restrict_day,
    7 * 86_400 to R.string.restrict_week,
)

private const val NAME_MAX = 32
private const val MAX_LIMIT = 99_999
