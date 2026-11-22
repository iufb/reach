package io.github.iufb.reach.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatListType
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.ui.components.ReachTabBar
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import io.github.iufb.reach.ui.components.TabItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Вкладки главного экрана, слева направо. */
enum class MainTab { Contacts, Chats, Settings }

/** Бейдж вкладки «Чаты»: сколько чатов основного списка с непрочитанным и со звуком. */
@HiltViewModel
class MainTabsViewModel @Inject constructor(chats: ChatsRepository, users: UsersRepository, td: TdClient) : ViewModel() {
    // У каждого экрана-вкладки своя ViewModel: id запоминаем на процесс, чтобы аватар не мигал при переключении
    private val myId = MutableStateFlow(cachedMyId)

    /** Свой профиль: аватар на вкладке «Настройки». */
    val me: StateFlow<User?> = combine(users.users, myId) { all, id -> all[id] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, users.users.value[cachedMyId])

    init {
        viewModelScope.launch {
            val id = runCatching { td.send(TdApi.GetOption("my_id")) }.getOrNull() as? TdApi.OptionValueInteger
            myId.value = id?.value ?: 0L
            cachedMyId = myId.value
        }
    }

    val unreadChats: StateFlow<Int> = chats.chats
        .map(::countUnread)
        // Начальное значение — сразу из текущих чатов: бейдж не мигает при переключении вкладок
        .stateIn(viewModelScope, SharingStarted.Eagerly, countUnread(chats.chats.value))

    private fun countUnread(all: Map<Long, Chat>): Int =
        all.values.count { it.positions.containsKey(ChatListType.Main) && it.unreadCount > 0 && !it.isMuted }

    private companion object {
        var cachedMyId = 0L
    }
}

/** Последняя показанная вкладка: с неё линза приезжает на новый экран-вкладку. */
private object LastMainTab {
    var value: MainTab? = null
}

/**
 * Tab bar «Контакты / Чаты / Настройки» как в Telegram iOS 26: на «Настройках» — свой аватар. Каждый экран-вкладка
 * рисует его у себя, поэтому при переходе вглубь он уезжает вместе с экраном; линза выбранной вкладки
 * приезжает с прошлой вкладки ([LastMainTab]).
 */
@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MainTabsViewModel = hiltViewModel()
    val unread by viewModel.unreadChats.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val previous = remember { LastMainTab.value }
    SideEffect { LastMainTab.value = selected }
    ReachTabBar(
        items = listOf(
            TabItem(painterResource(R.drawable.ic_tab_contacts), stringResource(R.string.contacts_title)),
            TabItem(painterResource(R.drawable.ic_tab_chats), stringResource(R.string.chats_title), badge = unread),
            TabItem(
                painterResource(R.drawable.ic_tab_settings),
                stringResource(R.string.settings_title),
                image = me?.let { user -> { Avatar(user.fullName, user.photo, user.accentColorId, size = 26.dp) } },
            ),
        ),
        selected = selected.ordinal,
        onSelect = { index -> MainTab.entries[index].takeIf { it != selected }?.let(onSelect) },
        modifier = modifier,
        previous = previous?.ordinal,
    )
}

/**
 * Переключение вкладки: «Чаты» — корень стека, остальные ложатся поверх него одной записью.
 * «Назад» с любой вкладки возвращает в чаты, повторно вкладка в стек не попадает.
 */
fun NavController.selectTab(tab: MainTab) {
    when (tab) {
        MainTab.Chats -> popBackStack(ChatsRoute(), inclusive = false)
        MainTab.Contacts -> navigate(ContactsRoute) { popUpTo(ChatsRoute()); launchSingleTop = true }
        MainTab.Settings -> navigate(SettingsRoute) { popUpTo(ChatsRoute()); launchSingleTop = true }
    }
}
