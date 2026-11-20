package io.github.iufb.reach.members

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.contacts.ContactPicker
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.ui.theme.reachBarColors
import io.github.iufb.reach.users.User

/** Участники: список с фильтрами и поиском, добавление, удаление, разбан. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    onBack: () -> Unit,
    onOpenUser: (Long) -> Unit,
    /** Права админа (#153); `null` — экрана прав нет. */
    onEditAdmin: ((chatId: Long, userId: Long) -> Unit)? = null,
    onRestrict: ((chatId: Long, userId: Long) -> Unit)? = null,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val rights by viewModel.rights.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val contacts by viewModel.contactList.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val privacyText = stringResource(R.string.new_chat_privacy_failed)
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbar.showSnackbar(if (it == MembersViewModel.PRIVACY_MARKER) privacyText else it) }
    }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.closed.collect { back() } }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<Member, User>?>(null) }
    val supergroup = viewModel.group is GroupRef.Super

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    colors = reachBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    title = { Text(stringResource(if (viewModel.isChannel) R.string.members_subscribers else R.string.members_title)) },
                )
                val tabs = buildList {
                    add(MembersFilter.All)
                    add(MembersFilter.Admins)
                    if (supergroup && rights.canBan) add(MembersFilter.Banned)
                }
                TabRow(selectedTabIndex = tabs.indexOf(filter).coerceAtLeast(0)) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == filter,
                            onClick = { viewModel.filter.value = tab },
                            text = {
                                Text(
                                    stringResource(
                                        when (tab) {
                                            MembersFilter.All -> R.string.members_all
                                            MembersFilter.Admins -> R.string.members_admins
                                            MembersFilter.Banned -> R.string.members_banned
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (rights.canAdd && filter == MembersFilter.All) {
                FloatingActionButton(onClick = { adding = true }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (supergroup && filter != MembersFilter.Admins) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.members_search)) },
                    singleLine = true,
                )
            }
            val current = list
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.members_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(current, key = { it.first.userId }) { (member, user) ->
                        MemberItem(
                            member, user, rights,
                            onOpen = { onOpenUser(user.id) },
                            onRemove = { removing = member to user },
                            onUnban = { viewModel.unban(member) },
                            onEditAdmin = onEditAdmin?.let { edit -> { edit(viewModel.chatId, user.id) } },
                            onRestrict = onRestrict?.takeIf { !viewModel.isChannel }?.let { r -> { r(viewModel.chatId, user.id) } },
                        )
                    }
                    item { LaunchedEffect(current.size) { viewModel.loadMore() } }
                }
            }
        }
    }
    removing?.let { (member, user) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.members_remove_confirm, user.fullName)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.remove(member)
                }) { Text(stringResource(R.string.members_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (adding) {
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        val already = list.orEmpty().map { it.first.userId }.toSet()
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.members_add)) },
            text = {
                ContactPicker(
                    contacts,
                    selected,
                    onToggle = { u -> selected = if (u.id in selected) selected - u.id else selected + u.id },
                    modifier = Modifier.height(420.dp),
                    disabled = already,
                )
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    adding = false
                    viewModel.add(selected)
                }) { Text(stringResource(R.string.contacts_add_confirm)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MemberItem(
    member: Member,
    user: User,
    rights: MemberRights,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onUnban: () -> Unit,
    onEditAdmin: (() -> Unit)?,
    onRestrict: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val role = member.role
    val label = when (role) {
        MemberRole.Owner -> stringResource(R.string.member_owner)
        is MemberRole.Admin -> member.title.ifBlank { stringResource(R.string.member_admin) }
        is MemberRole.Restricted -> stringResource(R.string.member_restricted)
        MemberRole.Banned -> stringResource(R.string.member_banned)
        MemberRole.Member -> null
    }
    val editableAdmin = role is MemberRole.Admin && role.canBeEdited
    val canActOn = role != MemberRole.Owner && (role !is MemberRole.Admin || editableAdmin)
    val actions = buildList<Pair<Int, () -> Unit>> {
        add(R.string.member_open to onOpen)
        if (rights.canPromote && canActOn && role != MemberRole.Banned && onEditAdmin != null) {
            add((if (role is MemberRole.Admin) R.string.member_edit_admin else R.string.member_promote) to onEditAdmin)
        }
        if (rights.canBan && canActOn && role !is MemberRole.Admin && role != MemberRole.Banned && onRestrict != null) {
            add(R.string.member_restrict to onRestrict)
        }
        if (rights.canBan && canActOn && role != MemberRole.Banned) add(R.string.members_remove to onRemove)
        if (rights.canBan && role == MemberRole.Banned) add(R.string.member_unban to onUnban)
    }
    Box {
        UserRow(
            user,
            onClick = { if (actions.size > 1) menu = true else onOpen() },
            trailing = label?.let { { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) } },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions.forEach { (title, action) ->
                DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { menu = false; action() })
            }
        }
    }
}
