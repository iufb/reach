package io.github.iufb.reach.invites

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.contacts.UserRowTextInset
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedRow
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.groupedHeader
import io.github.iufb.reach.ui.components.groupedItems
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.launch

/**
 * Ссылки-приглашения в стиле Telegram iOS: основная ссылка карточкой с «Копировать» и «Поделиться»,
 * заявки на вступление, дополнительные ссылки с «Создать ссылку» первой строкой, отозванные.
 * Действия со ссылкой — листом; «Отозвать» — с подтверждением.
 */
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
    var acting by remember { mutableStateOf<InviteLink?>(null) }
    var revoking by remember { mutableStateOf<InviteLink?>(null) }
    val colors = ReachTheme.colors
    val linkTitle = stringResource(R.string.invite_link_title)
    val requestsTitle = stringResource(R.string.join_requests_title)
    val moreTitle = stringResource(R.string.invite_more)
    val revokedTitle = stringResource(R.string.invite_revoked)

    ListScreen(
        title = stringResource(R.string.invite_links_title),
        onBack = onBack,
        snackbar = snackbar,
        loading = !state.loaded,
    ) {
        state.primary?.let { primary ->
            groupedHeader(linkTitle)
            item(key = "primary") {
                GroupedRow(first = true, last = true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            primary.link,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.fill)
                                .clickable { copy(primary) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.label,
                            maxLines = 1,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LinkButton(stringResource(R.string.message_action_copy), Modifier.weight(1f)) { copy(primary) }
                            LinkButton(stringResource(R.string.invite_share), Modifier.weight(1f)) { share(primary) }
                        }
                    }
                }
            }
            if (primary.memberCount > 0) {
                item(key = "primary-joined") {
                    Text(
                        pluralStringResource(R.plurals.invite_joined, primary.memberCount, primary.memberCount),
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryLabel,
                    )
                }
            }
            item(key = "replace") {
                GroupedSection {
                    GroupedCell(stringResource(R.string.invite_replace), onClick = viewModel::replacePrimary, destructive = true, chevron = false, divider = false)
                }
            }
        }
        if (requests.isNotEmpty()) {
            groupedHeader(requestsTitle)
            groupedItems(requests, key = { "request-${it.user.id}" }, dividerInset = UserRowTextInset - 16.dp) { request ->
                UserRow(
                    request.user,
                    onClick = null,
                    subtitle = request.bio.ifBlank { null },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallPill(stringResource(R.string.join_request_decline), accent = false) { viewModel.process(request, false) }
                            SmallPill(stringResource(R.string.join_request_approve), accent = true) { viewModel.process(request, true) }
                        }
                    },
                )
            }
        }
        groupedHeader(moreTitle)
        item(key = "create") {
            GroupedRow(first = true, last = state.links.isEmpty()) {
                GroupedCell(
                    stringResource(R.string.invite_create),
                    onClick = { creating = true },
                    icon = {
                        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = colors.accent)
                        }
                    },
                    accent = true,
                    chevron = false,
                    divider = false,
                )
            }
        }
        items(state.links, key = { it.link }) { link ->
            GroupedRow(first = false, last = link == state.links.last()) { LinkCell(link) { acting = link } }
        }
        if (state.revoked.isNotEmpty()) {
            groupedHeader(revokedTitle)
            groupedItems(state.revoked, key = { "revoked-${it.link}" }) { link -> LinkCell(link) { acting = link } }
        }
    }

    acting?.let { link ->
        ActionSheet(
            title = link.name.ifBlank { link.link },
            actions = buildList {
                if (link.isRevoked) {
                    add(SheetAction(stringResource(R.string.message_action_delete), destructive = true) { viewModel.deleteRevoked(link) })
                } else {
                    add(SheetAction(stringResource(R.string.message_action_copy)) { copy(link) })
                    add(SheetAction(stringResource(R.string.invite_share)) { share(link) })
                    add(SheetAction(stringResource(R.string.message_action_edit)) { editing = link })
                    add(SheetAction(stringResource(R.string.invite_revoke), destructive = true) { revoking = link })
                }
            },
            onDismiss = { acting = null },
        )
    }
    revoking?.let { link ->
        ActionSheet(
            title = stringResource(R.string.invite_revoke_confirm),
            actions = listOf(SheetAction(stringResource(R.string.invite_revoke), destructive = true) { viewModel.revoke(link) }),
            onDismiss = { revoking = null },
        )
    }
    if (creating) {
        LinkEditor(null, onSave = { creating = false; viewModel.create(it) }, onDismiss = { creating = false })
    }
    editing?.let { link ->
        LinkEditor(link, onSave = { editing = null; viewModel.edit(link, it) }, onDismiss = { editing = null })
    }
}

/** Большая кнопка под основной ссылкой: акцентный текст на подложке `fill`. */
@Composable
private fun LinkButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = colors.onAccent,
        textAlign = TextAlign.Center,
    )
}

/** Маленькая кнопка в строке заявки: «Принять» акцентом, «Отклонить» на `fill`. */
@Composable
private fun SmallPill(text: String, accent: Boolean, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (accent) colors.accent else colors.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (accent) colors.onAccent else colors.label,
    )
}

/** Строка дополнительной ссылки: название (или сама ссылка) и кто вступил, лимит, срок. */
@Composable
private fun LinkCell(link: InviteLink, onClick: () -> Unit) {
    GroupedCell(
        link.name.ifBlank { link.link },
        onClick = onClick,
        subtitle = linkDetails(link),
        chevron = false,
        divider = false,
    )
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
            link.expirationDate != 0 -> add(stringResource(R.string.invite_expires, Dates.dateTime(link.expirationDate.toLong())))
        }
    }
    return parts.joinToString(" · ")
}

/** Новая или изменяемая ссылка во весь экран: название, заявки, лимит, срок. */
@Composable
private fun LinkEditor(existing: InviteLink?, onSave: (LinkDraft) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var expires by rememberSaveable { mutableStateOf(0) }
    var limit by rememberSaveable { mutableStateOf(existing?.memberLimit?.takeIf { it > 0 }?.toString().orEmpty()) }
    var requests by rememberSaveable { mutableStateOf(existing?.createsJoinRequest ?: false) }
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.groupedBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 12.dp)) {
                PillTextButton(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart))
                Text(
                    stringResource(if (existing == null) R.string.invite_create else R.string.message_action_edit),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.label,
                )
                PillTextButton(
                    stringResource(R.string.save),
                    onClick = { onSave(LinkDraft(name, expires, limit.toIntOrNull()?.coerceIn(0, MAX_LIMIT) ?: 0, requests)) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    bold = true,
                )
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GroupedSection(header = stringResource(R.string.invite_name_short), footer = stringResource(R.string.invite_name_hint)) {
                    GroupedTextField(name, { name = it.take(NAME_MAX) }, placeholder = stringResource(R.string.invite_name_short), divider = false)
                }
                GroupedSection {
                    GroupedCell(
                        stringResource(R.string.invite_requests_switch),
                        modifier = Modifier.toggleable(requests, role = Role.Switch) { requests = it },
                        chevron = false,
                        divider = false,
                        trailing = { Switch(checked = requests, onCheckedChange = null) },
                    )
                }
                if (!requests) {
                    GroupedSection(header = stringResource(R.string.invite_limit_short), footer = stringResource(R.string.invite_limit_hint)) {
                        GroupedTextField(
                            limit,
                            { limit = it.filter(Char::isDigit).take(5) },
                            placeholder = stringResource(R.string.invite_limit_short),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            divider = false,
                        )
                    }
                }
                GroupedSection(header = stringResource(R.string.invite_expiry)) {
                    EXPIRY_OPTIONS.forEachIndexed { index, (seconds, label) ->
                        GroupedCell(
                            stringResource(label),
                            modifier = Modifier.selectable(expires == seconds, role = Role.RadioButton) { expires = seconds },
                            chevron = false,
                            divider = index < EXPIRY_OPTIONS.lastIndex,
                            trailing = {
                                if (expires == seconds) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = colors.accent)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
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
