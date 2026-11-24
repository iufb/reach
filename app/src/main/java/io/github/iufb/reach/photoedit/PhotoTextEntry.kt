package io.github.iufb.reach.photoedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import kotlin.math.sqrt

/** Надпись, которую сейчас набирают: [index] — какую правим, `null` — новая. */
internal class TextEntry(val index: Int?, text: String, color: Int, size: Float) {
    var text by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    var color by mutableIntStateOf(color)
    var size by mutableFloatStateOf(size)
}

/**
 * Ввод надписи как в Telegram iOS: прямо поверх затемнённого фото, тем же шрифтом, цветом и размером,
 * что получится на снимке. Сверху «Отмена» и «Готово», слева ползунок размера, над клавиатурой цвета.
 * [minSide] — меньшая сторона фото на экране в px: от неё считается размер, как в [OverlayPainter].
 */
@Composable
internal fun TextEntryOverlay(entry: TextEntry, minSide: Float, onCancel: () -> Unit, onDone: () -> Unit) {
    BackHandler(onBack = onCancel)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val fontSize = with(LocalDensity.current) { (entry.size * minSide).toSp() }
    val family = remember { FontFamily(OverlayPainter.typeface) }
    Box(
        Modifier
            .fillMaxSize()
            .background(TEXT_ENTRY_SCRIM)
            // Тапы не уходят в редактор под затемнением
            .pointerInput(Unit) { detectTapGestures {} }
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EntryButton(stringResource(R.string.cancel), bold = false, onClick = onCancel)
                Spacer(Modifier.weight(1f))
                EntryButton(stringResource(R.string.photo_edit_done), bold = true, onClick = onDone)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicTextField(
                    value = entry.text,
                    onValueChange = { entry.text = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp).focusRequester(focus),
                    textStyle = TextStyle(
                        color = Color(entry.color),
                        fontSize = fontSize,
                        fontFamily = family,
                        textAlign = TextAlign.Center,
                        // Та же тень, что рисует OverlayPainter: белый текст читается на светлом фото
                        shadow = Shadow(Color(TEXT_SHADOW), Offset.Zero, entry.size * minSide * 0.08f),
                    ),
                    cursorBrush = SolidColor(Color.White),
                )
                SizeSlider(
                    value = entry.size,
                    onChange = { entry.size = it },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
            EntryColorRow(entry.color) { entry.color = it }
        }
    }
}

@Composable
private fun EntryButton(text: String, bold: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/**
 * Вертикальный ползунок размера, как в Telegram iOS: клин, широкий сверху (крупно) и узкий снизу,
 * и белый кружок. Шкала квадратичная: мелкие размеры не сжаты в самом низу.
 */
@Composable
private fun SizeSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val fraction = sqrt(((value - MIN_TEXT_SIZE) / (MAX_TEXT_SIZE - MIN_TEXT_SIZE)).coerceIn(0f, 1f))
    Canvas(
        modifier
            .width(32.dp)
            .height(240.dp)
            .pointerInput(Unit) {
                fun set(y: Float) {
                    val t = (1f - y / size.height).coerceIn(0f, 1f)
                    change(MIN_TEXT_SIZE + t * t * (MAX_TEXT_SIZE - MIN_TEXT_SIZE))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    set(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        set(change.position.y)
                    }
                }
            },
    ) {
        val thumb = 11.dp.toPx()
        val top = thumb
        val bottom = size.height - thumb
        val cx = size.width / 2
        val wedge = Path().apply {
            moveTo(cx - 5.dp.toPx(), top)
            lineTo(cx + 5.dp.toPx(), top)
            lineTo(cx + 1.dp.toPx(), bottom)
            lineTo(cx - 1.dp.toPx(), bottom)
            close()
        }
        drawPath(wedge, Color.White.copy(alpha = 0.35f))
        drawCircle(Color.White, thumb, Offset(cx, bottom - fraction * (bottom - top)))
    }
}

@Composable
private fun EntryColorRow(selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(PALETTE) { color ->
            val argb = color.toArgb()
            Box(
                Modifier
                    .size(30.dp)
                    .border(2.dp, if (argb == selected) Color.White else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .background(color, CircleShape)
                    .then(if (argb == selected) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                    .clickable(role = Role.Button) { onSelect(argb) },
            )
        }
    }
}

private val TEXT_ENTRY_SCRIM = Color.Black.copy(alpha = 0.6f)
private const val TEXT_SHADOW = 0x66000000
