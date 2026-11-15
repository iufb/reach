package io.github.iufb.reach.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.iufb.reach.auth.AuthState
import io.github.iufb.reach.auth.CodeScreen
import io.github.iufb.reach.auth.LoadingScreen
import io.github.iufb.reach.auth.PasswordScreen
import io.github.iufb.reach.auth.PhoneScreen
import io.github.iufb.reach.auth.UnsupportedAuthScreen
import io.github.iufb.reach.chat.ChatScreen
import io.github.iufb.reach.chats.ChatsScreen
import kotlinx.serialization.Serializable

@Serializable data object AuthGraph
@Serializable data object PhoneRoute
@Serializable data object CodeRoute
@Serializable data object PasswordRoute
@Serializable data class UnsupportedAuthRoute(val step: String)

@Serializable data object MainGraph
@Serializable data object ChatsRoute
@Serializable data class ChatRoute(val chatId: Long)

/**
 * Два графа: вход и основной. Какой экран показан, решает [AuthState]:
 * навигация только догоняет состояние TDLib, сама переходов не придумывает.
 */
@Composable
fun ReachNavHost(authState: AuthState) {
    // Стартовый граф — по первому известному состоянию: при повторном запуске
    // с сохранённой сессией экран входа не мелькает.
    var startGraph by remember { mutableStateOf<Any?>(null) }
    val start = startGraph ?: when (authState) {
        AuthState.Loading -> {
            LoadingScreen()
            return
        }
        AuthState.Ready -> MainGraph
        else -> AuthGraph
    }.also { startGraph = it }

    val navController = rememberNavController()
    val currentAuthState by rememberUpdatedState(authState)

    NavHost(navController, startDestination = start) {
        navigation<AuthGraph>(startDestination = PhoneRoute) {
            composable<PhoneRoute> {
                // Тот же номер повторно может дать равное состояние, и StateFlow его не пришлёт —
                // поэтому после успешной отправки догоняем состояние явно.
                PhoneScreen(onSubmitted = { navController.syncWith(currentAuthState) })
            }
            composable<CodeRoute> {
                // Назад к уже введённому номеру. TDLib разрешает setAuthenticationPhoneNumber и в WaitCode.
                CodeScreen(onChangePhoneNumber = navController::backToPhone)
            }
            // В WaitPassword TDLib тоже принимает setAuthenticationPhoneNumber
            composable<PasswordRoute> { PasswordScreen(onChangePhoneNumber = navController::backToPhone) }
            composable<UnsupportedAuthRoute> { UnsupportedAuthScreen(it.toRoute<UnsupportedAuthRoute>().step) }
        }
        navigation<MainGraph>(startDestination = ChatsRoute) {
            composable<ChatsRoute> { ChatsScreen(onChatClick = { navController.navigate(ChatRoute(it)) }) }
            composable<ChatRoute> { ChatScreen(onBack = { navController.popBackStack() }) }
        }
    }
    LaunchedEffect(authState) { navController.syncWith(authState) }
}

/** Назад к уже введённому номеру. */
private fun NavController.backToPhone() {
    if (!popBackStack<PhoneRoute>(inclusive = false)) navigate(PhoneRoute)
}

private fun NavController.syncWith(state: AuthState) {
    val current = currentDestination
    when (state) {
        AuthState.Loading -> Unit

        AuthState.WaitPhoneNumber -> if (current?.hasRoute<PhoneRoute>() != true) {
            navigate(PhoneRoute) { popUpTo(graph.id) { inclusive = true } }
        }

        is AuthState.WaitCode -> if (current?.hasRoute<CodeRoute>() != true) {
            navigate(CodeRoute) { popUpTo<PhoneRoute>() }
        }

        is AuthState.WaitPassword -> if (current?.hasRoute<PasswordRoute>() != true) {
            navigate(PasswordRoute) { popUpTo<PhoneRoute>() }
        }

        is AuthState.Unsupported -> navigate(UnsupportedAuthRoute(state.step)) {
            popUpTo<PhoneRoute>()
            launchSingleTop = true
        }

        AuthState.Ready -> if (current?.hierarchy?.any { it.hasRoute<MainGraph>() } != true) {
            navigate(MainGraph) { popUpTo(graph.id) { inclusive = true } }
        }
    }
}
