package io.github.iufb.reach.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Вкладка нижней панели; [badge] — счётчик в красном кружке (0 — нет). */
class TabItem(val icon: Painter, val label: String, val badge: Int = 0)

/** Круглая кнопка справа от капсулы вкладок (поиск). */
class TabBarAction(val icon: Painter, val description: String, val onClick: () -> Unit)

/** Высота плавающего tab bar без навигационной полосы. */
val TabBarHeight = 62.dp

/**
 * Tab bar Telegram iOS 26 (liquid glass): компактная стеклянная капсула по размеру вкладок, прижатая
 * влево, и справа — отдельная круглая кнопка [action] того же стекла. Выбранную вкладку отмечает
 * «линза» — капсула `fill` за значком и подписью; она переезжает к новой вкладке на пружине и чуть
 * увеличивается, пока вкладку держат нажатой. [fill] — капсула во всю ширину (шторка вложений).
 * Содержимое под панелью прокручивается (`navigationBarsPadding` внутри).
 */
@Composable
fun ReachTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    action: TabBarAction? = null,
    fill: Boolean = false,
    /** С какой вкладки линза приезжает при появлении панели (вкладки — разные экраны со своей панелью). */
    previous: Int? = null,
) {
    val colors = ReachTheme.colors
    val density = LocalDensity.current
    // Положение и ширина каждой вкладки внутри капсулы: по ним ездит линза
    val bounds = remember(items.size) { mutableStateListOf(*Array(items.size) { 0f to 0f }) }
    val pressed = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    val lensX = remember { Animatable(0f) }
    val lensW = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selected, bounds.toList()) {
        val target = bounds.getOrNull(selected)?.takeIf { it.second > 0f } ?: return@LaunchedEffect
        if (!placed) {
            // Первое появление: линза стоит там, где была на прошлом экране, и едет к выбранной
            val from = previous?.let { bounds.getOrNull(it) }?.takeIf { it.second > 0f } ?: target
            lensX.snapTo(from.first)
            lensW.snapTo(from.second)
            placed = true
        }
        val spec = spring<Float>(dampingRatio = LENS_DAMPING, stiffness = Spring.StiffnessMediumLow)
        launch { lensX.animateTo(target.first, spec) }
        launch { lensW.animateTo(target.second, spec) }
    }
    val lensWidth = lensW.value
    val anyPressed = pressed.map { it.collectIsPressedAsState().value }.any { it }
    val lensScale by animateFloatAsState(if (anyPressed) LENS_PRESSED_SCALE else 1f, spring(dampingRatio = 0.6f), label = "lensScale")

    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .then(if (fill) Modifier.weight(1f) else Modifier)
                .height(TabBarHeight)
                .glass(RoundedCornerShape(percent = 50))
                .padding(4.dp),
        ) {
            if (lensWidth > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset(lensX.value.roundToInt(), 0) }
                        .size(with(density) { lensWidth.toDp() }, TabBarHeight - 8.dp)
                        .graphicsLayer {
                            scaleX = lensScale
                            scaleY = lensScale
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.fill),
                )
            }
            Row(
                Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).fillMaxHeight(),
                horizontalArrangement = if (fill) Arrangement.SpaceEvenly else Arrangement.Start,
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    val tint = if (isSelected) colors.accent else colors.label
                    Column(
                        Modifier
                            .then(if (fill) Modifier.weight(1f) else Modifier.widthIn(min = TAB_MIN_WIDTH))
                            .fillMaxHeight()
                            .onPlaced { if (index < bounds.size) bounds[index] = it.positionInParent().x to it.size.width.toFloat() }
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(interactionSource = pressed[index], indication = null, role = Role.Tab) { onSelect(index) }
                            .semantics { this.selected = isSelected }
                            .padding(horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(item.icon, contentDescription = null, Modifier.size(26.dp), tint = tint)
                            if (item.badge > 0) Badge(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp))
                        }
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (action != null) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(TabBarHeight)
                    .glass(CircleShape)
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .semantics { contentDescription = action.description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, contentDescription = null, Modifier.size(26.dp), tint = colors.label)
            }
        }
    }
}

/** Красный счётчик на значке вкладки. */
@Composable
private fun Badge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .widthIn(min = 18.dp)
            .height(18.dp)
            .clip(CircleShape)
            .background(ReachTheme.colors.destructive)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val TAB_MIN_WIDTH = 64.dp
private const val LENS_DAMPING = 0.78f
private const val LENS_PRESSED_SCALE = 1.08f
