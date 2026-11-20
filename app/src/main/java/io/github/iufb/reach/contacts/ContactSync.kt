package io.github.iufb.reach.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.auth.AuthState
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Состояние синхронизации для экрана настроек. */
data class ContactSyncState(
    /** Пользователь включил синхронизацию (opt-in). */
    val enabled: Boolean,
    /** Сколько номеров отправлено в последний раз; `null` — ещё не синхронизировали. */
    val lastCount: Int? = null,
)

/**
 * Синхронизация с телефонной книгой (PRODUCT.md §4.7). Только по явному согласию:
 * включена — при каждом входе и при изменении книги (`ContentObserver`, с паузой) весь список
 * уходит в `changeImportedContacts` (он сам заменяет прежний, без дублей); выключена —
 * `clearImportedContacts`.
 */
@OptIn(FlowPreview::class)
@Singleton
class ContactSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ContactSyncState(prefs.getBoolean(KEY_ENABLED, false)))
    val state: StateFlow<ContactSyncState> = _state.asStateFlow()

    private val bookChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            bookChanged.tryEmit(Unit)
        }
    }
    private var observing = false

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun start() {
        scope.launch {
            combine(auth.state, _state) { auth, sync -> auth == AuthState.Ready && sync.enabled }.collect { active ->
                if (active && hasPermission) {
                    observe(true)
                    sync()
                } else {
                    observe(false)
                }
            }
        }
        scope.launch { bookChanged.debounce(CHANGE_DEBOUNCE_MS).collect { if (_state.value.enabled) sync() } }
    }

    /** Включить после выданного разрешения. */
    fun enable() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _state.value = _state.value.copy(enabled = true)
    }

    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        _state.value = ContactSyncState(enabled = false)
        scope.launch {
            runCatching { td.send(TdApi.ClearImportedContacts()) }.onFailure { Log.w(TAG, "clearImportedContacts failed", it) }
        }
    }

    private fun observe(on: Boolean) {
        if (on == observing) return
        observing = on
        val resolver = context.contentResolver
        if (on) {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        } else {
            resolver.unregisterContentObserver(observer)
        }
    }

    private suspend fun sync() {
        if (!hasPermission) return
        val contacts = runCatching { readBook() }.onFailure { Log.e(TAG, "read contacts failed", it) }.getOrNull() ?: return
        runCatching { td.send(TdApi.ChangeImportedContacts(contacts.toTypedArray())) }
            .onSuccess { _state.value = _state.value.copy(lastCount = contacts.size) }
            .onFailure { Log.w(TAG, "changeImportedContacts failed", it) }
    }

    /** Номера из книги: по одному на номер, повторы (тот же номер у разных записей) убраны. */
    private suspend fun readBook(): List<TdApi.ImportedContact> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val byPhone = LinkedHashMap<String, TdApi.ImportedContact>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val phone = cursor.getString(0)?.filter { it.isDigit() || it == '+' }.orEmpty()
                if (phone.count { it.isDigit() } < MIN_DIGITS || phone in byPhone) continue
                val name = cursor.getString(1).orEmpty().trim()
                val first = name.substringBefore(' ').ifBlank { phone }
                val last = name.substringAfter(' ', "").trim()
                byPhone[phone] = TdApi.ImportedContact(phone, first, last, null)
            }
        }
        byPhone.values.toList()
    }

    private companion object {
        const val TAG = "ContactSync"
        const val PREFS = "contact_sync"
        const val KEY_ENABLED = "enabled"
        const val CHANGE_DEBOUNCE_MS = 5_000L
        const val MIN_DIGITS = 5
    }
}
