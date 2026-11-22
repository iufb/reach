package io.github.iufb.reach.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import io.github.iufb.reach.ui.components.TabBarAction
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
class MainTabsViewModel @Inject constructor(chats: ChatsRepository) : ViewModel() {
    val unreadChats: StateFlow<Int> = chats.chats
        .map(::countUnread)
        // Начальное значение — сразу из текущих чатов: бейдж не мигает при переключении вкладок
        .stateIn(viewModelScope, SharingStarted.Eagerly, countUnread(chats.chats.value))

    private fun countUnread(all: Map<Long, Chat>): Int =
        all.values.count { it.positions.containsKey(ChatListType.Main) && it.unreadCount > 0 && !it.isMuted }
}

/** Последняя показанная вкладка: с неё линза приезжает на новый экран-вкладку. */
private object LastMainTab {
    var value: MainTab? = null
}

/**
 * Tab bar «Контакты / Чаты / Настройки» и справа кнопка поиска, как в Telegram iOS 26. Каждый экран-вкладка
 * рисует его у себя, поэтому при переходе вглубь он уезжает вместе с экраном; линза выбранной вкладки
 * приезжает с прошлой вкладки ([LastMainTab]).
 */
@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MainTabsViewModel = hiltViewModel()
    val unread by viewModel.unreadChats.collectAsStateWithLifecycle()
    val previous = remember { LastMainTab.value }
    SideEffect { LastMainTab.value = selected }
    ReachTabBar(
        items = listOf(
            TabItem(painterResource(R.drawable.ic_tab_contacts), stringResource(R.string.contacts_title)),
            TabItem(painterResource(R.drawable.ic_tab_chats), stringResource(R.string.chats_title), badge = unread),
            TabItem(painterResource(R.drawable.ic_tab_settings), stringResource(R.string.settings_title)),
        ),
        selected = selected.ordinal,
        onSelect = { index -> MainTab.entries[index].takeIf { it != selected }?.let(onSelect) },
        modifier = modifier,
        action = TabBarAction(painterResource(R.drawable.ic_search), stringResource(R.string.search_hint), onSearch),
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
