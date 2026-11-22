package io.github.iufb.reach.photoedit

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Инструмент редактора. */
private enum class Tool { Crop, Draw, Text, Filter }

/**
 * Состояние редактора: текущие правки и история для отмены/повтора. [commit] — законченное
 * действие (новая точка в истории), [preview] — промежуточное во время жеста; жест начинается
 * с [beginGesture], чтобы вся протяжка отменялась одним шагом.
 */
private class EditorState(initial: PhotoEdit) {
    var edit by mutableStateOf(initial)
        private set
    private val undo = mutableStateListOf<PhotoEdit>()
    private val redo = mutableStateListOf<PhotoEdit>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun commit(next: PhotoEdit) {
        if (next == edit) return
        undo += edit
        redo.clear()
        edit = next
    }

    fun beginGesture() {
        undo += edit
        redo.clear()
    }

    fun preview(next: PhotoEdit) {
        edit = next
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo += edit
        edit = previous
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo += edit
        edit = next
    }
}

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
        val state = remember { EditorState(initial) }
        val edit = state.edit
        var tool by remember { mutableStateOf(Tool.Crop) }
        val brush = remember { BrushSettings() }
        var selectedText by remember { mutableStateOf<Int?>(null) }
        var textDialog by remember { mutableStateOf<TextDialog?>(null) }
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            // Сверху — история правок; «Отмена» и «Готово» внизу, по краям панели инструментов, как в iOS
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorIcon(R.drawable.ic_undo, stringResource(R.string.photo_edit_undo), enabled = state.canUndo, onClick = state::undo)
                EditorIcon(R.drawable.ic_redo, stringResource(R.string.photo_edit_redo), enabled = state.canRedo, onClick = state::redo)
                Spacer(Modifier.weight(1f))
                EditorPill(stringResource(R.string.photo_edit_reset), selected = false, enabled = !edit.isEmpty) { state.commit(PhotoEdit.None) }
            }
            val bitmap = source
            if (bitmap == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EDITOR_CONTENT) }
                EditorBottomBar(tool, onSelect = {}, onCancel = onCancel, onDone = {}, doneEnabled = false)
                return@Column
            }
            val orientedBitmap = remember(bitmap, edit.rotation, edit.flipped) { PhotoEditRenderer.orient(bitmap, edit) }
            val oriented = remember(orientedBitmap) { orientedBitmap.asImageBitmap() }
            EditorCanvas(
                oriented, state, tool, brush,
                selectedText = selectedText,
                onSelectText = { selectedText = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            when (tool) {
                Tool.Crop -> CropTools(oriented, edit, onChange = state::commit)
                Tool.Draw -> DrawTools(brush)
                Tool.Filter -> FilterTools(orientedBitmap, state)
                Tool.Text -> TextTools(
                    selected = selectedText?.let { edit.texts.getOrNull(it) },
                    onAdd = { textDialog = TextDialog(null, "") },
                    onEdit = { selectedText?.let { i -> textDialog = TextDialog(i, edit.texts[i].text) } },
                    onDelete = {
                        selectedText?.let { i -> state.commit(edit.copy(texts = edit.texts.filterIndexed { j, _ -> j != i })) }
                        selectedText = null
                    },
                    onColor = { color ->
                        brush.textColor = color
                        selectedText?.let { i ->
                            state.commit(edit.copy(texts = edit.texts.mapIndexed { j, t -> if (j == i) t.copy(color = color) else t }))
                        }
                    },
                    color = brush.textColor,
                )
            }
            EditorBottomBar(
                tool = tool,
                onSelect = {
                    tool = it
                    selectedText = null
                },
                onCancel = onCancel,
                onDone = { onDone(edit) },
                doneEnabled = true,
            )
        }
        textDialog?.let { dialog ->
            TextInputDialog(
                initial = dialog.text,
                onDone = { text ->
                    textDialog = null
                    if (text.isBlank()) return@TextInputDialog
                    val index = dialog.index
                    if (index == null) {
                        // Новая надпись — по центру видимой рамки
                        val crop = edit.crop
                        val item = TextItem(
                            text, NormPoint((crop.left + crop.right) / 2, (crop.top + crop.bottom) / 2),
                            DEFAULT_TEXT_SIZE, 0f, brush.textColor,
                        )
                        state.commit(edit.copy(texts = edit.texts + item))
                        selectedText = edit.texts.size
                    } else {
                        state.commit(edit.copy(texts = edit.texts.mapIndexed { j, t -> if (j == index) t.copy(text = text) else t }))
                    }
                },
                onDismiss = { textDialog = null },
            )
        }
    }
}

private class TextDialog(val index: Int?, val text: String)

/** Кисть и цвет новых надписей; живут, пока открыт редактор. */
private class BrushSettings {
    var color by mutableIntStateOf(PALETTE.first().toArgb())
    var width by mutableFloatStateOf(DEFAULT_BRUSH_WIDTH)
    var eraser by mutableStateOf(false)
    var textColor by mutableIntStateOf(PALETTE.first().toArgb())
}

/**
 * Низ редактора как в Telegram iOS: слева «Отмена» (крестик), по центру инструменты значками
 * (выбранный — акцентом), справа «Готово» — акцентный круг с галочкой.
 */
@Composable
private fun EditorBottomBar(tool: Tool, onSelect: (Tool) -> Unit, onCancel: () -> Unit, onDone: () -> Unit, doneEnabled: Boolean) {
    val accent = ReachTheme.colors.accent
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorIcon(R.drawable.ic_close, stringResource(R.string.cancel), onClick = onCancel)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in Tool.entries) {
                val (icon, label) = when (t) {
                    Tool.Crop -> R.drawable.ic_crop to R.string.photo_edit_tab_crop
                    Tool.Draw -> R.drawable.ic_brush to R.string.photo_edit_tab_draw
                    Tool.Text -> R.drawable.ic_text to R.string.photo_edit_tab_text
                    Tool.Filter -> R.drawable.ic_tune to R.string.photo_edit_tab_filters
                }
                EditorIcon(icon, stringResource(label), tint = if (t == tool) accent else EDITOR_CONTENT, selected = t == tool) { onSelect(t) }
            }
        }
        val doneDescription = stringResource(R.string.photo_edit_done)
        Box(
            Modifier
                .size(PillHeight)
                .clip(CircleShape)
                .background(if (doneEnabled) accent else EDITOR_DISABLED)
                .clickable(enabled = doneEnabled, role = Role.Button, onClick = onDone)
                .semantics { contentDescription = doneDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(24.dp), tint = ReachTheme.colors.onAccent)
        }
    }
}

/** Значок-кнопка редактора: белый, недоступный — приглушён; [selected] — для переключателей инструментов. */
@Composable
private fun EditorIcon(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean = true,
    tint: Color = EDITOR_CONTENT,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(PillHeight)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = if (selected != null) Role.Tab else Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = if (enabled) tint else EDITOR_DISABLED)
    }
}

/** Пилюля-вариант (пропорции, кисть / ластик, действия с надписью): выбранная — на светлой подложке. */
@Composable
private fun EditorPill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) EDITOR_PILL_SELECTED else EDITOR_PILL)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = when {
            !enabled -> EDITOR_DISABLED
            selected -> EDITOR_BACKGROUND
            else -> EDITOR_CONTENT
        },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
    )
}

/** Ряд пилюль с прокруткой и отступами по краям: крайняя не упирается в край экрана. */
@Composable
private fun PillRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val EDITOR_BACKGROUND = Color.Black
private val EDITOR_CONTENT = Color.White
private val EDITOR_DISABLED = Color.White.copy(alpha = 0.35f)
private val EDITOR_PILL = Color.White.copy(alpha = 0.14f)
private val EDITOR_PILL_SELECTED = Color.White

/**
 * Фото, вписанное в место, и слои поверх: рисунок, надписи, затемнение вне рамки.
 * Жесты зависят от инструмента: рамка, кисть или надписи.
 */
@Composable
private fun EditorCanvas(
    image: ImageBitmap,
    state: EditorState,
    tool: Tool,
    brush: BrushSettings,
    selectedText: Int?,
    onSelectText: (Int?) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.padding(24.dp)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / image.width, boxH / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val ox = (boxW - dw) / 2
        val oy = (boxH - dh) / 2
        val area = RectF(ox, oy, ox + dw, oy + dh)
        val edit = state.edit
        var drawing by remember { mutableStateOf<BrushStroke?>(null) }
        val currentEdit by rememberUpdatedState(edit)
        val currentBrush by rememberUpdatedState(brush)
        val currentSelected by rememberUpdatedState(selectedText)
        val select by rememberUpdatedState(onSelectText)

        val colorFilter = remember(edit.filter, edit.adjust) { composeColorFilter(edit.filter, edit.adjust) }
        val gestures = when (tool) {
            Tool.Filter -> Modifier
            Tool.Crop -> Modifier.pointerInput(image, dw, dh) {
                val handle = HANDLE_RADIUS.toPx()
                var target = CropTarget.None
                detectDragGestures(
                    onDragStart = { pos ->
                        target = hitTest(pos, currentEdit.crop, ox, oy, dw, dh, handle)
                        if (target != CropTarget.None) state.beginGesture()
                    },
                    onDragEnd = { target = CropTarget.None },
                ) { pointer, delta ->
                    if (target == CropTarget.None) return@detectDragGestures
                    pointer.consume()
                    // Отношение высоты к ширине рамки в долях, при котором пиксельные пропорции равны aspect
                    val k = currentEdit.aspect?.let { image.width / (image.height * it) }
                    state.preview(currentEdit.copy(crop = currentEdit.crop.dragged(target, delta.x / dw, delta.y / dh, k)))
                }
            }
            Tool.Draw -> Modifier.pointerInput(image, dw, dh) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun norm(p: Offset) = NormPoint(((p.x - ox) / dw).coerceIn(0f, 1f), ((p.y - oy) / dh).coerceIn(0f, 1f))
                    val b = currentBrush
                    val points = mutableListOf(norm(down.position))
                    val color = b.color
                    drawing = BrushStroke(points.toList(), color, b.width, b.eraser)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            points += norm(change.position)
                            drawing = BrushStroke(points.toList(), color, b.width, b.eraser)
                        }
                        change.consume()
                    }
                    drawing?.let { stroke -> state.commit(currentEdit.copy(strokes = currentEdit.strokes + stroke)) }
                    drawing = null
                }
            }
            // Тап — выбрать надпись; один или два пальца — двигать, масштабировать и вращать выбранную
            Tool.Text -> Modifier.pointerInput(image, dw, dh) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = 0f
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        moved += pan.getDistance()
                        if (moved < slop && zoom == 1f && rotation == 0f) continue
                        val index = currentSelected ?: continue
                        val item = currentEdit.texts.getOrNull(index) ?: continue
                        if (!started) {
                            state.beginGesture()
                            started = true
                        }
                        val next = item.copy(
                            center = NormPoint(
                                (item.center.x + pan.x / dw).coerceIn(0f, 1f),
                                (item.center.y + pan.y / dh).coerceIn(0f, 1f),
                            ),
                            size = (item.size * zoom).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
                            rotation = item.rotation + rotation,
                        )
                        state.preview(currentEdit.copy(texts = currentEdit.texts.mapIndexed { j, t -> if (j == index) next else t }))
                        event.changes.forEach { it.consume() }
                    }
                    if (moved < slop && !started) select(hitText(down.position, currentEdit.texts, ox, oy, dw, dh))
                }
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            drawImage(
                image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                colorFilter = colorFilter,
            )
            drawIntoCanvas { OverlayPainter.draw(it.nativeCanvas, edit, area, extraStroke = drawing) }
            if (tool == Tool.Text) {
                selectedText?.let { i -> edit.texts.getOrNull(i)?.let { drawTextSelection(it, ox, oy, dw, dh) } }
            }
            drawCropShade(edit.crop, ox, oy, dw, dh, frame = tool == Tool.Crop)
        }
    }
}

/** Надпись под пальцем (сверху — последние); `null` — мимо. */
private fun hitText(pos: Offset, texts: List<TextItem>, ox: Float, oy: Float, dw: Float, dh: Float): Int? {
    val minSide = min(dw, dh)
    for (i in texts.indices.reversed()) {
        val item = texts[i]
        val (hw, hh) = OverlayPainter.textHalfSize(item, minSide)
        val cx = ox + item.center.x * dw
        val cy = oy + item.center.y * dh
        // Точку поворачиваем в систему надписи
        val angle = Math.toRadians(-item.rotation.toDouble())
        val dx = pos.x - cx
        val dy = pos.y - cy
        val lx = dx * cos(angle) - dy * sin(angle)
        val ly = dx * sin(angle) + dy * cos(angle)
        if (abs(lx) <= hw + TEXT_HIT_SLOP && abs(ly) <= hh + TEXT_HIT_SLOP) return i
    }
    return null
}

private fun DrawScope.drawTextSelection(item: TextItem, ox: Float, oy: Float, dw: Float, dh: Float) {
    val (hw, hh) = OverlayPainter.textHalfSize(item, min(dw, dh))
    val center = Offset(ox + item.center.x * dw, oy + item.center.y * dh)
    val pad = 8.dp.toPx()
    rotate(item.rotation, center) {
        drawRect(
            Color.White,
            topLeft = Offset(center.x - hw - pad, center.y - hh - pad),
            size = Size((hw + pad) * 2, (hh + pad) * 2),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
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
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PillRow {
            for (option in ASPECTS) {
                val value = option.value?.let { if (it.isNaN()) imageAspect else it }
                val selected = if (value == null) edit.aspect == null else edit.aspect != null && abs(edit.aspect - value) < 0.01f
                val label = option.label ?: stringResource(
                    if (option.value == null) R.string.photo_edit_aspect_free else R.string.photo_edit_aspect_original,
                )
                EditorPill(label, selected) {
                    onChange(
                        if (value == null) {
                            edit.copy(aspect = null)
                        } else {
                            edit.copy(aspect = value, crop = NormRect.centered(value, image.width, image.height))
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            EditorIcon(R.drawable.ic_rotate, stringResource(R.string.photo_edit_rotate)) { onChange(edit.rotatedClockwise()) }
            EditorIcon(R.drawable.ic_flip, stringResource(R.string.photo_edit_flip)) { onChange(edit.flippedHorizontally()) }
        }
    }
}

@Composable
private fun DrawTools(brush: BrushSettings) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorPill(stringResource(R.string.photo_edit_brush), selected = !brush.eraser) { brush.eraser = false }
            EditorPill(stringResource(R.string.photo_edit_eraser), selected = brush.eraser) { brush.eraser = true }
            Slider(
                value = brush.width,
                onValueChange = { brush.width = it },
                valueRange = MIN_BRUSH_WIDTH..MAX_BRUSH_WIDTH,
                modifier = Modifier.weight(1f),
                colors = editorSliderColors(),
            )
        }
        ColorRow(brush.color) {
            brush.color = it
            brush.eraser = false
        }
    }
}

@Composable
private fun TextTools(
    selected: TextItem?,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onColor: (Int) -> Unit,
    color: Int,
) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorPill(stringResource(R.string.photo_edit_text_add), selected = false, onClick = onAdd)
            if (selected != null) {
                EditorPill(stringResource(R.string.photo_edit_text_edit), selected = false, onClick = onEdit)
                EditorPill(stringResource(R.string.photo_edit_text_delete), selected = false, onClick = onDelete)
            }
        }
        ColorRow(selected?.color ?: color, onColor)
    }
}

@Composable
private fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(PALETTE) { color ->
            val argb = color.toArgb()
            Box(
                Modifier
                    .size(28.dp)
                    .background(color, CircleShape)
                    .border(if (argb == selected) 3.dp else 1.dp, Color.White, CircleShape)
                    .clickable { onSelect(argb) },
            )
        }
    }
}

private fun composeColorFilter(filter: FilterPreset, adjust: Adjustments): ColorFilter? =
    colorMatrixFor(filter, adjust)?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) }

/** Фильтры с превью на миниатюре и ползунки коррекции; ползунок — один шаг истории за протяжку. */
@Composable
private fun FilterTools(image: Bitmap, state: EditorState) {
    val thumb = remember(image) {
        val scale = THUMB_SIDE.toFloat() / maxOf(image.width, image.height)
        Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1), true)
            .asImageBitmap()
    }
    val edit = state.edit
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FilterPreset.entries) { preset ->
                val filter = remember(preset) { composeColorFilter(preset, Adjustments()) }
                Column(
                    Modifier.clickable { state.commit(edit.copy(filter = preset)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        thumb,
                        contentDescription = null,
                        colorFilter = filter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .border(if (preset == edit.filter) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Text(stringResource(preset.label()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        var adjusting by remember { mutableStateOf(false) }
        for ((label, value, update) in listOf(
            Triple(R.string.photo_edit_brightness, edit.adjust.brightness) { a: Adjustments, v: Float -> a.copy(brightness = v) },
            Triple(R.string.photo_edit_contrast, edit.adjust.contrast) { a: Adjustments, v: Float -> a.copy(contrast = v) },
            Triple(R.string.photo_edit_saturation, edit.adjust.saturation) { a: Adjustments, v: Float -> a.copy(saturation = v) },
            Triple(R.string.photo_edit_warmth, edit.adjust.warmth) { a: Adjustments, v: Float -> a.copy(warmth = v) },
        )) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                Slider(
                    value = value,
                    onValueChange = { v ->
                        if (!adjusting) {
                            state.beginGesture()
                            adjusting = true
                        }
                        state.preview(state.edit.copy(adjust = update(state.edit.adjust, v)))
                    },
                    onValueChangeFinished = { adjusting = false },
                    valueRange = -1f..1f,
                    modifier = Modifier.weight(1f),
                    colors = editorSliderColors(),
                )
            }
        }
    }
}

/** Ползунок на чёрном: белая ручка, акцентная заполненная часть. */
@Composable
private fun editorSliderColors() = SliderDefaults.colors(
    thumbColor = EDITOR_CONTENT,
    activeTrackColor = ReachTheme.colors.accent,
    inactiveTrackColor = EDITOR_PILL,
)

private fun FilterPreset.label(): Int = when (this) {
    FilterPreset.None -> R.string.photo_filter_none
    FilterPreset.Mono -> R.string.photo_filter_mono
    FilterPreset.Noir -> R.string.photo_filter_noir
    FilterPreset.Sepia -> R.string.photo_filter_sepia
    FilterPreset.Warm -> R.string.photo_filter_warm
    FilterPreset.Cool -> R.string.photo_filter_cool
    FilterPreset.Vintage -> R.string.photo_filter_vintage
    FilterPreset.Vivid -> R.string.photo_filter_vivid
}

@Composable
private fun TextInputDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.photo_edit_text_hint)) },
                maxLines = 4,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text(stringResource(R.string.photo_edit_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Что тянем: угол рамки или всю рамку. */
private enum class CropTarget { None, Move, TopLeft, TopRight, BottomLeft, BottomRight }

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

/** Затемнение вне рамки; [frame] — ещё и сама рамка с сеткой и уголками (инструмент «Кадр»). */
private fun DrawScope.drawCropShade(crop: NormRect, ox: Float, oy: Float, dw: Float, dh: Float, frame: Boolean) {
    val l = ox + crop.left * dw
    val t = oy + crop.top * dh
    val r = ox + crop.right * dw
    val b = oy + crop.bottom * dh
    val shade = Color.Black.copy(alpha = if (frame) 0.6f else 0.85f)
    drawRect(shade, Offset(ox, oy), Size(dw, t - oy))
    drawRect(shade, Offset(ox, b), Size(dw, oy + dh - b))
    drawRect(shade, Offset(ox, t), Size(l - ox, b - t))
    drawRect(shade, Offset(r, t), Size(ox + dw - r, b - t))
    if (!frame) return
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
        listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f),
        listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f),
    )) {
        drawLine(Color.White, Offset(x, y), Offset(x + len * sx, y), thick)
        drawLine(Color.White, Offset(x, y), Offset(x, y + len * sy), thick)
    }
}

private val PALETTE = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFEC407A),
)

private const val MIN_SIZE = 0.08f
private val HANDLE_RADIUS = 28.dp
/** Толщина кисти — доля меньшей стороны фото. */
private const val DEFAULT_BRUSH_WIDTH = 0.012f
private const val MIN_BRUSH_WIDTH = 0.003f
private const val MAX_BRUSH_WIDTH = 0.05f
/** Высота шрифта надписи — доля меньшей стороны фото. */
private const val DEFAULT_TEXT_SIZE = 0.08f
private const val MIN_TEXT_SIZE = 0.02f
private const val MAX_TEXT_SIZE = 0.4f
private const val TEXT_HIT_SLOP = 24f
private const val THUMB_SIDE = 160
