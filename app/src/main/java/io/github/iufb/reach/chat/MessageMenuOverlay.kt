package io.github.iufb.reach.chat

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.launch

/**
 * Поднятый пузырь меню (Telegram iOS): какое сообщение, где оно лежит в ленте и его «снимок» —
 * слой, в который пузырь рисует себя ([menuAnchor]). Оверлей рисует этот слой поверх размытого фона.
 */
@Stable
internal class MessageMenuOverlayState {
    /** Сообщение, чей пузырь поднят: меню открыто или ещё закрывается. */
    var messageId by mutableStateOf<Long?>(null)
        internal set
    var bounds by mutableStateOf(Rect.Zero)
    var layer by mutableStateOf<GraphicsLayer?>(null)

    /** 0 — меню нет, 1 — открыто: размытие фона, полёт пузыря, появление реакций и меню. */
    val progress = Animatable(0f)
}

internal val LocalMessageMenuOverlay = staticCompositionLocalOf<MessageMenuOverlayState?> { null }

/**
 * Пузырь, у которого открыто меню: запоминает своё место и рисуется в слой оверлея, а на месте
 * в ленте остаётся пусто — сообщение «поднято» над размытым чатом.
 */
@Composable
internal fun Modifier.menuAnchor(message: Message): Modifier {
    val overlay = LocalMessageMenuOverlay.current ?: return this
    if (overlay.messageId != message.id) return this
    val layer = rememberGraphicsLayer()
    return this
        .onGloballyPositioned { overlay.bounds = it.boundsInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            // Слой остаётся в дереве отрисовки на своём месте, но под пустой обрезкой — здесь он не виден.
            // Без этого вложенные слои пузыря (фото, видео со скруглением) теряют содержимое, и в меню пусто
            clipRect(0f, 0f, 0f, 0f) { drawLayer(layer) }
            overlay.layer = layer
        }
}

/**
 * Меню по долгому нажатию, как в Telegram iOS: размытый чат (размывает [ChatContent] по [MessageMenuOverlayState.progress]),
 * поднятый пузырь, над ним пилюля реакций, под ним карточка действий. Не влезает — вся группа сдвигается в экран.
 */
@Composable
internal fun MessageMenuOverlay(state: MessageMenuOverlayState, menu: MessageMenu?, interactions: MessageInteractions) {
    var shown by remember { mutableStateOf<MessageMenu?>(null) }
    LaunchedEffect(menu) {
        if (menu != null) {
            shown = menu
            state.messageId = menu.message.id
            state.progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        } else if (shown != null) {
            state.progress.animateTo(0f, tween(CLOSE_MS))
            shown = null
            state.messageId = null
            state.layer = null
        }
    }
    val current = shown ?: return
    val colors = ReachTheme.colors
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var expanded by remember(current.message.id) { mutableStateOf(false) }
    val statusBars = WindowInsets.statusBars
    val navBars = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val outgoing = current.message.isOutgoing

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        // Подложка: лёгкое затемнение поверх размытого чата; тап мимо меню — закрыть
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(colors.label.copy(alpha = SCRIM_ALPHA * state.progress.value)) }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = interactions.onDismissMenu),
        )
        Layout(
            content = {
                if (!current.reactions.isEmpty) {
                    Box(Modifier.layoutId(ID_REACTIONS)) {
                        ReactionBar(current, expanded, onExpand = { expanded = true }) { key ->
                            interactions.onReact(current.message, key)
                        }
                    }
                }
                Box(
                    Modifier
                        .layoutId(ID_BUBBLE)
                        .drawBehind { state.layer?.let { drawLayer(it) } }
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                )
                // Раскрытые реакции закрывают меню, но его высота остаётся в расчёте — пузырь не прыгает
                Box(Modifier.layoutId(ID_MENU)) { if (!expanded) ActionCard(current, interactions) else ActionCardSpace(current) }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val bounds = state.bounds.translate(-origin)
            val reactions = measurables.firstOrNull { it.layoutId == ID_REACTIONS }?.measure(loose)
            val bubble = measurables.first { it.layoutId == ID_BUBBLE }
                .measure(Constraints.fixed(bounds.width.toInt().coerceAtLeast(0), bounds.height.toInt().coerceAtLeast(0)))
            val menuCard = measurables.firstOrNull { it.layoutId == ID_MENU }?.measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val gap = GAP.roundToPx()
                val edge = EDGE.roundToPx()
                val safeTop = statusBars.getTop(this@Layout) + edge
                val safeBottom = constraints.maxHeight - maxOf(navBars.getBottom(this@Layout), ime.getBottom(this@Layout)) - edge
                val reactionsH = reactions?.let { it.height + gap } ?: 0
                val menuH = menuCard?.let { it.height + gap } ?: 0
                // Группа «реакции, пузырь, меню» — на месте пузыря, но целиком в безопасной зоне
                var bubbleY = bounds.top.toInt()
                if (bubbleY - reactionsH < safeTop) bubbleY = safeTop + reactionsH
                if (bubbleY + bubble.height + menuH > safeBottom) bubbleY = safeBottom - menuH - bubble.height
                // Пузырь выше экрана: реакции у верхнего края, меню у нижнего, пузырь между ними (и под меню)
                bubbleY = bubbleY.coerceAtLeast(safeTop + reactionsH)
                val p = state.progress.value
                val flyingY = lerp(bounds.top, bubbleY.toFloat(), p).toInt()
                fun alignX(width: Int): Int {
                    val x = if (outgoing) bounds.right.toInt() - width else bounds.left.toInt()
                    return x.coerceIn(edge, (constraints.maxWidth - width - edge).coerceAtLeast(edge))
                }
                bubble.place(bounds.left.toInt(), flyingY)
                val pivotY = if (reactions != null) 1f else 0f
                reactions?.placeWithLayer(alignX(reactions.width), bubbleY - reactions.height - gap) {
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = lerp(APPEAR_SCALE, 1f, p)
                    scaleY = lerp(APPEAR_SCALE, 1f, p)
                    transformOrigin = TransformOrigin(if (outgoing) 1f else 0f, pivotY)
                }
                menuCard?.let { card ->
                    val y = (bubbleY + bubble.height + gap).coerceAtMost(safeBottom - card.height)
                    card.placeWithLayer(alignX(card.width), y) {
                        alpha = p.coerceIn(0f, 1f)
                        scaleX = lerp(APPEAR_SCALE, 1f, p)
                        scaleY = lerp(APPEAR_SCALE, 1f, p)
                        transformOrigin = TransformOrigin(if (outgoing) 1f else 0f, 0f)
                    }
                }
            }
        }
    }
}

/** Пилюля реакций: сколько влезает в строку и «ещё»; раскрытая — все реакции карточкой с прокруткой. */
@Composable
private fun ReactionBar(menu: MessageMenu, expanded: Boolean, onExpand: () -> Unit, onPick: (ReactionKey) -> Unit) {
    val colors = ReachTheme.colors
    val chosen = menu.message.reactions.filter { it.isChosen }.mapTo(HashSet()) { it.key }
    val available = menu.reactions
    if (expanded) {
        FlowRow(
            Modifier
                .width(EXPANDED_WIDTH)
                .heightIn(max = EXPANDED_MAX_HEIGHT)
                .shadow(MENU_ELEVATION, RoundedCornerShape(CARD_CORNER))
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(colors.cell)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            available.all.forEach { key -> ReactionButton(key, key in chosen, onPick) }
        }
        return
    }
    // Одна строка, без переноса (#196): лишние — под «ещё»
    val more = available.all.size > available.top.size
    val shown = available.top.take(if (more) MAX_IN_ROW - 1 else MAX_IN_ROW)
    Row(
        Modifier
            .shadow(MENU_ELEVATION, CircleShape)
            .clip(CircleShape)
            .background(colors.cell)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { key -> ReactionButton(key, key in chosen, onPick) }
        if (more || available.all.size > shown.size) {
            val description = stringResource(R.string.reactions_more)
            Box(
                Modifier
                    .size(REACTION_SIZE)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(colors.fill)
                    .clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, Modifier.size(18.dp), tint = colors.secondaryLabel)
            }
        }
    }
}

@Composable
private fun ReactionButton(key: ReactionKey, chosen: Boolean, onPick: (ReactionKey) -> Unit) {
    Box(
        Modifier
            .size(REACTION_SIZE)
            .clip(CircleShape)
            .background(if (chosen) ReachTheme.colors.fill else Color.Transparent)
            .clickable { onPick(key) },
        contentAlignment = Alignment.Center,
    ) {
        PickerIcon(key)
        PrefetchReactionAnimations(key)
    }
}

/** Пустое место размером с карточку действий (пока раскрыты реакции). */
@Composable
private fun ActionCardSpace(menu: MessageMenu) {
    Box(Modifier.width(MENU_WIDTH).height(ROW_HEIGHT * menuRowCount(menu.actions)))
}

private fun menuRowCount(actions: MessageActions): Int = listOf(
    actions.canReply, actions.canEdit, actions.copyText != null, actions.canPin, actions.canForward,
    actions.canRetractVote, actions.canStopPoll, true, actions.canDelete,
).count { it }

/** Карточка действий: строки «название — значок справа», разделители, «Удалить» красным последним. */
@Composable
private fun ActionCard(menu: MessageMenu, interactions: MessageInteractions) {
    val colors = ReachTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = menu.actions
    val message = menu.message
    val rows = buildList {
        if (actions.canReply) add(MenuRow(R.string.message_action_reply, R.drawable.ic_reply) { interactions.onReply?.invoke(message) })
        if (actions.canEdit) add(MenuRow(R.string.message_action_edit, R.drawable.ic_edit) { interactions.onEdit(message) })
        actions.copyText?.let { text ->
            add(
                MenuRow(R.string.message_action_copy, R.drawable.ic_copy) {
                    // Android 13+ сам показывает подтверждение копирования
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                    interactions.onDismissMenu()
                },
            )
        }
        if (actions.canPin) {
            val pinned = message.isPinned
            add(MenuRow(if (pinned) R.string.message_action_unpin else R.string.message_action_pin, R.drawable.ic_pin) { interactions.onPin(message, !pinned) })
        }
        if (actions.canForward) add(MenuRow(R.string.message_action_forward, R.drawable.ic_forward) { interactions.onForward(message) })
        if (actions.canRetractVote) add(MenuRow(R.string.poll_retract, R.drawable.ic_undo) { interactions.onRetractVote(message) })
        if (actions.canStopPoll) {
            val quiz = (message.content as? MessageBody.Poll)?.poll?.quiz != null
            add(MenuRow(if (quiz) R.string.quiz_stop else R.string.poll_stop, R.drawable.ic_stop) { interactions.onStopPoll(message) })
        }
        // Выбрать можно любое сообщение, у которого открылось меню
        add(MenuRow(R.string.message_action_select, R.drawable.ic_select) { interactions.onStartSelection(menu) })
        if (actions.canDelete) add(MenuRow(R.string.message_action_delete, R.drawable.ic_delete, destructive = true) { interactions.onDelete(menu) })
    }
    Column(
        Modifier
            .width(MENU_WIDTH)
            .shadow(MENU_ELEVATION, RoundedCornerShape(CARD_CORNER))
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(colors.cell),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
            val tint = if (row.destructive) colors.destructive else colors.label
            Row(
                Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable(onClick = row.onClick).padding(horizontal = 16.dp),
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

private class MenuRow(val label: Int, @DrawableRes val icon: Int, val destructive: Boolean = false, val onClick: () -> Unit)

private const val ID_REACTIONS = "reactions"
private const val ID_BUBBLE = "bubble"
private const val ID_MENU = "menu"
private const val CLOSE_MS = 180
private const val SCRIM_ALPHA = 0.08f
private const val APPEAR_SCALE = 0.6f
private const val MAX_IN_ROW = 7
private val GAP = 8.dp
private val EDGE = 8.dp
private val MENU_WIDTH = 250.dp
private val ROW_HEIGHT = 44.dp
private val CARD_CORNER = 14.dp
private val MENU_ELEVATION = 12.dp
private val REACTION_SIZE = 40.dp
private val EXPANDED_WIDTH = 304.dp
private val EXPANDED_MAX_HEIGHT = 260.dp
