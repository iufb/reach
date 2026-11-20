package io.github.iufb.reach.blocked

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.ContactsRepository
import io.github.iufb.reach.contacts.UserRow
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.ui.theme.reachBarColors
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
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
import javax.inject.Inject

/** Заблокированные пользователи (PRODUCT.md §4.7). */
@HiltViewModel
class BlockedViewModel @Inject constructor(
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
            }.onSuccess { reload() }.onFailure { _errors.emit(it.message.orEmpty()) }
        }
    }

    private companion object {
        const val TAG = "BlockedViewModel"
        const val PAGE = 50
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(onBack: () -> Unit, viewModel: BlockedViewModel = hiltViewModel()) {
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    var picking by rememberSaveable { mutableStateOf(false) }
    var unblocking by remember { mutableStateOf<User?>(null) }
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
                title = { Text(stringResource(R.string.blocked_title)) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { picking = true }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        val list = blocked
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.blocked_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.id }) { user ->
                    UserRow(user, onClick = { unblocking = user }, subtitle = user.phoneNumber.takeIf { it.isNotBlank() }?.let { "+$it" } ?: "")
                }
                item { LaunchedEffect(list.size) { viewModel.loadMore() } }
            }
        }
    }
    unblocking?.let { user ->
        AlertDialog(
            onDismissRequest = { unblocking = null },
            text = { Text(stringResource(R.string.unblock_confirm, user.fullName)) },
            confirmButton = {
                TextButton(onClick = {
                    unblocking = null
                    viewModel.setBlocked(user.id, false)
                }) { Text(stringResource(R.string.unblock)) }
            },
            dismissButton = { TextButton(onClick = { unblocking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.block_pick)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.id }) { user ->
                        UserRow(user, onClick = {
                            picking = false
                            viewModel.setBlocked(user.id, true)
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
