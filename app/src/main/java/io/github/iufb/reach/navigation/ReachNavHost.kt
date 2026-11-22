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
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.iufb.reach.auth.AuthState
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.links.InviteDialog
import io.github.iufb.reach.links.LinkEvent
import io.github.iufb.reach.links.LinkMessage
import io.github.iufb.reach.links.LinksViewModel
import io.github.iufb.reach.auth.CodeScreen
import io.github.iufb.reach.auth.LoadingScreen
import io.github.iufb.reach.auth.PasswordScreen
import io.github.iufb.reach.auth.PhoneScreen
import io.github.iufb.reach.auth.RegistrationScreen
import io.github.iufb.reach.auth.UnsupportedAuthScreen
import io.github.iufb.reach.chat.ChatScreen
import io.github.iufb.reach.chat.ScheduledScreen
import io.github.iufb.reach.topics.TopicsScreen
import io.github.iufb.reach.contacts.ContactsScreen
import io.github.iufb.reach.contacts.NewMessageScreen
import io.github.iufb.reach.newchat.NewChatScreen
import io.github.iufb.reach.members.MembersScreen
import io.github.iufb.reach.rights.AdminRightsScreen
import io.github.iufb.reach.rights.PermissionsScreen
import io.github.iufb.reach.invites.InviteLinksScreen
import io.github.iufb.reach.stats.StatisticsScreen
import io.github.iufb.reach.blocked.BlockedScreen
import io.github.iufb.reach.contacts.ContactSyncScreen
import io.github.iufb.reach.BuildConfig
import io.github.iufb.reach.settings.SettingsScreen
import io.github.iufb.reach.ui.components.ComponentCatalogScreen
import io.github.iufb.reach.chats.ChatsScreen
import io.github.iufb.reach.forward.ForwardScreen
import io.github.iufb.reach.profile.ProfileScreen
import io.github.iufb.reach.search.SearchScreen
import io.github.iufb.reach.settings.AutoDownloadScreen
import kotlinx.serialization.Serializable

@Serializable data object AuthGraph
@Serializable data object PhoneRoute
@Serializable data object CodeRoute
@Serializable data object PasswordRoute
@Serializable data object RegistrationRoute
@Serializable data class UnsupportedAuthRoute(val step: String)

@Serializable data object MainGraph
/** Список чатов: основной или архив. */
@Serializable data class ChatsRoute(val archive: Boolean = false)
/** [query] — сразу искать это (тап по хэштегу, #221). */
@Serializable data class SearchRoute(val query: String = "")
/** Чат; [messageId] не 0 — открыть на этом сообщении (например, из поиска). */
/** [threadId] — комментарии к посту (в группе обсуждения), [topicId] — топик форума; 0 — весь чат. */
@Serializable data class ChatRoute(val chatId: Long, val messageId: Long = 0, val threadId: Long = 0, val topicId: Int = 0)
@Serializable data class ForwardRoute(val fromChatId: Long, val messageIds: List<Long>)
/** Профиль чата ([chatId]) или пользователя ([userId]); второй — 0. */
@Serializable data class ProfileRoute(val chatId: Long = 0, val userId: Long = 0)
@Serializable data object AutoDownloadRoute
@Serializable data class ScheduledRoute(val chatId: Long)
@Serializable data class TopicsRoute(val chatId: Long)
@Serializable data object ContactsRoute
@Serializable data object NewMessageRoute
@Serializable data object SettingsRoute
@Serializable data object ContactSyncRoute
@Serializable data object BlockedRoute
@Serializable data object ComponentsRoute
@Serializable data class NewChatRoute(val channel: Boolean)
@Serializable data class MembersRoute(val chatId: Long)
@Serializable data class AdminRightsRoute(val chatId: Long, val userId: Long)
/** [userId] = 0 — общие разрешения группы. */
@Serializable data class PermissionsRoute(val chatId: Long, val userId: Long = 0)
@Serializable data class InviteLinksRoute(val chatId: Long)
@Serializable data class StatisticsRoute(val chatId: Long)

/**
 * Два графа: вход и основной. Какой экран показан, решает [AuthState]:
 * навигация только догоняет состояние TDLib, сама переходов не придумывает.
 */
@Composable
fun ReachNavHost(
    authState: AuthState,
    openChatId: Long? = null,
    onOpenChatHandled: () -> Unit = {},
    openLink: String? = null,
    onOpenLinkHandled: () -> Unit = {},
    links: LinksViewModel = hiltViewModel(),
) {
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

    // Ссылки Telegram из сообщений разбирает клиент, остальные — внешнее приложение
    val platformUris = LocalUriHandler.current
    val uriHandler = remember(platformUris, links) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LinksViewModel.isTelegramLink(uri)) links.open(uri) else platformUris.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        NavHost(
            navController,
            startDestination = start,
            enterTransition = navEnter,
            exitTransition = navExit,
            popEnterTransition = navPopEnter,
            popExitTransition = navPopExit,
        ) {
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
                composable<RegistrationRoute> { RegistrationScreen(onChangePhoneNumber = navController::backToPhone) }
                composable<UnsupportedAuthRoute> { UnsupportedAuthScreen(it.toRoute<UnsupportedAuthRoute>().step) }
            }
            navigation<MainGraph>(startDestination = ChatsRoute()) {
                composable<ChatsRoute> {
                    ChatsScreen(
                        onChatClick = { navController.navigate(ChatRoute(it)) },
                        onOpenArchive = { navController.navigate(ChatsRoute(archive = true)) },
                        onOpenSearch = { navController.navigate(SearchRoute()) },
                        onBack = { navController.popBackStack() },
                        onOpenTopics = { navController.navigate(TopicsRoute(it)) },
                        onSelectTab = navController::selectTab,
                        onNewMessage = { navController.navigate(NewMessageRoute) },
                    )
                }
                composable<NewChatRoute> {
                    NewChatScreen(
                        onBack = { navController.popBackStack() },
                        // Созданный чат открывается вместо мастера: «назад» из него — в список
                        onCreated = { navController.navigate(ChatRoute(it)) { popUpTo(ChatsRoute()) } },
                    )
                }
                composable<ContactsRoute> {
                    ContactsScreen(
                        onSelectTab = navController::selectTab,
                        onOpenChat = { navController.navigate(ChatRoute(it)) },
                    )
                }
                composable<NewMessageRoute> {
                    NewMessageScreen(
                        onBack = { navController.popBackStack() },
                        // Чат открывается вместо «Нового сообщения»: «назад» из него — в список
                        onOpenChat = { navController.navigate(ChatRoute(it)) { popUpTo(ChatsRoute()) } },
                        onNewChat = { channel -> navController.navigate(NewChatRoute(channel)) },
                    )
                }
                composable<TopicsRoute> {
                    TopicsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTopic = { chatId, topicId -> navController.navigate(ChatRoute(chatId, topicId = topicId)) },
                        onOpenAsMessages = { navController.navigate(ChatRoute(it)) },
                        onOpenProfile = { navController.navigate(ProfileRoute(chatId = it)) },
                    )
                }
                composable<ChatRoute> { entry ->
                    val forwardedTo by entry.savedStateHandle.getStateFlow<String?>(FORWARDED_TO_KEY, null).collectAsStateWithLifecycle()
                    ChatScreen(
                        forwardedTo = forwardedTo,
                        onForwardedShown = { entry.savedStateHandle[FORWARDED_TO_KEY] = null },
                        onBack = { navController.popBackStack() },
                        onForward = { from, ids -> navController.navigate(ForwardRoute(from, ids)) },
                        onOpenChat = { navController.navigate(ChatRoute(it)) },
                        onOpenProfile = { navController.navigate(ProfileRoute(chatId = it)) },
                        onOpenUserProfile = { navController.navigate(ProfileRoute(userId = it)) },
                        onOpenScheduled = { navController.navigate(ScheduledRoute(it)) },
                        onOpenThread = { chatId, threadId -> navController.navigate(ChatRoute(chatId, threadId = threadId)) },
                        onSearch = { navController.navigate(SearchRoute(it)) },
                    )
                }
                composable<ScheduledRoute> { ScheduledScreen(onBack = { navController.popBackStack() }) }
                composable<SearchRoute> {
                    SearchScreen(
                        entering = { transition.isRunning },
                        onBack = { navController.popBackStack() },
                        onOpenChat = { navController.navigate(ChatRoute(it)) },
                        onOpenMessage = { chatId, messageId -> navController.navigate(ChatRoute(chatId, messageId)) },
                    )
                }
                composable<ProfileRoute> {
                    ProfileScreen(
                        onBack = { navController.popBackStack() },
                        onOpenChat = { navController.navigate(ChatRoute(it)) },
                        onShowInChat = { chatId, messageId -> navController.navigate(ChatRoute(chatId, messageId)) },
                        onOpenMembers = { navController.navigate(MembersRoute(it)) },
                        onOpenPermissions = { navController.navigate(PermissionsRoute(it)) },
                        onOpenInviteLinks = { navController.navigate(InviteLinksRoute(it)) },
                        onOpenStatistics = { navController.navigate(StatisticsRoute(it)) },
                        // Вышли из группы — её чата больше нет: назад к списку
                        onLeft = { navController.popBackStack<ChatsRoute>(inclusive = false) },
                    )
                }
                composable<MembersRoute> {
                    MembersScreen(
                        onBack = { navController.popBackStack() },
                        onOpenUser = { navController.navigate(ProfileRoute(userId = it)) },
                        onEditAdmin = { chatId, userId -> navController.navigate(AdminRightsRoute(chatId, userId)) },
                        onRestrict = { chatId, userId -> navController.navigate(PermissionsRoute(chatId, userId)) },
                    )
                }
                composable<AdminRightsRoute> { AdminRightsScreen(onBack = { navController.popBackStack() }) }
                composable<PermissionsRoute> { PermissionsScreen(onBack = { navController.popBackStack() }) }
                composable<InviteLinksRoute> { InviteLinksScreen(onBack = { navController.popBackStack() }) }
                composable<StatisticsRoute> {
                    val chatId = it.toRoute<StatisticsRoute>().chatId
                    StatisticsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPost = { messageId -> navController.navigate(ChatRoute(chatId, messageId)) },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onSelectTab = navController::selectTab,
                        onOpenAutoDownload = { navController.navigate(AutoDownloadRoute) },
                        onOpenContactSync = { navController.navigate(ContactSyncRoute) },
                        onOpenBlocked = { navController.navigate(BlockedRoute) },
                        // Каталог компонентов — только для проверки дизайна в debug-сборках
                        onOpenComponents = if (BuildConfig.DEBUG) ({ navController.navigate(ComponentsRoute) }) else null,
                    )
                }
                composable<ComponentsRoute> { ComponentCatalogScreen(onBack = { navController.popBackStack() }) }
                composable<BlockedRoute> { BlockedScreen(onBack = { navController.popBackStack() }) }
                composable<ContactSyncRoute> { ContactSyncScreen(onBack = { navController.popBackStack() }) }
                composable<AutoDownloadRoute> { AutoDownloadScreen(onBack = { navController.popBackStack() }) }
                // Шторка поверх чата (Telegram iOS): после пересылки остаёмся в чате, он показывает тост
                dialog<ForwardRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    ForwardScreen(
                        onBack = { navController.popBackStack() },
                        onForwarded = { _, title ->
                            navController.previousBackStackEntry?.savedStateHandle?.set(FORWARDED_TO_KEY, title)
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(authState) { navController.syncWith(authState) }
    LinkEffects(links, navController, authState, openLink, onOpenLinkHandled) { url ->
        runCatching { platformUris.openUri(url) }
    }
    // Тап по уведомлению: открываем чат поверх списка, когда вход уже выполнен
    LaunchedEffect(openChatId, authState) {
        if (openChatId != null && authState == AuthState.Ready) {
            navController.navigate(ChatRoute(openChatId)) { popUpTo(ChatsRoute()) }
            onOpenChatHandled()
        }
    }
}

/** Ссылка из другого приложения (после входа), переходы по ссылкам и превью приглашения. */
@Composable
private fun LinkEffects(
    links: LinksViewModel,
    navController: NavController,
    authState: AuthState,
    openLink: String?,
    onOpenLinkHandled: () -> Unit,
    openExternal: (String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(openLink, authState) {
        if (openLink != null && authState == AuthState.Ready) {
            links.open(openLink)
            onOpenLinkHandled()
        }
    }
    LaunchedEffect(links) {
        links.events.collect { event ->
            when (event) {
                is LinkEvent.OpenChat -> navController.navigate(ChatRoute(event.chatId, event.messageId))
                is LinkEvent.External -> openExternal(event.url)
                is LinkEvent.Message -> Toast.makeText(
                    context,
                    when (event.text) {
                        LinkMessage.RequestSent -> R.string.invite_request_sent
                        LinkMessage.Invalid -> R.string.invite_invalid
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val invite by links.invite.collectAsStateWithLifecycle()
    invite?.let { InviteDialog(it, onJoin = links::join, onDismiss = links::dismissInvite) }
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

        is AuthState.WaitRegistration -> if (current?.hasRoute<RegistrationRoute>() != true) {
            navigate(RegistrationRoute) { popUpTo<PhoneRoute>() }
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

/** Название чата, куда только что переслали: чат-источник показывает тост. */
private const val FORWARDED_TO_KEY = "forwarded_to"
