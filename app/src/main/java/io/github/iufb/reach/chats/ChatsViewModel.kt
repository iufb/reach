package io.github.iufb.reach.chats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _loggingOut = MutableStateFlow(false)

    /** Выход запрошен: ждём, пока TDLib закроет сессию и навигация уведёт на экран входа. */
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    fun logOut() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            runCatching { auth.logOut() }.onFailure {
                Log.e(TAG, "logOut failed", it)
                _loggingOut.value = false
            }
        }
    }

    private companion object {
        const val TAG = "ChatsViewModel"
    }
}
