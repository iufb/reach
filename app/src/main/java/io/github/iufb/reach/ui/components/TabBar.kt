package io.github.iufb.reach.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
) {
    val colors = ReachTheme.colors
    // Положение и ширина каждой вкладки в ряду: по ним ездит линза
    val bounds = remember(items.size) { mutableStateListOf(*Array(items.size) { 0f to 0f }) }
    val pressed = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    val lensX = remember { Animatable(0f) }
    val lensW = remember { Animatable(0f) }
    // 0 — линза-заливка на месте, 1 — стеклянный пузырь в движении
    val moving = remember { Animatable(0f) }
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
        if (lensX.value != target.first || lensW.value != target.second) {
            val spec = spring<Float>(dampingRatio = LENS_DAMPING, stiffness = Spring.StiffnessMediumLow)
            coroutineScope {
                launch { moving.animateTo(1f, tween(LENS_LIFT_MS)) }
                launch { lensX.animateTo(target.first, spec) }
                launch { lensW.animateTo(target.second, spec) }
            }
        }
        moving.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow))
    }
    val anyPressed = pressed.map { it.collectIsPressedAsState().value }.any { it }
    val held by animateFloatAsState(if (anyPressed) 1f else 0f, spring(dampingRatio = 0.7f), label = "lensHeld")
    val shader = remember { RuntimeShader(LENS_SHADER) }
    val restFill = colors.fill
    // Пузырь почти прозрачный: та же заливка, только бледнее; кромка — тонкая тень и блик сверху
    val glassFill = restFill.copy(alpha = restFill.alpha * GLASS_FILL_ALPHA)
    val edge = colors.label

    /** Линза в координатах ряда вкладок высотой [rowHeight]; [g] — насколько она «стекло». */
    fun lensRect(g: Float, rowHeight: Float, growX: Float, growY: Float) =
        Rect(lensX.value - growX * g, -growY * g, lensX.value + lensW.value + growX * g, rowHeight + growY * g)

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(TabBarHeight)
            .graphicsLayer {
                val s = 1f + CAPSULE_SWELL * maxOf(moving.value, held)
                scaleX = s
                scaleY = s
            },
    ) {
        Box(Modifier.matchParentSize().glass(RoundedCornerShape(percent = 50)))
        // Линза: залитая капсула на месте, стеклянный пузырь с кромкой в движении
        Box(
            Modifier.matchParentSize().drawBehind {
                if (lensW.value <= 0f) return@drawBehind
                val g = maxOf(moving.value, held)
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
                .graphicsLayer {
                    val g = maxOf(moving.value, held)
                    renderEffect = if (g > 0f && lensW.value > 0f) {
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
                        if (lensW.value <= 0f) return@drawWithContent
                        val r = lensRect(maxOf(moving.value, held), size.height, LENS_GROW_X.toPx(), LENS_GROW_Y.toPx())
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
private const val LENS_DAMPING = 0.72f
private const val LENS_LIFT_MS = 120
private const val CAPSULE_SWELL = 0.025f
private const val GLASS_FILL_ALPHA = 0.3f
