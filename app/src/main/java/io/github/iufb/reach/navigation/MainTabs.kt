package io.github.iufb.reach.navigation

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.iufb.reach.ui.components.GlassScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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

/** Экран вкладки внутри [MainTabsHost]: свою копию таббара он не рисует — таббар у хоста один. */
val LocalInTabsHost = staticCompositionLocalOf { false }

/**
 * Главные вкладки «Контакты / Чаты / Настройки» в одном месте навигации (#395). Экраны вкладок
 * собираются один раз и живут дальше: «Чаты» — сразу, остальные — в фоне через [PREWARM_DELAY_MS]
 * или при первом выборе. Скрытые вкладки размещены за правым краем экрана: они уже нарисованы, и
 * при переключении их не надо ни собирать, ни рисовать с нуля — первый кадр новой вкладки дешёвый,
 * линза таббара летит без замирания (#404; раньше скрытые не размещались, и первая отрисовка
 * вкладки останавливала анимацию на 150–200 мс). Содержимое меняется быстрым кроссфейдом, как в
 * Telegram iOS 26: новая вкладка проявляется поверх старой за [CONTENT_FADE_MS]. Таббар один, поверх вкладок, в общем [GlassScope]:
 * экраны вкладок делят его (`shareParent`), и стекло размывает видимую вкладку.
 * «Назад» с «Контактов» и «Настроек» — в «Чаты».
 */
@Composable
fun MainTabsHost(
    chats: @Composable (onSelectTab: (MainTab) -> Unit) -> Unit,
    contacts: @Composable (onSelectTab: (MainTab) -> Unit) -> Unit,
    settings: @Composable (onSelectTab: (MainTab) -> Unit) -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(MainTab.Chats.name) }
    val selected = MainTab.valueOf(selectedName)
    var builtNames by rememberSaveable { mutableStateOf(listOf(MainTab.Chats.name)) }
    // Видимость каждой вкладки: 1 — выбранная, 0 — за краем экрана; между ними — кроссфейд
    val shown = remember { MainTab.entries.map { Animatable(if (it.name == selectedName) 1f else 0f) } }
    // Вкладка, с которой уходим: во время кроссфейда она остаётся на экране под новой
    var leaving by remember { mutableStateOf<MainTab?>(null) }
    val scope = rememberCoroutineScope()
    val select: (MainTab) -> Unit = { tab ->
        if (tab.name !in builtNames) builtNames = builtNames + tab.name
        val from = MainTab.valueOf(selectedName)
        if (tab != from) {
            selectedName = tab.name
            leaving = from
            scope.launch {
                // Новая проявляется поверх старой; старая исчезает, когда новая уже непрозрачна
                MainTab.entries.forEach { if (it != tab && it != from) shown[it.ordinal].snapTo(0f) }
                shown[tab.ordinal].snapTo(0f)
                shown[tab.ordinal].animateTo(1f, tween(CONTENT_FADE_MS, easing = LinearEasing))
                if (MainTab.valueOf(selectedName) == tab) {
                    shown[from.ordinal].snapTo(0f)
                    leaving = null
                }
            }
        }
    }
    // Остальные вкладки — заранее, когда список чатов уже на экране: первое переключение тоже без паузы
    LaunchedEffect(Unit) {
        delay(PREWARM_DELAY_MS)
        builtNames = MainTab.entries.map { it.name }
    }
    BackHandler(enabled = selected != MainTab.Chats) { select(MainTab.Chats) }
    CompositionLocalProvider(LocalInTabsHost provides true) {
        GlassScope {
            Box(Modifier.fillMaxSize()) {
                Layout(
                    content = {
                        MainTab.entries.forEach { tab ->
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = shown[tab.ordinal].value }
                                    // Ушедшая за край вкладка не видна TalkBack
                                    .then(if (tab == selected) Modifier else Modifier.clearAndSetSemantics {}),
                            ) {
                                if (tab.name in builtNames) {
                                    when (tab) {
                                        MainTab.Chats -> chats(select)
                                        MainTab.Contacts -> contacts(select)
                                        MainTab.Settings -> settings(select)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { measurables, constraints ->
                    // Размещаются все собранные вкладки. Скрытые — за правым краем: нарисованы заранее, но не
                    // видны, не получают касаний и не попадают под стекло таббара. Выбранная — сверху
                    val placeables = measurables.map { it.measure(constraints) }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val onScreen = listOfNotNull(leaving, selected)
                        MainTab.entries.forEach { tab ->
                            if (tab !in onScreen) placeables[tab.ordinal].place(constraints.maxWidth, 0)
                        }
                        onScreen.forEach { placeables[it.ordinal].place(0, 0) }
                    }
                }
                HostTabBar(selected, select, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * Таббар вкладки. Внутри [MainTabsHost] ничего не рисует — таббар у хоста один; экраны вкладок
 * оставляют вызов ради отступа снизу под ним.
 */
@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    if (LocalInTabsHost.current) return
    HostTabBar(selected, onSelect, modifier)
}

/** Tab bar «Контакты / Чаты / Настройки» как в Telegram iOS 26: на «Настройках» — свой аватар. */
@Composable
private fun HostTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MainTabsViewModel = hiltViewModel()
    val unread by viewModel.unreadChats.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
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
    )
}

/** Кроссфейд содержимого при смене вкладки — по записи Telegram iOS 26 (#404). */
private const val CONTENT_FADE_MS = 100

/** Через сколько после появления хоста собрать остальные вкладки в фоне. */
private const val PREWARM_DELAY_MS = 1_500L
