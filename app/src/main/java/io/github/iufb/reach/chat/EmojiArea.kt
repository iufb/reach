package io.github.iufb.reach.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.emoji.EmojiPanel
import io.github.iufb.reach.ui.components.SegmentedControl
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Вкладки панели под полем ввода. */
internal enum class PanelTab { Emoji, Stickers }

/** Последняя вкладка — на весь процесс, как в iOS: открыл стикеры — в следующий раз откроются стикеры. */
private val lastPanelTab = mutableStateOf(PanelTab.Emoji)

/**
 * Панель эмодзи на месте клавиатуры. Ключевое — лента не прыгает: пока клавиатура уезжает
 * или выезжает, панель занимает ровно освободившееся место (высота клавиатуры минус её текущий сдвиг),
 * так что низ поля ввода стоит на месте.
 */
@Stable
internal class EmojiPanelState(private val scope: CoroutineScope) {
    /** Панель открыта (или открывается). */
    var open by mutableStateOf(false)
        private set

    /** Переключились на клавиатуру: панель уступает место выезжающей клавиатуре, а не исчезает сразу. */
    var yieldingToKeyboard by mutableStateOf(false)
        private set

    /** Фокус в поиске эмодзи: клавиатура открыта, от панели остаётся поиск и строка результатов. */
    var searching by mutableStateOf(false)

    /** Доля раскрытия, когда клавиатуры не было: панель выезжает снизу сама. */
    val reveal = Animatable(0f)

    /** Открытая вкладка; помнится между чатами до конца процесса. Поиск есть только у эмодзи. */
    var tab: PanelTab
        get() = lastPanelTab.value
        set(value) {
            lastPanelTab.value = value
            if (value != PanelTab.Emoji) searching = false
        }

    /** Высота клавиатуры в пикселях, как её видели в последний раз; 0 — ещё не открывалась. */
    var keyboardPx by mutableIntStateOf(0)

    /** Куда вставлять выбранный эмодзи; задаёт поле ввода, пока оно на экране. */
    var onPick: (String) -> Unit = {}

    private var yieldJob: Job? = null

    fun show(keyboardVisible: Boolean) {
        yieldJob?.cancel()
        yieldingToKeyboard = false
        open = true
        // Из-под клавиатуры — место уже занято, панель видна сразу; иначе выезжает
        scope.launch { if (keyboardVisible) reveal.snapTo(1f) else reveal.animateTo(1f, tween(REVEAL_MS)) }
    }

    fun switchToKeyboard() {
        android.util.Log.d("Reach", "dbg314 switchToKeyboard t=${System.nanoTime() / 1_000_000}")
        open = false
        searching = false
        yieldingToKeyboard = true
        yieldJob?.cancel()
        // Клавиатура может и не появиться (аппаратная) — тогда панель всё равно уходит
        yieldJob = scope.launch {
            delay(YIELD_TIMEOUT_MS)
            android.util.Log.d("Reach", "dbg314 yield timeout t=${System.nanoTime() / 1_000_000}")
            yieldingToKeyboard = false
            reveal.snapTo(0f)
        }
    }

    fun close() {
        open = false
        searching = false
        scope.launch { reveal.animateTo(0f, tween(REVEAL_MS)) }
    }
}

@Composable
internal fun rememberEmojiPanelState(): EmojiPanelState {
    val scope = rememberCoroutineScope()
    return remember { EmojiPanelState(scope) }
}

/**
 * Низ экрана чата под полем ввода: клавиатура, панель эмодзи или навигационная полоса — одним блоком.
 * Его высота — max(ime, nav) плюс панель, и при переключении клавиатура ↔ панель сумма постоянна:
 * область ленты и поле над ней не меняют размер ни на кадр. Считается при раскладке, в том же кадре,
 * что и сдвиг клавиатуры. [panelAllowed] `false` — поля ввода нет, панель не нужна.
 * [canSendStickers] `false` — на вкладке «Стикеры» вместо [stickerContent] текст об ограничении.
 * [stickerContent] — содержимое вкладки «Стикеры» (сетка `StickerPanel`; по умолчанию заглушка).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChatBottomArea(
    state: EmojiPanelState,
    panelAllowed: Boolean,
    canSendStickers: Boolean,
    stickerContent: @Composable () -> Unit = { StickerTabPlaceholder() },
) {
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    val density = LocalDensity.current
    LaunchedEffect(state) {
        snapshotFlow { ime.getBottom(density) }.collect { if (it > state.keyboardPx) state.keyboardPx = it }
    }
    LaunchedEffect(panelAllowed) { if (!panelAllowed && state.open) state.close() }
    // DEBUG #314 — удалить до мержа
    run {
        val src = WindowInsets.imeAnimationSource
        val tgt = WindowInsets.imeAnimationTarget
        LaunchedEffect(state) {
            while (true) {
                androidx.compose.runtime.withFrameNanos { t ->
                    val imeB = ime.getBottom(density)
                    val navB = nav.getBottom(density)
                    val inset = maxOf(imeB, navB)
                    val show = panelAllowed && (state.open || state.yieldingToKeyboard || state.reveal.value > 0f)
                    val panel = if (show) density.panelHeightPx(state, inset) else 0
                    if (state.open || state.yieldingToKeyboard || state.reveal.value > 0f || imeB > 0) {
                        android.util.Log.d("Reach", "dbg314 t=${t / 1_000_000} ime=$imeB src=${src.getBottom(density)} tgt=${tgt.getBottom(density)} nav=$navB open=${state.open} yield=${state.yieldingToKeyboard} reveal=${state.reveal.value} kb=${state.keyboardPx} panel=$panel total=${inset + panel}")
                    }
                }
            }
        }
    }
    val showPanel = panelAllowed && (state.open || state.yieldingToKeyboard || state.reveal.value > 0f)
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val insetPx = maxOf(ime.getBottom(this), nav.getBottom(this))
                val panelPx = if (showPanel) panelHeightPx(state, insetPx) else 0
                val heightPx = insetPx + panelPx
                val placeable = measurable.measure(constraints.copy(minHeight = heightPx, maxHeight = heightPx))
                layout(placeable.width, heightPx) { placeable.place(0, 0) }
            },
    ) {
        if (showPanel) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = CORNER, topEnd = CORNER))
                    .background(ReachTheme.colors.cell),
            ) {
                // Панель — верхняя часть блока; нижнюю (под клавиатурой или полосой) панель не занимает
                Box(
                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                        val insetPx = maxOf(ime.getBottom(this), nav.getBottom(this))
                        val panelPx = (constraints.maxHeight - insetPx).coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = panelPx, maxHeight = panelPx))
                        layout(placeable.width, panelPx) { placeable.place(0, 0) }
                    },
                ) {
                    PanelTabs(
                        tab = state.tab,
                        onTabChange = { state.tab = it },
                        // В поиске панель ужата до строки результатов — переключателю там не место
                        showTabs = !state.searching,
                        emojiContent = {
                            EmojiPanel(
                                onPick = { state.onPick(it) },
                                modifier = Modifier.fillMaxSize(),
                                onSearchFocusChange = { state.searching = it && state.open },
                            )
                        },
                        stickerContent = {
                            if (canSendStickers) stickerContent() else PanelMessage(stringResource(R.string.stickers_restricted))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Содержимое панели и переключатель «Эмодзи | Стикеры» под ним, как в Telegram iOS. Высоту задаёт
 * [ChatBottomArea]; смена вкладки меняет только содержимое, а не размер.
 */
@Composable
private fun PanelTabs(
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    showTabs: Boolean,
    emojiContent: @Composable () -> Unit,
    stickerContent: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                PanelTab.Emoji -> emojiContent()
                PanelTab.Stickers -> stickerContent()
            }
        }
        if (showTabs) {
            val tabs = PanelTab.entries
            SegmentedControl(
                items = tabs.map { stringResource(it.label) },
                selected = tabs.indexOf(tab),
                onSelect = { onTabChange(tabs[it]) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp, bottom = 6.dp)
                    .width(TABS_WIDTH),
            )
        }
    }
}

private val PanelTab.label: Int
    get() = when (this) {
        PanelTab.Emoji -> R.string.emoji_open
        PanelTab.Stickers -> R.string.stickers_tab
    }

/** Вкладка «Стикеры» до сетки (#289). */
@Composable
internal fun StickerTabPlaceholder() {
    PanelMessage(stringResource(R.string.stickers_coming_soon))
}

@Composable
private fun PanelMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ReachTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** Сколько панели видно: добирает до высоты клавиатуры, сколько та ещё не заняла. */
private fun Density.panelHeightPx(state: EmojiPanelState, insetPx: Int): Int {
    val targetPx = state.keyboardPx.takeIf { it > 0 } ?: DEFAULT_HEIGHT.roundToPx()
    val freePx = (targetPx - insetPx).coerceAtLeast(0)
    return when {
        state.open && state.searching -> SEARCH_HEIGHT.roundToPx()
        state.yieldingToKeyboard -> freePx
        else -> (freePx * state.reveal.value).toInt()
    }
}

private val DEFAULT_HEIGHT = 340.dp
private val SEARCH_HEIGHT = 112.dp
private val CORNER = 20.dp
private val TABS_WIDTH = 220.dp
private const val REVEAL_MS = 250
private const val YIELD_TIMEOUT_MS = 700L
