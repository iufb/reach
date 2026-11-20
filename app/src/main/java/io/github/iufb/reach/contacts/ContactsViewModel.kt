package io.github.iufb.reach.contacts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Экран контактов: список, поиск, добавление по номеру. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val td: TdClient,
    private val contacts: ContactsRepository,
    private val users: UsersRepository,
) : ViewModel() {

    val query = MutableStateFlow("")

    /** Найденные `searchContacts` id; `null` — поиска нет. */
    private val found: StateFlow<List<Long>?> = query
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { q ->
            if (q.isBlank()) null else runCatching { td.send(TdApi.SearchContacts(q.trim(), SEARCH_LIMIT)).userIds.toList() }
                .onFailure { Log.w(TAG, "searchContacts failed", it) }
                .getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** `null` — ещё грузятся. */
    val list: StateFlow<List<User>?> = combine(contacts.contacts, found, users.users) { all, ids, known ->
        when {
            all == null -> null
            ids == null -> all
            else -> ids.mapNotNull(known::get)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _openChat = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openChat: SharedFlow<Long> = _openChat.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _notFound = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Номер не в Telegram. */
    val notFound: SharedFlow<Unit> = _notFound.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching { contacts.load() }.onFailure {
                Log.w(TAG, "getContacts failed", it)
                _errors.emit(it.message.orEmpty())
            }
        }
    }

    fun open(user: User) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.CreatePrivateChat(user.id, false)) }
                .onSuccess { _openChat.emit(it.id) }
                .onFailure { _errors.emit(it.message.orEmpty()) }
        }
    }

    fun addByPhone(phone: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            runCatching { contacts.importByPhone(phone, firstName, lastName) }
                .onSuccess { result ->
                    when (result) {
                        is ImportResult.Added -> runCatching { td.send(TdApi.CreatePrivateChat(result.userId, false)) }
                            .onSuccess { _openChat.emit(it.id) }
                        ImportResult.NotFound -> _notFound.emit(Unit)
                    }
                }
                .onFailure { _errors.emit(it.message.orEmpty()) }
        }
    }

    private companion object {
        const val TAG = "ContactsViewModel"
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_LIMIT = 50
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
