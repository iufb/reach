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
import io.github.iufb.reach.chat.ScheduledScreen
import io.github.iufb.reach.chats.ChatsScreen
import io.github.iufb.reach.forward.ForwardScreen
import io.github.iufb.reach.gallery.GalleryScreen
import io.github.iufb.reach.profile.ProfileScreen
import io.github.iufb.reach.search.SearchScreen
import io.github.iufb.reach.settings.AutoDownloadScreen
import kotlinx.serialization.Serializable

@Serializable data object AuthGraph
@Serializable data object PhoneRoute
@Serializable data object CodeRoute
@Serializable data object PasswordRoute
@Serializable data class UnsupportedAuthRoute(val step: String)

@Serializable data object MainGraph
/** Список чатов: основной или архив. */
@Serializable data class ChatsRoute(val archive: Boolean = false)
@Serializable data object SearchRoute
/** Чат; [messageId] не 0 — открыть на этом сообщении (например, из поиска). */
@Serializable data class ChatRoute(val chatId: Long, val messageId: Long = 0)
@Serializable data class ForwardRoute(val fromChatId: Long, val messageIds: List<Long>)
/** Профиль чата ([chatId]) или пользователя ([userId]); второй — 0. */
@Serializable data class ProfileRoute(val chatId: Long = 0, val userId: Long = 0)
@Serializable data class GalleryRoute(val chatId: Long)
@Serializable data object AutoDownloadRoute
@Serializable data class ScheduledRoute(val chatId: Long)

/**
 * Два графа: вход и основной. Какой экран показан, решает [AuthState]:
 * навигация только догоняет состояние TDLib, сама переходов не придумывает.
 */
@Composable
fun ReachNavHost(authState: AuthState, openChatId: Long? = null, onOpenChatHandled: () -> Unit = {}) {
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
        navigation<MainGraph>(startDestination = ChatsRoute()) {
            composable<ChatsRoute> {
                ChatsScreen(
                    onChatClick = { navController.navigate(ChatRoute(it)) },
                    onOpenArchive = { navController.navigate(ChatsRoute(archive = true)) },
                    onOpenSearch = { navController.navigate(SearchRoute) },
                    onBack = { navController.popBackStack() },
                    onOpenAutoDownload = { navController.navigate(AutoDownloadRoute) },
                )
            }
            composable<ChatRoute> {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onForward = { from, ids -> navController.navigate(ForwardRoute(from, ids)) },
                    onOpenChat = { navController.navigate(ChatRoute(it)) },
                    onOpenProfile = { navController.navigate(ProfileRoute(chatId = it)) },
                    onOpenUserProfile = { navController.navigate(ProfileRoute(userId = it)) },
                    onOpenScheduled = { navController.navigate(ScheduledRoute(it)) },
                )
            }
            composable<ScheduledRoute> { ScheduledScreen(onBack = { navController.popBackStack() }) }
            composable<SearchRoute> {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { navController.navigate(ChatRoute(it)) },
                    onOpenMessage = { chatId, messageId -> navController.navigate(ChatRoute(chatId, messageId)) },
                )
            }
            composable<ProfileRoute> {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { navController.navigate(ChatRoute(it)) },
                    onOpenGallery = { navController.navigate(GalleryRoute(it)) },
                )
            }
            composable<AutoDownloadRoute> { AutoDownloadScreen(onBack = { navController.popBackStack() }) }
            composable<GalleryRoute> {
                GalleryScreen(
                    onBack = { navController.popBackStack() },
                    onShowInChat = { chatId, messageId -> navController.navigate(ChatRoute(chatId, messageId)) },
                )
            }
            composable<ForwardRoute> {
                ForwardScreen(
                    onBack = { navController.popBackStack() },
                    // После пересылки — в целевой чат; «назад» из него ведёт в список чатов
                    onForwarded = { navController.navigate(ChatRoute(it)) { popUpTo(ChatsRoute()) } },
                )
            }
        }
    }
    LaunchedEffect(authState) { navController.syncWith(authState) }
    // Тап по уведомлению: открываем чат поверх списка, когда вход уже выполнен
    LaunchedEffect(openChatId, authState) {
        if (openChatId != null && authState == AuthState.Ready) {
            navController.navigate(ChatRoute(openChatId)) { popUpTo(ChatsRoute()) }
            onOpenChatHandled()
        }
    }
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
