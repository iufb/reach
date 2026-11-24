package io.github.iufb.reach.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Вкладка нижней панели; [badge] — счётчик в красном кружке (0 — нет). [image] рисуется вместо [icon]
 * без перекраски — аватар на вкладке «Настройки».
 */
class TabItem(
    val icon: Painter,
    val label: String,
    val badge: Int = 0,
    val image: (@Composable () -> Unit)? = null,
)

/** Высота плавающего tab bar без навигационной полосы. */
val TabBarHeight = 62.dp

/**
 * Tab bar Telegram iOS 26 (liquid glass): стеклянная капсула во всю ширину. Выбранную вкладку отмечает
 * линза. Пока вкладку держат или линза едет к новой, она становится прозрачным стеклянным пузырём
 * больше вкладки: светлая кромка, содержимое под ней чуть искажено, сама капсула слегка «вздувается».
 * Акцентный цвет идёт за линзой: вкладки рисуются дважды, акцентный слой обрезан по форме линзы.
 * На месте линза снова залитая капсула.
 * Содержимое под панелью прокручивается (`navigationBarsPadding` внутри).
 */
@Composable
fun ReachTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** С какой вкладки линза приезжает при появлении панели (вкладки — разные экраны со своей панелью). */
    previous: Int? = null,
    /** Вкладку выбрали, перетащив линзу пальцем (по умолчанию — как обычный выбор). */
    onDragSelect: (Int) -> Unit = onSelect,
    /** Панель появляется с линзой-пузырём, который гаснет: продолжение перетаскивания с прошлого экрана. */
    enterAsGlass: Boolean = false,
) {
    val colors = ReachTheme.colors
    // Положение и ширина каждой вкладки в ряду: по ним ездит линза
    val bounds = remember(items.size) { mutableStateListOf(*Array(items.size) { 0f to 0f }) }
    val pressed = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    // Края линзы едут на разных пружинах: передний убегает к цели, задний догоняет — линза «перетекает»
    val lensL = remember { Animatable(0f) }
    val lensR = remember { Animatable(0f) }
    // 0 — линза-заливка на месте, 1 — стеклянный пузырь в движении
    val moving = remember { Animatable(if (enterAsGlass) 1f else 0f) }
    // Линза в пути к [travelTarget]: стекло гаснет по мере подъезда, без отдельной фазы «осесть»
    var travelTarget by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var placed by remember { mutableStateOf(false) }
    // Палец, который тащит линзу: x в координатах ряда вкладок; null — не тащат
    var dragX by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentDragSelect by rememberUpdatedState(onDragSelect)

    /**
     * Линза перетекает к вкладке [target]: передний край на жёсткой пружине, задний — на мягкой, линза
     * вытягивается и стягивается на месте. Пузырь поднимается сразу и гаснет вместе с подъездом
     * ([glassness]) — одно непрерывное движение ~0,4 с, как в Telegram iOS.
     */
    suspend fun settle(target: Pair<Float, Float>) = coroutineScope {
        val left = target.first
        val right = target.first + target.second
        if (lensL.value != left || lensR.value != right) {
            val toRight = left + right > lensL.value + lensR.value
            val lead = spring<Float>(dampingRatio = LEAD_DAMPING, stiffness = LEAD_STIFFNESS)
            val trail = spring<Float>(dampingRatio = TRAIL_DAMPING, stiffness = TRAIL_STIFFNESS)
            travelTarget = target
            val edges = listOf(
                launch { lensL.animateTo(left, if (toRight) trail else lead) },
                launch { lensR.animateTo(right, if (toRight) lead else trail) },
            )
            launch { moving.animateTo(1f, tween(LENS_LIFT_MS)) }
            edges.forEach { it.join() }
            travelTarget = null
            moving.snapTo(0f)
        } else {
            moving.animateTo(0f, tween(LENS_SETTLE_MS))
        }
    }

    LaunchedEffect(selected, bounds.toList()) {
        val target = bounds.getOrNull(selected)?.takeIf { it.second > 0f } ?: return@LaunchedEffect
        if (!placed) {
            // Первое появление: линза стоит там, где была на прошлом экране, и едет к выбранной
            val from = previous?.let { bounds.getOrNull(it) }?.takeIf { it.second > 0f } ?: target
            lensL.snapTo(from.first)
            lensR.snapTo(from.first + from.second)
            placed = true
        }
        settle(target)
    }
    val anyPressed = pressed.map { it.collectIsPressedAsState().value }.any { it }
    val held by animateFloatAsState(if (anyPressed) 1f else 0f, spring(dampingRatio = 0.7f), label = "lensHeld")
    // Преломление линзы — RuntimeShader, он есть с Android 13; на Android 12 линза без искажения
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(LENS_SHADER) else null }
    val restFill = colors.fill
    // Пузырь почти прозрачный: та же заливка, только бледнее; кромка — тонкая тень и блик сверху
    val glassFill = restFill.copy(alpha = restFill.alpha * GLASS_FILL_ALPHA)
    val edge = colors.label

    /**
     * Положение и ширина линзы. До первой анимации — прямо из замера вкладок: замер приходит в том же
     * кадре, что и отрисовка, и на новом экране-вкладке линза видна с первого кадра.
     */
    fun lensSpan(): Pair<Float, Float> {
        val finger = dragX
        if (finger != null && bounds.isNotEmpty()) {
            // Под пальцем: линза центрирована на нём и не выходит за край капсулы
            val w = lensR.value - lensL.value
            val rowWidth = bounds.last().let { it.first + it.second }
            return (finger - w / 2).coerceIn(0f, (rowWidth - w).coerceAtLeast(0f)) to w
        }
        return if (placed) lensL.value to lensR.value - lensL.value
        else previous?.let { bounds.getOrNull(it) }?.takeIf { it.second > 0f } ?: bounds.getOrNull(selected) ?: (0f to 0f)
    }

    /**
     * Насколько линза сейчас «стекло»: едет, держат палец или тащат. В пути стекло гаснет по мере того,
     * как оба края подъезжают к цели: заливка проявляется ровно к остановке.
     */
    fun glassness(): Float {
        if (dragX != null) return 1f
        val target = travelTarget ?: return maxOf(moving.value, held)
        val remaining = maxOf(abs(lensL.value - target.first), abs(lensR.value - (target.first + target.second)))
        val approach = (remaining / (target.second * GLASS_FADE_SPAN)).coerceIn(0f, 1f)
        return maxOf(minOf(moving.value, approach), held)
    }

    /** Линза в координатах ряда вкладок высотой [rowHeight]; [g] — насколько она «стекло». */
    fun lensRect(g: Float, rowHeight: Float, growX: Float, growY: Float): Rect {
        val (x, w) = lensSpan()
        return Rect(x - growX * g, -growY * g, x + w + growX * g, rowHeight + growY * g)
    }

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(TabBarHeight)
            .graphicsLayer {
                val s = 1f + CAPSULE_SWELL * glassness()
                scaleX = s
                scaleY = s
            },
    ) {
        Box(Modifier.matchParentSize().glass(RoundedCornerShape(percent = 50)))
        // Линза: залитая капсула на месте, стеклянный пузырь с кромкой в движении
        Box(
            Modifier.matchParentSize().drawBehind {
                if (lensSpan().second <= 0f) return@drawBehind
                val g = glassness()
                val pad = INNER_PAD.toPx()
                val r = lensRect(g, size.height - pad * 2, LENS_GROW_X.toPx(), LENS_GROW_Y.toPx()).translate(Offset(pad, pad))
                val corner = CornerRadius(r.height / 2)
                drawRoundRect(lerp(restFill, glassFill, g), r.topLeft, r.size, corner)
                if (g > 0f) {
                    drawRoundRect(edge.copy(alpha = 0.14f * g), r.topLeft, r.size, corner, style = Stroke(width = 1.dp.toPx()))
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.7f * g), Color.White.copy(alpha = 0.08f * g), Color.White.copy(alpha = 0.35f * g)),
                            startY = r.top,
                            endY = r.bottom,
                        ),
                        r.topLeft,
                        r.size,
                        corner,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            },
        )
        Box(
            Modifier
                .matchParentSize()
                .padding(INNER_PAD)
                // Линзу можно тащить пальцем вдоль капсулы; отпустили — встаёт на ближайшую вкладку
                .pointerInput(items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        dragX = start.position.x
                        horizontalDrag(start.id) { change ->
                            dragX = change.position.x
                            change.consume()
                        }
                        val x = dragX ?: return@awaitEachGesture
                        val index = bounds.indices.minByOrNull { abs(bounds[it].first + bounds[it].second / 2 - x) }
                            ?: return@awaitEachGesture
                        val left = lensSpan().first
                        scope.launch {
                            moving.snapTo(1f)
                            val w = lensR.value - lensL.value
                            lensL.snapTo(left)
                            lensR.snapTo(left + w)
                            dragX = null
                            if (index != currentSelected) currentDragSelect(index) else settle(bounds[index])
                        }
                    }
                }
                .graphicsLayer {
                    val g = glassness()
                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null &&
                        g > 0f && lensSpan().second > 0f
                    ) {
                        val r = lensRect(g, size.height, LENS_GROW_X.toPx(), LENS_GROW_Y.toPx())
                        shader.setFloatUniform("lens", r.left, r.top, r.right, r.bottom)
                        shader.setFloatUniform("strength", g)
                        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                    } else {
                        null
                    }
                },
        ) {
            TabsRow(items, selected, colors.label, Modifier) { index, tab ->
                tab
                    .onPlaced { if (index < bounds.size) bounds[index] = it.positionInParent().x to it.size.width.toFloat() }
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(interactionSource = pressed[index], indication = null, role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = index == selected }
            }
            // Тот же ряд акцентным цветом, видимый только под линзой
            TabsRow(
                items,
                selected,
                colors.accent,
                Modifier
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        if (lensSpan().second <= 0f) return@drawWithContent
                        val r = lensRect(glassness(), size.height, LENS_GROW_X.toPx(), LENS_GROW_Y.toPx())
                        val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(r.height / 2))) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    },
            ) { _, tab -> tab }
        }
    }
}

/** Ряд вкладок одного цвета; [tabModifier] навешивает на вкладку клик и замер. */
@Composable
private fun TabsRow(
    items: List<TabItem>,
    selected: Int,
    tint: Color,
    modifier: Modifier,
    tabModifier: (Int, Modifier) -> Modifier,
) {
    Row(modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEachIndexed { index, item ->
            Column(
                tabModifier(index, Modifier.weight(1f).fillMaxHeight()).padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    val image = item.image
                    if (image != null) {
                        Box(Modifier.size(26.dp).clip(CircleShape)) { image() }
                    } else {
                        Icon(item.icon, contentDescription = null, Modifier.size(26.dp), tint = tint)
                    }
                    if (item.badge > 0) Badge(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp))
                }
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                )
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

/**
 * Искажение под стеклянной линзой: у кромки содержимое тянется к центру, как в толстом стекле.
 * `lens` — прямоугольник линзы (left, top, right, bottom) в пикселях слоя, `strength` — 0…1.
 */
private const val LENS_SHADER = """
uniform shader content;
uniform float4 lens;
uniform float strength;

half4 main(float2 p) {
    float2 c = (lens.xy + lens.zw) * 0.5;
    float2 hs = (lens.zw - lens.xy) * 0.5;
    float r = min(hs.x, hs.y);
    float2 q = abs(p - c) - (hs - r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    if (d >= 0.0 || strength <= 0.0) return content.eval(p);
    float e = smoothstep(-r, 0.0, d);
    float k = 1.0 - strength * 0.22 * e * e;
    return content.eval(c + (p - c) * k);
}
"""

private val INNER_PAD = 4.dp
private val LENS_GROW_X = 8.dp
private val LENS_GROW_Y = 8.dp
// Передний край — быстрый, с лёгким перелётом; задний — мягкий, без перелёта, догоняет
private const val LEAD_DAMPING = 0.8f
private const val LEAD_STIFFNESS = 700f
private const val TRAIL_DAMPING = 1f
private const val TRAIL_STIFFNESS = 260f
private const val LENS_LIFT_MS = 90
private const val LENS_SETTLE_MS = 220
/** Стекло начинает гаснуть, когда до цели остаётся эта доля ширины вкладки. */
private const val GLASS_FADE_SPAN = 0.6f
private const val CAPSULE_SWELL = 0.025f
private const val GLASS_FILL_ALPHA = 0.3f
