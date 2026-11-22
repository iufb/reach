package io.github.iufb.reach.members

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.contacts.ContactPickerSheet
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.contacts.UserRowTextInset
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.components.SegmentedControl
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.groupedItems
import io.github.iufb.reach.ui.theme.ReachTheme
import io.github.iufb.reach.users.User

/**
 * Участники в стиле Telegram iOS: сегмент «Все / Админы / Удалённые», поиск, список карточкой;
 * тап по участнику — лист действий, «+» в шапке — добавить из контактов.
 */
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
    var acting by remember { mutableStateOf<Pair<Member, User>?>(null) }
    var removing by remember { mutableStateOf<Pair<Member, User>?>(null) }
    val supergroup = viewModel.group is GroupRef.Super
    val colors = ReachTheme.colors
    val tabs = buildList {
        add(MembersFilter.All)
        add(MembersFilter.Admins)
        if (supergroup && rights.canBan) add(MembersFilter.Banned)
    }

    ListScreen(
        title = stringResource(if (viewModel.isChannel) R.string.members_subscribers else R.string.members_title),
        onBack = onBack,
        snackbar = snackbar,
        // Полный список — простой, как контакты: на белом видны сегмент и поиск
        background = colors.background,
        actions = {
            if (rights.canAdd && filter == MembersFilter.All) {
                PillIconButton(painterResource(R.drawable.ic_add), stringResource(R.string.members_add), onClick = { adding = true })
            }
        },
    ) {
        item(key = "tabs") {
            SegmentedControl(
                items = tabs.map { stringResource(it.label()) },
                selected = tabs.indexOf(filter).coerceAtLeast(0),
                onSelect = { viewModel.filter.value = tabs[it] },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (supergroup && filter != MembersFilter.Admins) {
            item(key = "search") {
                SearchField(
                    query,
                    onQueryChange = { viewModel.query.value = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = stringResource(R.string.members_search),
                )
            }
        }
        val current = list
        when {
            current == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            }
            current.isEmpty() -> item(key = "empty") {
                Text(
                    stringResource(R.string.members_empty),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                item(key = "gap") { Spacer(Modifier.height(8.dp)) }
                groupedItems(current, key = { it.first.userId }, dividerInset = UserRowTextInset - 16.dp) { (member, user) ->
                    UserRow(
                        user,
                        onClick = { acting = member to user },
                        trailing = member.roleLabel()?.let { label ->
                            { Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryLabel) }
                        },
                    )
                }
                item(key = "more") { LaunchedEffect(current.size) { viewModel.loadMore() } }
            }
        }
    }

    acting?.let { (member, user) ->
        val actions = memberActions(
            member, rights,
            onOpen = { onOpenUser(user.id) },
            onRemove = { removing = member to user },
            onUnban = { viewModel.unban(member) },
            onEditAdmin = onEditAdmin?.let { edit -> { edit(viewModel.chatId, user.id) } },
            onRestrict = onRestrict?.takeIf { !viewModel.isChannel }?.let { r -> { r(viewModel.chatId, user.id) } },
        )
        // Только «Открыть профиль» — сразу открываем, без листа
        if (actions.size == 1) {
            LaunchedEffect(member) {
                acting = null
                onOpenUser(user.id)
            }
        } else {
            ActionSheet(title = user.fullName, actions = actions, onDismiss = { acting = null })
        }
    }
    removing?.let { (member, user) ->
        ActionSheet(
            title = stringResource(R.string.members_remove_confirm, user.fullName),
            actions = listOf(SheetAction(stringResource(R.string.members_remove), destructive = true) { viewModel.remove(member) }),
            onDismiss = { removing = null },
        )
    }
    if (adding) {
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        ContactPickerSheet(
            title = stringResource(R.string.members_add),
            contacts = contacts,
            selected = selected,
            onToggle = { u -> selected = if (u.id in selected) selected - u.id else selected + u.id },
            onDismiss = { adding = false },
            confirm = stringResource(R.string.contacts_add_confirm) to {
                adding = false
                viewModel.add(selected)
            },
            disabled = list.orEmpty().map { it.first.userId }.toSet(),
        )
    }
}

@StringRes
private fun MembersFilter.label(): Int = when (this) {
    MembersFilter.All -> R.string.members_all
    MembersFilter.Admins -> R.string.members_admins
    MembersFilter.Banned -> R.string.members_removed_tab
}

/** Роль справа в строке: «владелец», титул или «админ», «ограничен», «удалён». */
@Composable
private fun Member.roleLabel(): String? = when (val role = role) {
    MemberRole.Owner -> stringResource(R.string.member_owner)
    is MemberRole.Admin -> title.ifBlank { stringResource(R.string.member_admin) }
    is MemberRole.Restricted -> stringResource(R.string.member_restricted)
    MemberRole.Banned -> stringResource(R.string.member_banned)
    MemberRole.Member -> null
}

/** Что можно сделать с участником при текущих правах; первым — открыть профиль. */
@Composable
private fun memberActions(
    member: Member,
    rights: MemberRights,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onUnban: () -> Unit,
    onEditAdmin: (() -> Unit)?,
    onRestrict: (() -> Unit)?,
): List<SheetAction> {
    val role = member.role
    val editableAdmin = role is MemberRole.Admin && role.canBeEdited
    val canActOn = role != MemberRole.Owner && (role !is MemberRole.Admin || editableAdmin)
    return buildList {
        add(SheetAction(stringResource(R.string.member_open), onClick = onOpen))
        if (rights.canPromote && canActOn && role != MemberRole.Banned && onEditAdmin != null) {
            add(SheetAction(stringResource(if (role is MemberRole.Admin) R.string.member_edit_admin else R.string.member_promote), onClick = onEditAdmin))
        }
        if (rights.canBan && canActOn && role !is MemberRole.Admin && role != MemberRole.Banned && onRestrict != null) {
            add(SheetAction(stringResource(R.string.member_restrict), onClick = onRestrict))
        }
        if (rights.canBan && canActOn && role != MemberRole.Banned) add(SheetAction(stringResource(R.string.members_remove), destructive = true, onClick = onRemove))
        if (rights.canBan && role == MemberRole.Banned) add(SheetAction(stringResource(R.string.member_unban), onClick = onUnban))
    }
}
