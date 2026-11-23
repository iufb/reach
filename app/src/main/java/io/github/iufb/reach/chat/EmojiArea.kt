package io.github.iufb.reach.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Вкладки панели под полем ввода. */
internal enum class PanelTab { Emoji, Stickers }

/** Последняя вкладка — на весь процесс, как в iOS: открыл стикеры — в следующий раз откроются стикеры. */
private val lastPanelTab = mutableStateOf(PanelTab.Emoji)

/** Высота клавиатуры — тоже на весь процесс: от чата она не зависит, и панель в новом чате сразу ей равна. */
private val lastKeyboardPx = mutableIntStateOf(0)

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
    var keyboardPx: Int
        get() = lastKeyboardPx.intValue
        set(value) {
            lastKeyboardPx.intValue = value
        }

    /** Высота нижнего блока в последней раскладке; с неё панель начинает уступать место клавиатуре. */
    internal var lastBlockPx = 0

    /** Высота блока в момент [switchToKeyboard]: из неё блок по ходу клавиатуры переходит в её высоту. */
    internal var yieldFromPx = 0
        private set

    /** Куда вставлять выбранный эмодзи; задаёт поле ввода, пока оно на экране. */
    var onPick: (String) -> Unit = {}

    fun show(keyboardVisible: Boolean) {
        yieldingToKeyboard = false
        open = true
        // Из-под клавиатуры — место уже занято, панель видна сразу; иначе выезжает
        scope.launch { if (keyboardVisible) reveal.snapTo(1f) else reveal.animateTo(1f, tween(REVEAL_MS)) }
    }

    fun switchToKeyboard() {
        // Панель держит место, пока его не займёт клавиатура; когда это случилось, решает ChatBottomArea
        // по фактическим инсетам клавиатуры, а не по времени
        yieldFromPx = lastBlockPx
        open = false
        searching = false
        yieldingToKeyboard = true
    }

    /**
     * Панели больше нечего держать: клавиатура заняла её место ([keyboardShown]) — панели уже не видно,
     * или клавиатура так и не появилась (аппаратная) либо уехала обратно — тогда панель уезжает сама.
     */
    internal fun finishYield(keyboardShown: Boolean) {
        if (!yieldingToKeyboard) return
        yieldingToKeyboard = false
        scope.launch { if (keyboardShown) reveal.snapTo(0f) else reveal.animateTo(0f, tween(REVEAL_MS)) }
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatBottomArea(
    state: EmojiPanelState,
    panelAllowed: Boolean,
    canSendStickers: Boolean,
    stickerContent: @Composable () -> Unit = { StickerTabPlaceholder() },
) {
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    // Откуда и куда едет клавиатура: цель известна с первого кадра анимации, раньше первого сдвига
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val density = LocalDensity.current
    // Высота клавиатуры — по цели её анимации, а не по текущему сдвигу: иначе на первом выезде
    // запомненная высота растёт вместе со сдвигом, и уступающая панель пропадает с первого кадра (#314)
    LaunchedEffect(state) {
        snapshotFlow { imeTarget.getBottom(density) }.collect { if (it > 0) state.keyboardPx = it }
    }
    LaunchedEffect(panelAllowed) { if (!panelAllowed && state.open) state.close() }
    LaunchedEffect(state.yieldingToKeyboard) {
        if (!state.yieldingToKeyboard) return@LaunchedEffect
        val keyboard = snapshotFlow { ime.getBottom(density) to imeTarget.getBottom(density) }
        // Клавиатура так и не тронулась (аппаратная, или система её не показала) — панель уезжает сама
        val started = withTimeoutOrNull(YIELD_START_TIMEOUT_MS) { keyboard.first { (_, targetPx) -> targetPx > 0 } }
        if (started == null) {
            state.finishYield(keyboardShown = false)
            return@LaunchedEffect
        }
        // Держим место, пока клавиатура не встала целиком; передумала и уехала — панель уезжает сама
        val (imePx, targetPx) = withTimeoutOrNull(YIELD_MAX_MS) {
            keyboard.first { (imePx, targetPx) -> targetPx == 0 || imePx >= targetPx }
        } ?: (0 to 0)
        state.finishYield(keyboardShown = targetPx > 0 && imePx >= targetPx)
    }
    val showPanel = panelAllowed && (state.open || state.yieldingToKeyboard || state.reveal.value > 0f)
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val imePx = ime.getBottom(this)
                val navPx = nav.getBottom(this)
                val insetPx = maxOf(imePx, navPx)
                val heightPx = when {
                    !showPanel -> insetPx
                    state.yieldingToKeyboard -> yieldingBlockPx(
                        fromPx = state.yieldFromPx,
                        imePx = imePx,
                        sourcePx = imeSource.getBottom(this),
                        targetPx = imeTarget.getBottom(this),
                        navPx = navPx,
                    )
                    else -> insetPx + panelHeightPx(state, insetPx)
                }
                state.lastBlockPx = heightPx
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
        else -> (freePx * state.reveal.value).toInt()
    }
}

/**
 * Высота нижнего блока, пока панель уступает место клавиатуре. Блок начинает с высоты [fromPx], которая
 * была при переключении, и приходит к высоте клавиатуры [targetPx] по ходу её же анимации
 * (сдвиг [imePx] из [sourcePx] в [targetPx]). Пока клавиатура не тронулась, блок держит [fromPx];
 * высоты совпадают — блок не меняется ни на кадр, а с ним и поле ввода над ним. Не ниже клавиатуры
 * и навигационной полосы ([navPx]).
 */
internal fun yieldingBlockPx(fromPx: Int, imePx: Int, sourcePx: Int, targetPx: Int, navPx: Int): Int {
    val insetPx = maxOf(imePx, navPx)
    if (targetPx <= 0) return maxOf(fromPx, insetPx)
    val progress = if (targetPx == sourcePx) 1f else ((imePx - sourcePx).toFloat() / (targetPx - sourcePx)).coerceIn(0f, 1f)
    return maxOf((fromPx + (targetPx - fromPx) * progress).roundToInt(), insetPx)
}

private val DEFAULT_HEIGHT = 340.dp
private val SEARCH_HEIGHT = 112.dp
private val CORNER = 20.dp
private val TABS_WIDTH = 220.dp
private const val REVEAL_MS = 250

/** Сколько панель ждёт, что клавиатура начнёт выезжать; не начала — клавиатуры не будет (аппаратная). */
private const val YIELD_START_TIMEOUT_MS = 1000L

/** Страховка: клавиатура начала выезжать, но так и не встала на место. */
private const val YIELD_MAX_MS = 2000L
