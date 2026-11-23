package io.github.iufb.reach.stickers

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.ui.components.DialogSystemBars
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Стикер в превью; [recent] — взят из секции «Недавние» (есть «Убрать из недавних»). */
data class StickerPick(val sticker: StickerInfo, val recent: Boolean = false)

/** Пункты меню под превью, по порядку. */
enum class StickerAction { Send, AddFavorite, RemoveFavorite, OpenSet, RemoveRecent }

/**
 * Пункты меню превью: «Отправить» — если есть куда ([canSend]), избранное — всегда,
 * «Открыть пак» — если стикер из пака и лист пака не открыт уже ([canOpenSet]),
 * «Убрать из недавних» — для стикера из «Недавних».
 */
fun stickerActions(pick: StickerPick, favorite: Boolean, canSend: Boolean, canOpenSet: Boolean): List<StickerAction> = buildList {
    if (canSend) add(StickerAction.Send)
    add(if (favorite) StickerAction.RemoveFavorite else StickerAction.AddFavorite)
    if (canOpenSet && pick.sticker.setId != 0L) add(StickerAction.OpenSet)
    if (pick.recent) add(StickerAction.RemoveRecent)
}

/**
 * Превью стикера по долгому нажатию в сетке (панель, лист пака). [pick] — что показано;
 * [dragging] — палец ещё на экране: превью переключается на стикер под пальцем, меню скрыто.
 */
@Stable
class StickerPreviewState internal constructor(private val haptic: HapticFeedback) {
    var pick by mutableStateOf<StickerPick?>(null)
        private set
    var dragging by mutableStateOf(false)
        private set

    /** Долгое нажатие на ячейку (вибрацию делает сам `combinedClickable`). */
    fun start(pick: StickerPick) {
        this.pick = pick
        dragging = true
    }

    internal fun moveTo(next: StickerPick) {
        if (next.sticker.file.uniqueId == pick?.sticker?.file?.uniqueId) return
        pick = next
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    internal fun release() {
        dragging = false
    }

    fun dismiss() {
        pick = null
        dragging = false
    }
}

@Composable
fun rememberStickerPreviewState(): StickerPreviewState {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { StickerPreviewState(haptic) }
}

/**
 * Ячейка-стикер с тапом [onClick] и долгим нажатием — превью [pick] в [state].
 */
fun Modifier.stickerCellGestures(state: StickerPreviewState, pick: StickerPick, onClick: (() -> Unit)?): Modifier =
    combinedClickable(role = Role.Button, onClick = { onClick?.invoke() }, onLongClick = { state.start(pick) })

/**
 * Ведение пальцем по сетке [grid] после долгого нажатия: превью переключается на стикер под пальцем
 * ([pickAt] по ключу элемента сетки), отпускание показывает меню. Пока превью ведётся, касания
 * перехватываются раньше сетки и ячеек — сетка не прокручивается, ячейка не нажимается.
 * Вешается на саму сетку; [contentPadding] — её отступы (смещения элементов считаются от них).
 */
@Composable
fun Modifier.stickerPreviewDrag(
    state: StickerPreviewState,
    grid: LazyGridState,
    contentPadding: PaddingValues,
    pickAt: (key: Any) -> StickerPick?,
): Modifier {
    val currentPickAt by rememberUpdatedState(pickAt)
    val startPadding = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    return pointerInput(state, grid, startPadding) {
        val start = startPadding.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!state.dragging) continue
                event.changes.forEach { it.consume() }
                val change = event.changes.firstOrNull() ?: continue
                if (!change.pressed) {
                    state.release()
                } else {
                    gridKeyAt(grid.layoutInfo, change.position, start)?.let(currentPickAt)?.let(state::moveTo)
                }
            }
        }
    }
}

/** Ключ элемента сетки под точкой [position] в координатах сетки; смещения элементов — от отступов. */
private fun gridKeyAt(layout: LazyGridLayoutInfo, position: Offset, startPadding: Float): Any? {
    val x = position.x - startPadding
    val y = position.y - layout.beforeContentPadding
    return layout.visibleItemsInfo.firstOrNull { item ->
        x >= item.offset.x && x < item.offset.x + item.size.width &&
            y >= item.offset.y && y < item.offset.y + item.size.height
    }?.key
}

/** Действия превью: избранное и недавние из [StickersRepository]. */
@HiltViewModel
class StickerPreviewViewModel @Inject constructor(
    private val stickers: StickersRepository,
    private val userErrors: UserErrors,
) : ViewModel() {

    /** `uniqueId` файлов избранных стикеров. */
    val favorites: StateFlow<Set<String>> = stickers.favorites
        .map { list -> list.mapTo(HashSet()) { it.file.uniqueId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun setFavorite(sticker: StickerInfo, favorite: Boolean) = launchAction {
        if (favorite) stickers.addFavorite(sticker) else stickers.removeFavorite(sticker)
    }

    fun removeRecent(sticker: StickerInfo) = launchAction { stickers.removeRecent(sticker) }

    private fun launchAction(action: suspend () -> Unit) {
        viewModelScope.launch { runCatching { action() }.onFailure { _errors.tryEmit(userErrors.message(it)) } }
    }
}

/**
 * Превью стикера, как в Telegram iOS: стикер крупно по центру (анимация играет) над затемнённым
 * фоном, под ним — карточка действий в стиле меню сообщения. Пока палец на экране, меню прозрачно,
 * но место под него занято — стикер не прыгает при отпускании. Тап мимо — закрыть.
 *
 * Фон — непрозрачная на 85% заливка цветом фона темы, без размытия: живой blur всего экрана
 * на API 31 пересчитывается каждый кадр под анимированными ячейками (#304).
 *
 * [onSend] `null` — отправлять некуда; [onOpenSet] `null` — «Открыть пак» не нужен (уже в листе пака).
 */
@Composable
fun StickerPreview(
    state: StickerPreviewState,
    onSend: ((StickerInfo) -> Unit)?,
    onOpenSet: ((StickerInfo) -> Unit)?,
) {
    // Последний показанный стикер держится, пока превью гаснет
    var shown by remember { mutableStateOf<StickerPick?>(null) }
    val progress = remember { Animatable(0f) }
    val current = state.pick
    LaunchedEffect(current) {
        if (current != null) {
            shown = current
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        } else if (shown != null) {
            progress.animateTo(0f, tween(CLOSE_MS))
            shown = null
        }
    }
    val pick = current ?: shown ?: return

    val viewModel: StickerPreviewViewModel = hiltViewModel()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val colors = ReachTheme.colors

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Своё затемнение вместо оконного: оно появляется вместе со стикером
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        DialogSystemBars()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(colors.background.copy(alpha = SCRIM_ALPHA * progress.value.coerceIn(0f, 1f))) }
                .clickable(remember { MutableInteractionSource() }, indication = null) { if (!state.dragging) state.dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val side = (maxWidth - PREVIEW_MARGIN * 2).coerceAtMost(PREVIEW_MAX)
            val menuAlpha by animateFloatAsState(if (state.dragging) 0f else 1f, tween(MENU_FADE_MS), label = "menu")
            Column(
                Modifier.graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = APPEAR_SCALE + (1f - APPEAR_SCALE) * p
                    scaleY = APPEAR_SCALE + (1f - APPEAR_SCALE) * p
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                key(pick.sticker.file.uniqueId) {
                    StickerImage(
                        pick.sticker,
                        Modifier.size(side),
                        emojiStyle = MaterialTheme.typography.displayLarge,
                    )
                }
                Spacer(Modifier.height(MENU_GAP))
                val actions = stickerActions(
                    pick,
                    favorite = pick.sticker.file.uniqueId in favorites,
                    canSend = onSend != null,
                    canOpenSet = onOpenSet != null,
                )
                ActionCard(
                    actions,
                    enabled = !state.dragging,
                    modifier = Modifier.graphicsLayer { alpha = menuAlpha },
                ) { action ->
                    val sticker = pick.sticker
                    state.dismiss()
                    when (action) {
                        StickerAction.Send -> onSend?.invoke(sticker)
                        StickerAction.AddFavorite -> viewModel.setFavorite(sticker, true)
                        StickerAction.RemoveFavorite -> viewModel.setFavorite(sticker, false)
                        StickerAction.OpenSet -> onOpenSet?.invoke(sticker)
                        StickerAction.RemoveRecent -> viewModel.removeRecent(sticker)
                    }
                }
            }
        }
    }
}

/** Карточка действий, как у меню сообщения: «название — значок справа», разделители, красное последним. */
@Composable
private fun ActionCard(actions: List<StickerAction>, enabled: Boolean, modifier: Modifier, onAction: (StickerAction) -> Unit) {
    val colors = ReachTheme.colors
    Column(
        modifier
            .width(MENU_WIDTH)
            .shadow(MENU_ELEVATION, RoundedCornerShape(CARD_CORNER))
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(colors.cell),
    ) {
        actions.forEachIndexed { i, action ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
            val row = action.row()
            val tint = if (row.destructive) colors.destructive else colors.label
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .clickable(enabled = enabled) { onAction(action) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(row.label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(painterResource(row.icon), contentDescription = null, Modifier.size(22.dp), tint = tint)
            }
        }
    }
}

private class MenuRow(@StringRes val label: Int, @DrawableRes val icon: Int, val destructive: Boolean = false)

private fun StickerAction.row(): MenuRow = when (this) {
    StickerAction.Send -> MenuRow(R.string.sticker_action_send, R.drawable.ic_send)
    StickerAction.AddFavorite -> MenuRow(R.string.sticker_action_favorite, R.drawable.ic_star)
    StickerAction.RemoveFavorite -> MenuRow(R.string.sticker_action_unfavorite, R.drawable.ic_star)
    StickerAction.OpenSet -> MenuRow(R.string.sticker_action_open_set, R.drawable.ic_sticker)
    StickerAction.RemoveRecent -> MenuRow(R.string.sticker_action_remove_recent, R.drawable.ic_delete, destructive = true)
}

private const val SCRIM_ALPHA = 0.85f
private const val APPEAR_SCALE = 0.7f
private const val CLOSE_MS = 160
private const val MENU_FADE_MS = 120
private val PREVIEW_MARGIN = 48.dp
private val PREVIEW_MAX = 300.dp
private val MENU_GAP = 20.dp
private val MENU_WIDTH = 250.dp
private val ROW_HEIGHT = 44.dp
private val CARD_CORNER = 14.dp
private val MENU_ELEVATION = 12.dp
