package io.github.iufb.reach.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.emoji.EmojiPanel
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        open = false
        searching = false
        yieldingToKeyboard = true
        yieldJob?.cancel()
        // Клавиатура может и не появиться (аппаратная) — тогда панель всё равно уходит
        yieldJob = scope.launch {
            delay(YIELD_TIMEOUT_MS)
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
 */
@Composable
internal fun ChatBottomArea(state: EmojiPanelState, panelAllowed: Boolean) {
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    val density = LocalDensity.current
    LaunchedEffect(state) {
        snapshotFlow { ime.getBottom(density) }.collect { if (it > state.keyboardPx) state.keyboardPx = it }
    }
    LaunchedEffect(panelAllowed) { if (!panelAllowed && state.open) state.close() }
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
                    EmojiPanel(
                        onPick = { state.onPick(it) },
                        modifier = Modifier.fillMaxSize(),
                        onSearchFocusChange = { state.searching = it && state.open },
                    )
                }
            }
        }
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
private const val REVEAL_MS = 250
private const val YIELD_TIMEOUT_MS = 700L
