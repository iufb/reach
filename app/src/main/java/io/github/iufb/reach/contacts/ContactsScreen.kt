package io.github.iufb.reach.contacts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.SheetHeader
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.navigation.MainTab
import io.github.iufb.reach.navigation.MainTabBar
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.ListSectionHeader
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.components.TabBarHeight
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme

/** Вкладка «Контакты»: поиск, список, «+» — добавить по номеру. */
@Composable
fun ContactsScreen(onSelectTab: (MainTab) -> Unit, onOpenChat: (Long) -> Unit, viewModel: ContactsViewModel = hiltViewModel()) {
    var adding by rememberSaveable { mutableStateOf(false) }
    ContactsList(
        title = stringResource(R.string.contacts_title),
        viewModel = viewModel,
        onOpenChat = onOpenChat,
        actions = { PillIconButton(painterResource(R.drawable.ic_add), stringResource(R.string.contacts_add), onClick = { adding = true }) },
        tabBar = { modifier -> MainTabBar(MainTab.Contacts, onSelectTab, modifier) },
    )
    if (adding) AddContact(viewModel) { adding = false }
}

/**
 * «Новое сообщение» с кнопки «создать» в списке чатов, как в Telegram iOS: сверху «Новая группа»,
 * «Новый контакт», «Новый канал», ниже контакты — тап открывает чат.
 */
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onNewChat: (channel: Boolean) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    ContactsList(
        title = stringResource(R.string.new_message_title),
        viewModel = viewModel,
        onOpenChat = onOpenChat,
        navigation = { BackPill(onBack) },
        header = {
            item(key = "new-group") { CreateRow(R.drawable.ic_group, R.string.new_group) { onNewChat(false) } }
            item(key = "new-contact") { CreateRow(R.drawable.ic_person_add, R.string.contacts_add) { adding = true } }
            item(key = "new-channel") { CreateRow(R.drawable.ic_channel, R.string.new_channel, divider = false) { onNewChat(true) } }
            item(key = "contacts-header") { ListSectionHeader(stringResource(R.string.contacts_title)) }
        },
    )
    if (adding) AddContact(viewModel) { adding = false }
}

/**
 * Общий список контактов: плавающая шапка, поиск первым элементом, строки с разделителями.
 * [header] — элементы между поиском и контактами, [tabBar] — tab bar внизу (вкладка «Контакты»).
 */
@Composable
private fun ContactsList(
    title: String,
    viewModel: ContactsViewModel,
    onOpenChat: (Long) -> Unit,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    header: LazyListScope.() -> Unit = {},
    tabBar: (@Composable (Modifier) -> Unit)? = null,
) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val notFoundText = stringResource(R.string.contacts_not_found)
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.notFound.collect { snackbar.showSnackbar(notFoundText) } }
    val open by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { open(it) } }
    val colors = ReachTheme.colors
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = if (tabBar != null) navBar + TabBarHeight + 16.dp else navBar

    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(
                Modifier.fillMaxSize().glassSource().imePadding(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight,
                    bottom = bottom,
                ),
            ) {
                item(key = "search") {
                    SearchField(
                        query,
                        onQueryChange = { viewModel.query.value = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = stringResource(R.string.contacts_search),
                    )
                }
                if (query.isBlank()) header()
                val current = list
                when {
                    current == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                    }
                    current.isEmpty() -> item(key = "empty") {
                        Text(
                            stringResource(if (query.isBlank()) R.string.contacts_empty else R.string.contacts_nothing_found),
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            color = colors.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> items(current, key = { it.id }) { user ->
                        UserRow(user, onClick = { viewModel.open(user) }, divider = user != current.last())
                    }
                }
            }
            ReachTopBar(title = title, navigation = navigation, actions = actions)
            tabBar?.invoke(Modifier.align(Alignment.BottomCenter))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
        }
    }
}

/** Строка «создать»: синий значок без подложки и синий текст, как в листе «New Message». */
@Composable
private fun CreateRow(@DrawableRes icon: Int, @StringRes title: Int, divider: Boolean = true, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(UserRowAvatar), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, Modifier.size(26.dp), tint = colors.accent)
            }
            Text(
                stringResource(title),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.accent,
            )
        }
        if (divider) HorizontalDivider(Modifier.padding(start = UserRowTextInset), thickness = 0.5.dp, color = colors.separator)
    }
}

@Composable
private fun AddContact(viewModel: ContactsViewModel, onDone: () -> Unit) {
    AddContactDialog(
        onAdd = { phone, first, last ->
            onDone()
            viewModel.addByPhone(phone, first, last)
        },
        onDismiss = onDone,
    )
}

/** «Новый контакт» во весь экран: номер, имя, фамилия; «Добавить» — когда есть номер и имя. */
@Composable
private fun AddContactDialog(onAdd: (phone: String, first: String, last: String) -> Unit, onDismiss: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("+") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    FormSheet(
        title = stringResource(R.string.contacts_add),
        action = stringResource(R.string.contacts_add_confirm),
        actionEnabled = phone.count { it.isDigit() } >= MIN_PHONE_DIGITS && first.isNotBlank(),
        onAction = { onAdd(phone, first, last) },
        onDismiss = onDismiss,
    ) {
        GroupedSection {
            GroupedTextField(first, { first = it }, placeholder = stringResource(R.string.contacts_first_name), focusRequester = focus)
            GroupedTextField(last, { last = it }, placeholder = stringResource(R.string.contacts_last_name), divider = false)
        }
        GroupedSection {
            GroupedTextField(
                phone,
                { phone = it },
                placeholder = stringResource(R.string.contacts_phone),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                divider = false,
            )
        }
    }
}

private const val MIN_PHONE_DIGITS = 5

/**
 * Имя и фамилия контакта во весь экран, как правка контакта в Telegram iOS: «Отмена» / заголовок /
 * «Готово», имя и фамилия карточкой. Добавление из профиля и переименование.
 */
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    FormSheet(
        title = title,
        action = stringResource(R.string.new_chat_done),
        actionEnabled = first.isNotBlank(),
        onAction = { onSave(first, last) },
        onDismiss = onDismiss,
    ) {
        GroupedSection(footer = stringResource(R.string.contact_edit_hint)) {
            GroupedTextField(first, { first = it }, placeholder = stringResource(R.string.contacts_first_name), focusRequester = focus)
            GroupedTextField(last, { last = it }, placeholder = stringResource(R.string.contacts_last_name), divider = false)
        }
    }
}

/** Шторка-форма во весь экран на сером фоне: шапка «Отмена / заголовок / действие», ниже карточки. */
@Composable
private fun FormSheet(
    title: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ReachTheme.colors.groupedBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SheetHeader(
                title = title,
                cancel = stringResource(R.string.cancel),
                onCancel = onDismiss,
                action = action,
                actionEnabled = actionEnabled,
                onAction = onAction,
            )
            content()
        }
    }
}
