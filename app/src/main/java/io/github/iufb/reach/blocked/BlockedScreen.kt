package io.github.iufb.reach.blocked

import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.ContactPickerSheet
import io.github.iufb.reach.contacts.ContactsRepository
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.contacts.UserRowTextInset
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.groupedItems
import io.github.iufb.reach.ui.theme.ReachTheme
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Заблокированные пользователи (PRODUCT.md §4.7). */
@HiltViewModel
class BlockedViewModel @Inject constructor(
    private val userErrors: UserErrors,
    private val td: TdClient,
    users: UsersRepository,
    contacts: ContactsRepository,
) : ViewModel() {

    private val ids = MutableStateFlow<List<Long>?>(null)
    private var total = Int.MAX_VALUE
    private var loading = false

    /** `null` — ещё грузятся. Заблокированные каналы и группы клиент не показывает. */
    val blocked: StateFlow<List<User>?> = combine(ids, users.users) { list, known -> list?.mapNotNull(known::get) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Кого можно добавить в список: контакты. */
    val candidates: StateFlow<List<User>> = combine(contacts.contacts, ids) { all, blocked ->
        all.orEmpty().filter { it.id !in blocked.orEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        viewModelScope.launch { runCatching { contacts.load() } }
        reload()
        // Блокировка из профиля или другого клиента: перечитываем список
        viewModelScope.launch {
            td.updates.collect { update ->
                if (update is TdApi.UpdateUserFullInfo || update is TdApi.UpdateChatBlockList) reload()
            }
        }
    }

    private fun reload() {
        total = Int.MAX_VALUE
        ids.value = ids.value?.let { emptyList() }
        loadMore(reset = true)
    }

    fun loadMore(reset: Boolean = false) {
        if (loading && !reset) return
        val current = if (reset) emptyList() else ids.value.orEmpty()
        if (current.size >= total) return
        loading = true
        viewModelScope.launch {
            runCatching { td.send(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), current.size, PAGE)) }
                .onSuccess { page ->
                    total = page.totalCount
                    val users = page.senders.mapNotNull { (it as? TdApi.MessageSenderUser)?.userId }
                    ids.value = (current + users).distinct()
                }
                .onFailure {
                    Log.w(TAG, "getBlockedMessageSenders failed", it)
                    ids.value = current
                }
            loading = false
        }
    }

    fun setBlocked(userId: Long, blocked: Boolean) {
        viewModelScope.launch {
            runCatching {
                td.send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), if (blocked) TdApi.BlockListMain() else null))
            }.onSuccess { reload() }.onFailure { _errors.emit(userErrors.message(it)) }
        }
    }

    private companion object {
        const val TAG = "BlockedViewModel"
        const val PAGE = 50
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Заблокированные в стиле Telegram iOS: список карточкой, тап — разблокировать, «+» — выбрать из контактов. */
@Composable
fun BlockedScreen(onBack: () -> Unit, viewModel: BlockedViewModel = hiltViewModel()) {
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    var picking by rememberSaveable { mutableStateOf(false) }
    var unblocking by remember { mutableStateOf<User?>(null) }
    val colors = ReachTheme.colors
    val list = blocked
    ListScreen(
        title = stringResource(R.string.blocked_title),
        onBack = onBack,
        snackbar = snackbar,
        loading = list == null,
        actions = { PillIconButton(painterResource(R.drawable.ic_add), stringResource(R.string.block_pick), onClick = { picking = true }) },
    ) {
        if (list.isNullOrEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.blocked_empty),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item(key = "gap") { Spacer(Modifier.height(8.dp)) }
            groupedItems(list, key = { it.id }, dividerInset = UserRowTextInset - 16.dp) { user ->
                UserRow(user, onClick = { unblocking = user }, subtitle = user.phoneNumber.takeIf { it.isNotBlank() }?.let { "+$it" } ?: "")
            }
            item(key = "more") { LaunchedEffect(list.size) { viewModel.loadMore() } }
        }
    }
    unblocking?.let { user ->
        ActionSheet(
            title = stringResource(R.string.unblock_confirm, user.fullName),
            actions = listOf(SheetAction(stringResource(R.string.unblock)) { viewModel.setBlocked(user.id, false) }),
            onDismiss = { unblocking = null },
        )
    }
    if (picking) {
        ContactPickerSheet(
            title = stringResource(R.string.block_pick),
            contacts = candidates,
            selected = emptySet(),
            onToggle = { user ->
                picking = false
                viewModel.setBlocked(user.id, true)
            },
            onDismiss = { picking = false },
        )
    }
}
