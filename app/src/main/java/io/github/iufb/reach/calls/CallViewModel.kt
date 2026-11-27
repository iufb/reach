package io.github.iufb.reach.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Экран звонка и плашка «Вернуться к звонку»: текущий звонок [CallManager] и собеседник. */
@HiltViewModel
class CallViewModel @Inject constructor(private val calls: CallManager, users: UsersRepository) : ViewModel() {

    val call: StateFlow<CallUi?> = calls.call

    val user: StateFlow<User?> = combine(calls.call, users.users) { call, all -> call?.let { all[it.userId] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, calls.call.value?.let { users.users.value[it.userId] })

    fun hangUp() = calls.hangUp()

    fun toggleMute() = calls.toggleMute()

    fun toggleSpeaker() = calls.toggleSpeaker()

    fun rate(stars: Int) = calls.rate(stars)

    fun dismiss() = calls.dismiss()
}
