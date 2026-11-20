package io.github.iufb.reach.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.UsernameStatus
import io.github.iufb.reach.chats.checkUsername
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.files.MediaImporter
import io.github.iufb.reach.push.PushRegistrar
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Свой профиль в настройках. */
data class MyProfile(val user: User, val bio: String, val hasPhoto: Boolean)

/** Настройки: свой профиль и вход в остальные экраны (PRODUCT.md §4.7). */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val td: TdClient,
    users: UsersRepository,
    private val chats: ChatsRepository,
    private val importer: MediaImporter,
    private val auth: AuthRepository,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {

    private val myId = MutableStateFlow(0L)
    private val full = MutableStateFlow<TdApi.UserFullInfo?>(null)
    private var photoId = 0L

    val profile: StateFlow<MyProfile?> = combine(users.users, myId, full) { all, id, info ->
        all[id]?.let { MyProfile(it, info?.bio?.text.orEmpty(), it.photo != null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Максимум символов в «О себе» (`bio_length_max`). */
    val bioMax: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        val value = runCatching { td.send(TdApi.GetOption("bio_length_max")) }.getOrNull()
        emit((value as? TdApi.OptionValueInteger)?.value?.toInt() ?: DEFAULT_BIO_MAX)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DEFAULT_BIO_MAX)

    /** Введённый в диалоге username; проверяется по мере ввода. */
    val usernameInput = MutableStateFlow<String?>(null)

    val usernameStatus: StateFlow<UsernameStatus?> = usernameInput
        .debounce(USERNAME_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { input ->
            val name = input?.trim()?.removePrefix("@") ?: return@mapLatest null
            if (name == profile.value?.user?.username) return@mapLatest UsernameStatus.Ok
            runCatching { td.checkUsername(chats.savedMessagesChatId(), name) }
                .onFailure { Log.w(TAG, "checkChatUsername failed", it) }
                .getOrDefault(UsernameStatus.Invalid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    init {
        viewModelScope.launch {
            val id = (runCatching { td.send(TdApi.GetOption("my_id")) }.getOrNull() as? TdApi.OptionValueInteger)?.value ?: return@launch
            myId.value = id
            loadFull()
        }
        viewModelScope.launch {
            td.updates.collect { update ->
                if (update is TdApi.UpdateUserFullInfo && update.userId == myId.value) {
                    full.value = update.userFullInfo
                    photoId = update.userFullInfo.photo?.id ?: 0L
                }
            }
        }
    }

    private suspend fun loadFull() {
        runCatching { td.send(TdApi.GetUserFullInfo(myId.value)) }
            .onSuccess {
                full.value = it
                photoId = it.photo?.id ?: 0L
            }
            .onFailure { Log.w(TAG, "getUserFullInfo failed", it) }
    }

    fun setName(first: String, last: String) = act { td.send(TdApi.SetName(first.trim(), last.trim())) }

    fun setBio(bio: String) = act {
        td.send(TdApi.SetBio(bio.trim()))
        loadFull()
    }

    fun setUsername(username: String) = act { td.send(TdApi.SetUsername(username.trim().removePrefix("@"))) }

    fun setPhoto(uri: Uri) = act {
        val path = importer.importSquarePhoto(uri)
        td.send(TdApi.SetProfilePhoto(TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(path)), false))
        loadFull()
    }

    fun deletePhoto() = act {
        if (photoId == 0L) loadFull()
        if (photoId != 0L) td.send(TdApi.DeleteProfilePhoto(photoId))
        loadFull()
    }

    fun logOut() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            runCatching {
                // Сначала отвязываем push: после выхода сессии уже нет
                pushRegistrar.unregister()
                auth.logOut()
            }.onFailure {
                Log.e(TAG, "logOut failed", it)
                _loggingOut.value = false
                _errors.emit(it.message.orEmpty())
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "settings action failed", it)
                _errors.emit(it.message.orEmpty())
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val USERNAME_DEBOUNCE_MS = 400L
        const val DEFAULT_BIO_MAX = 70
    }
}
