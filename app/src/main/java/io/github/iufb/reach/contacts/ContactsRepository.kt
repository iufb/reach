package io.github.iufb.reach.contacts

import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UserStatus
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Результат добавления по номеру. */
sealed interface ImportResult {
    data class Added(val userId: Long) : ImportResult
    /** Номер не зарегистрирован в Telegram. */
    data object NotFound : ImportResult
}

/**
 * Контакты (PRODUCT.md §4.7). Список — из `getContacts`; дальше флаг `is_contact` у пользователей
 * обновляется апдейтами `UpdateUser`, поэтому список собирается из [UsersRepository].
 */
@Singleton
class ContactsRepository @Inject constructor(
    private val td: TdClient,
    private val users: UsersRepository,
) {
    private val loaded = MutableStateFlow(false)

    /** Сначала в сети, потом по времени последнего захода, потом по имени; `null` — ещё не загружены. */
    val contacts: Flow<List<User>?> = combine(users.users, loaded) { all, ready ->
        if (!ready) null else all.values.filter { it.isContact }.sortedWith(CONTACT_ORDER)
    }

    suspend fun load() {
        td.send(TdApi.GetContacts())
        loaded.value = true
    }

    /** Добавить или переименовать: у Telegram это один запрос. */
    suspend fun save(userId: Long, firstName: String, lastName: String, sharePhone: Boolean = false) {
        val phone = users.users.value[userId]?.phoneNumber.orEmpty()
        td.send(TdApi.AddContact(userId, TdApi.ImportedContact(phone, firstName.trim(), lastName.trim(), null), sharePhone))
    }

    suspend fun remove(userId: Long) {
        td.send(TdApi.RemoveContacts(longArrayOf(userId)))
    }

    suspend fun importByPhone(phone: String, firstName: String, lastName: String): ImportResult {
        val contact = TdApi.ImportedContact(phone.filter { it.isDigit() || it == '+' }, firstName.trim(), lastName.trim(), null)
        val result = td.send(TdApi.ImportContacts(arrayOf(contact)))
        val id = result.userIds.firstOrNull() ?: 0L
        return if (id == 0L) ImportResult.NotFound else ImportResult.Added(id)
    }

    private companion object {
        val CONTACT_ORDER = compareByDescending<User> { it.status.rank() }
            .thenByDescending { (it.status as? UserStatus.Offline)?.wasOnline ?: 0 }
            .thenBy { it.fullName.lowercase() }

        fun UserStatus.rank(): Int = when (this) {
            is UserStatus.Online -> 5
            is UserStatus.Offline -> 4
            UserStatus.Recently -> 3
            UserStatus.LastWeek -> 2
            UserStatus.LastMonth -> 1
            UserStatus.Unknown -> 0
        }
    }
}
