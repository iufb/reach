package io.github.iufb.reach

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.auth.AuthState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Корневой ViewModel: по состоянию авторизации выбирается граф навигации. */
@HiltViewModel
class AppViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val authState: StateFlow<AuthState> = auth.state
}
