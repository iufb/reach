package io.github.iufb.reach.photoedit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Редактор фото на весь экран (ADR-0009). Правит [PhotoEdit], а не пиксели: «Отмена» закрывает
 * без изменений, «Сбросить» возвращает исходник, «Готово» отдаёт правки в [onDone].
 * [load] — исходник в памяти.
 */
@Composable
fun PhotoEditorDialog(
    initial: PhotoEdit,
    load: suspend () -> Bitmap,
    onDone: (PhotoEdit) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val source by produceState<Bitmap?>(null) { value = runCatching { load() }.getOrNull() }
        var edit by remember { mutableStateOf(initial) }
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { edit = PhotoEdit.None }, enabled = !edit.isEmpty) {
                    Text(stringResource(R.string.photo_edit_reset), color = if (edit.isEmpty) Color.Gray else Color.White)
                }
                TextButton(onClick = { onDone(edit) }, enabled = source != null) {
                    Text(stringResource(R.string.photo_edit_done), color = Color.White)
                }
            }
            val bitmap = source
            if (bitmap == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val oriented = remember(bitmap, edit.rotation, edit.flipped) {
                PhotoEditRenderer.orient(bitmap, edit).asImageBitmap()
            }
            CropCanvas(oriented, edit, onCrop = { edit = edit.copy(crop = it) }, modifier = Modifier.weight(1f).fillMaxWidth())
            CropTools(oriented, edit, onChange = { edit = it })
        }
    }
}

/** Пропорции рамки: подпись и значение (`null` — свободная, NaN — как у фото). */
private class AspectOption(val label: String?, val value: Float?)

private val ASPECTS = listOf(
    AspectOption(null, null),
    AspectOption(null, Float.NaN),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3),
    AspectOption("3:4", 3f / 4),
    AspectOption("16:9", 16f / 9),
    AspectOption("9:16", 9f / 16),
)

@Composable
private fun CropTools(image: ImageBitmap, edit: PhotoEdit, onChange: (PhotoEdit) -> Unit) {
    val imageAspect = image.width.toFloat() / image.height
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ASPECTS) { option ->
                val value = option.value?.let { if (it.isNaN()) imageAspect else it }
                val selected = if (value == null) edit.aspect == null else edit.aspect != null && abs(edit.aspect - value) < 0.01f
                val label = option.label ?: stringResource(
                    if (option.value == null) R.string.photo_edit_aspect_free else R.string.photo_edit_aspect_original,
                )
                FilterChip(
                    selected = selected,
                    onClick = {
                        onChange(
                            if (value == null) {
                                edit.copy(aspect = null)
                            } else {
                                edit.copy(aspect = value, crop = NormRect.centered(value, image.width, image.height))
                            },
                        )
                    },
                    label = { Text(label, color = Color.White) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { onChange(edit.rotatedClockwise()) }) {
                Icon(painterResource(R.drawable.ic_rotate), stringResource(R.string.photo_edit_rotate), tint = Color.White)
            }
            IconButton(onClick = { onChange(edit.flippedHorizontally()) }) {
                Icon(painterResource(R.drawable.ic_flip), stringResource(R.string.photo_edit_flip), tint = Color.White)
            }
        }
    }
}

/** Что тянем: угол рамки или всю рамку. */
private enum class CropTarget { None, Move, TopLeft, TopRight, BottomLeft, BottomRight }

/** Фото, вписанное в место, и рамка кропа поверх: углы тянутся, рамка двигается. */
@Composable
private fun CropCanvas(image: ImageBitmap, edit: PhotoEdit, onCrop: (NormRect) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.padding(24.dp)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / image.width, boxH / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val ox = (boxW - dw) / 2
        val oy = (boxH - dh) / 2
        val current by rememberUpdatedState(edit)
        val change by rememberUpdatedState(onCrop)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image, dw, dh) {
                    val handle = HANDLE_RADIUS.toPx()
                    var target = CropTarget.None
                    detectDragGestures(
                        onDragStart = { pos -> target = hitTest(pos, current.crop, ox, oy, dw, dh, handle) },
                        onDragEnd = { target = CropTarget.None },
                    ) { pointer, delta ->
                        if (target == CropTarget.None) return@detectDragGestures
                        pointer.consume()
                        // Отношение высоты к ширине рамки в долях, при котором пиксельные пропорции равны aspect
                        val k = current.aspect?.let { image.width / (image.height * it) }
                        change(current.crop.dragged(target, delta.x / dw, delta.y / dh, k))
                    }
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            drawCropFrame(edit.crop, ox, oy, dw, dh)
        }
    }
}

private fun hitTest(pos: Offset, crop: NormRect, ox: Float, oy: Float, dw: Float, dh: Float, handle: Float): CropTarget {
    val l = ox + crop.left * dw
    val t = oy + crop.top * dh
    val r = ox + crop.right * dw
    val b = oy + crop.bottom * dh
    fun near(x: Float, y: Float) = abs(pos.x - x) <= handle && abs(pos.y - y) <= handle
    return when {
        near(l, t) -> CropTarget.TopLeft
        near(r, t) -> CropTarget.TopRight
        near(l, b) -> CropTarget.BottomLeft
        near(r, b) -> CropTarget.BottomRight
        pos.x in l..r && pos.y in t..b -> CropTarget.Move
        else -> CropTarget.None
    }
}

/**
 * Рамка после перетаскивания [target] на (dx, dy) в долях. [k] — фиксированное отношение
 * высоты к ширине в долях (пропорции); `null` — свободно. Противоположный угол стоит на месте.
 */
private fun NormRect.dragged(target: CropTarget, dx: Float, dy: Float, k: Float?): NormRect {
    if (target == CropTarget.Move) {
        val sx = dx.coerceIn(-left, 1f - right)
        val sy = dy.coerceIn(-top, 1f - bottom)
        return NormRect(left + sx, top + sy, right + sx, bottom + sy)
    }
    val leftSide = target == CropTarget.TopLeft || target == CropTarget.BottomLeft
    val topSide = target == CropTarget.TopLeft || target == CropTarget.TopRight
    // Насколько угол «наружу» сдвинулся по каждой оси
    val growW = if (leftSide) -dx else dx
    val growH = if (topSide) -dy else dy
    val maxW = if (leftSide) right else 1f - left
    val maxH = if (topSide) bottom else 1f - top
    var w: Float
    var h: Float
    if (k == null) {
        w = (width + growW).coerceIn(MIN_SIZE, maxW)
        h = (height + growH).coerceIn(MIN_SIZE, maxH)
    } else {
        // Берём ось, по которой тянули сильнее, вторая следует пропорциям
        val grow = if (abs(growW) >= abs(growH / k)) growW else growH / k
        w = (width + grow).coerceIn(MIN_SIZE, maxW)
        h = w * k
        if (h > maxH) {
            h = maxH
            w = h / k
        }
    }
    val newLeft = if (leftSide) right - w else left
    val newTop = if (topSide) bottom - h else top
    return NormRect(newLeft, newTop, newLeft + w, newTop + h)
}

private fun DrawScope.drawCropFrame(crop: NormRect, ox: Float, oy: Float, dw: Float, dh: Float) {
    val l = ox + crop.left * dw
    val t = oy + crop.top * dh
    val r = ox + crop.right * dw
    val b = oy + crop.bottom * dh
    val shade = Color.Black.copy(alpha = 0.6f)
    // Затемнение вне рамки
    drawRect(shade, Offset(ox, oy), Size(dw, t - oy))
    drawRect(shade, Offset(ox, b), Size(dw, oy + dh - b))
    drawRect(shade, Offset(ox, t), Size(l - ox, b - t))
    drawRect(shade, Offset(r, t), Size(ox + dw - r, b - t))
    val line = 1.dp.toPx()
    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(line))
    // Сетка третей
    val grid = Color.White.copy(alpha = 0.4f)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3
        val y = t + (b - t) * i / 3
        drawLine(grid, Offset(x, t), Offset(x, b), line)
        drawLine(grid, Offset(l, y), Offset(r, y), line)
    }
    // Уголки
    val len = 18.dp.toPx()
    val thick = 3.dp.toPx()
    for ((x, y, sx, sy) in listOf(
        floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f),
        floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f),
    ).map { listOf(it[0], it[1], it[2], it[3]) }) {
        drawLine(Color.White, Offset(x, y), Offset(x + len * sx, y), thick)
        drawLine(Color.White, Offset(x, y), Offset(x, y + len * sy), thick)
    }
}

private const val MIN_SIZE = 0.08f
private val HANDLE_RADIUS = 28.dp
