package io.github.iufb.reach.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.content.ContextCompat
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.decodeWaveform
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.RecorderState
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.glass
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Запись голосового или видеокружка в поле ввода. Вид ([MicButton], [RecordingBar]) от режима
 * не зависит: кружок передаёт свои колбэки и [RecorderState] со своим временем начала.
 */
internal class VoiceInput(
    val state: RecorderState,
    /** Палец лёг на микрофон. */
    val onStart: () -> Unit,
    /** Свайп вверх: запись без удержания. */
    val onLock: () -> Unit,
    /** Палец отпущен без фиксации — отправить. */
    val onRelease: () -> Unit,
    /** «Стоп» в зафиксированном режиме — предпрослушивание. */
    val onStop: () -> Unit,
    val onSend: () -> Unit,
    /** Свайп влево или «удалить». */
    val onDiscard: () -> Unit,
    val onTogglePreview: () -> Unit,
    /** Кнопка записывает видеокружок, а не голосовое. */
    val videoMode: Boolean = false,
    /** Короткий тап — сменить голосовое ↔ кружок; `null` — кружки недоступны, запись сразу по касанию. */
    val onToggleMode: (() -> Unit)? = null,
    /** Позиция предпрослушивания 0..1; читается каждый кадр, пока оно играет. */
    val previewProgress: () -> Float = { 0f },
)

/**
 * Общее для [MicButton] и [RecordingBar] состояние записи: сдвиг пальца и анимация отмены
 * («микрофон в корзину»). Живёт в поле ввода ([rememberRecordingUi]); сдвиг читается только
 * в graphicsLayer и отрисовке, поэтому ведение пальца не перекомпоновывает поле.
 */
@Stable
internal class RecordingUi(private val scope: CoroutineScope) {
    /** Сдвиг пальца от точки касания, px: влево и вверх, не больше порогов отмены и фиксации. */
    var dragX by mutableFloatStateOf(0f)
        private set
    var dragY by mutableFloatStateOf(0f)
        private set
    private var cancelPx = 1f
    private var lockPx = 1f

    /** 0..1 — насколько палец близок к отмене. */
    val cancelProgress: Float get() = (-dragX / cancelPx).coerceIn(0f, 1f)

    /** 0..1 — насколько палец близок к фиксации. */
    val lockProgress: Float get() = (-dragY / lockPx).coerceIn(0f, 1f)

    /** Ход анимации отмены 0..1; идёт, пока [cancelling]. */
    val trash = Animatable(0f)

    /** Запись отменена, полоска ещё показывает, как микрофон падает в корзину. */
    var cancelling by mutableStateOf(false)
        private set

    private var springJob: Job? = null
    private var trashJob: Job? = null

    internal fun thresholds(cancel: Float, lock: Float) {
        cancelPx = cancel
        lockPx = lock
    }

    internal fun drag(x: Float, y: Float) {
        springJob?.cancel()
        dragX = x
        dragY = y
    }

    /** Новая запись: прервать отмену предыдущей. */
    internal fun reset() {
        trashJob?.cancel()
        cancelling = false
        drag(0f, 0f)
    }

    /** Круг и надписи пружиной возвращаются на место. */
    internal fun springBack() {
        springJob?.cancel()
        val fromX = dragX
        val fromY = dragY
        if (fromX == 0f && fromY == 0f) return
        springJob = scope.launch {
            animate(1f, 0f, animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                dragX = fromX * v
                dragY = fromY * v
            }
        }
    }

    /** Отменить запись (или удалить записанное) с анимацией корзины. */
    fun discard(voice: VoiceInput) {
        voice.onDiscard()
        springBack()
        cancelling = true
        trashJob?.cancel()
        trashJob = scope.launch {
            trash.snapTo(0f)
            trash.animateTo(1f, tween(TRASH_MS, easing = LinearEasing))
            cancelling = false
        }
    }
}

@Composable
internal fun rememberRecordingUi(): RecordingUi {
    val scope = rememberCoroutineScope()
    return remember(scope) { RecordingUi(scope) }
}

/**
 * Микрофон или камера, как в Telegram iOS: удержание — запись (под пальцем большой круг с «облаком»
 * по громкости, над ним капсула замка), отпустить — отправить, влево — отмена, вверх — фиксация;
 * после фиксации круг — «отправить», капсула — «стоп». Короткий тап — смена голосовое ↔ кружок
 * ([VoiceInput.onToggleMode]) с подсказкой. Без разрешений (`RECORD_AUDIO`, для кружка ещё
 * `CAMERA`) нажатие только запрашивает их.
 *
 * Касается только кружок размером с кнопку; большой круг, капсула и подсказка рисуются за её
 * границами, поэтому родитель не должен обрезать содержимое.
 */
@Composable
internal fun MicButton(voice: VoiceInput, ui: RecordingUi) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(voice)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (current.videoMode && !result.values.all { it }) {
            Toast.makeText(context, R.string.video_note_permission, Toast.LENGTH_LONG).show()
        }
    }
    val state = voice.state
    val sendMode = (state as? RecorderState.Recording)?.locked == true || state is RecorderState.Recorded
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(HINT_MS)
            hint = false
        }
    }
    if (state !is RecorderState.Idle && hint) hint = false
    val description = stringResource(
        when {
            sendMode -> R.string.chat_send
            voice.videoMode -> R.string.video_note_record
            else -> R.string.voice_record
        },
    )
    val idleIcon by animateFloatAsState(if (state is RecorderState.Idle) 1f else 0f, tween(ICON_FADE_MS), label = "micIcon")
    Box(Modifier.size(PillHeight), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .pointerInput(Unit) {
                    val cancelPx = CANCEL_DISTANCE.toPx()
                    val lockPx = LOCK_DISTANCE.toPx()
                    ui.thresholds(cancelPx, lockPx)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (current.state !is RecorderState.Idle) {
                            // Зафиксированная запись или предпрослушивание: круг — «отправить»
                            if (waitForUpOrCancellation() != null) current.onSend()
                            return@awaitEachGesture
                        }
                        val toggle = current.onToggleMode
                        if (toggle != null) {
                            // null — держат дольше порога (запись), true — отпустили раньше (смена режима)
                            val released = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() != null }
                            if (released == true) {
                                toggle()
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                hint = true
                            }
                            if (released != null) return@awaitEachGesture
                        }
                        val needed = if (current.videoMode) {
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        } else {
                            arrayOf(Manifest.permission.RECORD_AUDIO)
                        }
                        val missing = needed.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            permission.launch(missing.toTypedArray())
                            return@awaitEachGesture
                        }
                        ui.reset()
                        current.onStart()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var total = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                ui.springBack()
                                break
                            }
                            if (!change.pressed) {
                                current.onRelease()
                                ui.springBack()
                                break
                            }
                            total += change.positionChange()
                            change.consume()
                            ui.drag(total.x.coerceIn(-cancelPx, 0f), total.y.coerceIn(-lockPx, 0f))
                            if (total.x < -cancelPx) {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                ui.discard(current)
                                break
                            }
                            if (total.y < -lockPx) {
                                current.onLock()
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                ui.springBack()
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Смена микрофон ↔ камера: старый значок сжимается, новый вырастает
            AnimatedContent(
                targetState = voice.videoMode,
                modifier = Modifier.graphicsLayer { alpha = idleIcon },
                transitionSpec = {
                    (scaleIn(tween(MODE_SWAP_MS), initialScale = 0.3f) + fadeIn(tween(MODE_SWAP_MS))) togetherWith
                        (scaleOut(tween(MODE_SWAP_MS), targetScale = 0.3f) + fadeOut(tween(MODE_SWAP_MS)))
                },
                label = "micMode",
            ) { video ->
                Icon(
                    painterResource(if (video) R.drawable.ic_videocam else R.drawable.ic_mic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ReachTheme.colors.label,
                )
            }
        }
        ModeHint(hint, voice.videoMode)
        LockCapsule(voice, ui)
        BigCircle(voice, ui)
    }
}

/** Большой круг под пальцем: появляется пружиной, едет за пальцем, вокруг «облако» по громкости. */
@Composable
private fun BigCircle(voice: VoiceInput, ui: RecordingUi) {
    val state = voice.state
    val recording = state as? RecorderState.Recording
    // Предпрослушивание: круг сжимается до обычной кнопки «отправить»
    val target = when (state) {
        is RecorderState.Recording -> 1f
        is RecorderState.Recorded -> SEND_SCALE
        RecorderState.Idle -> 0f
    }
    val scale = remember { Animatable(0f) }
    LaunchedEffect(target) {
        scale.animateTo(
            target,
            if (target == 0f) tween(CIRCLE_OUT_MS, easing = FastOutLinearInEasing) else spring(dampingRatio = 0.6f, stiffness = 420f),
        )
    }
    if (target == 0f && scale.targetValue == 0f && !scale.isRunning) return
    val level by animateFloatAsState(recording?.level ?: 0f, spring(stiffness = Spring.StiffnessLow), label = "level")
    val pulse by animateFloatAsState(if (recording != null) 1f else 0f, tween(CIRCLE_OUT_MS), label = "pulse")
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val colors = ReachTheme.colors
    val sendMode = recording?.locked == true || state is RecorderState.Recorded
    Box(
        Modifier
            .requiredSize(BIG_CIRCLE * PULSE_ROOM)
            .graphicsLayer {
                translationX = ui.dragX
                translationY = ui.dragY
            }
            .drawBehind {
                val radius = BIG_CIRCLE.toPx() / 2 * scale.value * (1f - CANCEL_SHRINK * ui.cancelProgress)
                val p = pulse
                if (p > 0f) {
                    // Облако: дышит само и растёт с громкостью (у кружка громкости нет — только дыхание)
                    val grow = 1f + (PULSE_BASE + PULSE_BREATH * breath.value + PULSE_LEVEL * level) * p
                    drawCircle(colors.accent.copy(alpha = PULSE_ALPHA * p), radius * grow)
                }
                drawCircle(colors.accent, radius)
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = sendMode,
            modifier = Modifier.graphicsLayer {
                val k = bigIconScale(scale.value)
                scaleX = k
                scaleY = k
            },
            label = "bigIcon",
        ) { send ->
            Icon(
                painterResource(
                    when {
                        send -> R.drawable.ic_send
                        voice.videoMode -> R.drawable.ic_videocam
                        else -> R.drawable.ic_mic
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(BIG_ICON),
                tint = colors.onAccent,
            )
        }
    }
}

/** Значок в большом круге: при размере обычной кнопки — как значок «отправить» в ней. */
private fun bigIconScale(circle: Float): Float = if (circle <= SEND_SCALE) {
    circle / SEND_SCALE * SMALL_ICON_RATIO
} else {
    SMALL_ICON_RATIO + (1f - SMALL_ICON_RATIO) * (circle - SEND_SCALE) / (1f - SEND_SCALE)
}

/**
 * Капсула над кнопкой: замок со стрелкой вверх; чем ближе палец к фиксации, тем плотнее закрыт замок.
 * После фиксации капсула сжимается в круглую кнопку «стоп».
 */
@Composable
private fun LockCapsule(voice: VoiceInput, ui: RecordingUi) {
    val recording = voice.state as? RecorderState.Recording
    val visible = recording != null
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        appear.animateTo(if (visible) 1f else 0f, if (visible) spring(dampingRatio = 0.7f, stiffness = 380f) else tween(CIRCLE_OUT_MS))
    }
    if (!visible && appear.targetValue == 0f && !appear.isRunning) return
    val locked = recording?.locked == true
    val morph by animateFloatAsState(if (locked) 1f else 0f, tween(LOCK_MORPH_MS, easing = FastOutSlowInEasing), label = "lockMorph")
    val colors = ReachTheme.colors
    val height = lerp(LOCK_HEIGHT, LOCK_WIDTH, morph)
    val shape = RoundedCornerShape(percent = 50)
    val stopLabel = stringResource(R.string.voice_stop)
    val lockLabel = stringResource(R.string.voice_lock)
    Box(
        Modifier
            .offset {
                val rise = ((1f - appear.value) * LOCK_RISE.toPx()).toInt()
                IntOffset(0, -(LOCK_BOTTOM.roundToPx() + height.roundToPx() / 2) + rise)
            }
            .requiredSize(LOCK_WIDTH, height)
            .graphicsLayer {
                val a = appear.value.coerceIn(0f, 1f)
                alpha = a * (1f - ui.cancelProgress)
                scaleX = 0.6f + 0.4f * a
                scaleY = 0.6f + 0.4f * a
            }
            .shadow(LOCK_ELEVATION, shape, clip = false, ambientColor = LOCK_SHADOW, spotColor = LOCK_SHADOW)
            .clip(shape)
            .background(colors.bar)
            .then(
                if (locked) {
                    Modifier.clickable(onClickLabel = stopLabel, onClick = voice.onStop)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = if (locked) stopLabel else lockLabel },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (morph < 1f) LockGlyph(ui, colors.secondaryLabel, Modifier.graphicsLayer { alpha = 1f - morph })
        if (morph > 0f) {
            Icon(
                painterResource(R.drawable.ic_stop),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(22.dp).graphicsLayer { alpha = morph },
                tint = colors.destructive,
            )
        }
    }
}

/** Замок (дужка опускается по мере свайпа вверх) и покачивающаяся стрелка под ним. */
@Composable
private fun LockGlyph(ui: RecordingUi, color: Color, modifier: Modifier) {
    val bob = rememberInfiniteTransition(label = "lockBob").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BOB_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "lockBob",
    )
    Column(modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val p = ui.lockProgress
            val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(color, Offset(5.5f * u, 11f * u), Size(13f * u, 10f * u), CornerRadius(2.5f * u), style = stroke)
            // Открытый замок: дужка поднята, правая ножка не в корпусе
            val lift = (1f - p) * 3f * u
            val rightEnd = (8.5f * u - lift) + (11f * u - (8.5f * u - lift)) * p
            val shackle = Path().apply {
                moveTo(8.5f * u, 11f * u)
                lineTo(8.5f * u, 7.5f * u - lift)
                arcTo(Rect(8.5f * u, 4f * u - lift, 15.5f * u, 11f * u - lift), 180f, 180f, false)
                lineTo(15.5f * u, rightEnd)
            }
            drawPath(shackle, color, style = stroke)
        }
        Canvas(
            Modifier.size(width = 20.dp, height = 10.dp).graphicsLayer {
                alpha = 1f - ui.lockProgress
                translationY = -bob.value * 3.dp.toPx()
            },
        ) {
            val w = size.width
            val h = size.height
            val chevron = Path().apply {
                moveTo(w * 0.2f, h * 0.85f)
                lineTo(w * 0.5f, h * 0.2f)
                lineTo(w * 0.8f, h * 0.85f)
            }
            drawPath(chevron, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Подсказка после тапа по кнопке, как в Telegram iOS: «Удерживайте, чтобы записать …». */
@Composable
private fun ModeHint(visible: Boolean, video: Boolean) {
    val hintAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(HINT_FADE_MS), label = "hint")
    if (!visible && hintAlpha == 0f) return
    Column(
        Modifier
            .offset(y = -(PillHeight + 6.dp))
            .wrapContentSize(Alignment.BottomEnd, unbounded = true)
            .graphicsLayer { alpha = hintAlpha },
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            stringResource(if (video) R.string.voice_hint_video else R.string.voice_hint_audio),
            modifier = Modifier
                .widthIn(max = HINT_MAX_WIDTH)
                .background(HINT_BACKGROUND, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
        Canvas(Modifier.padding(end = PillHeight / 2 - 7.dp).size(width = 14.dp, height = 7.dp)) {
            val tail = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(tail, HINT_BACKGROUND)
        }
    }
}

/**
 * Стеклянная пилюля вместо поля ввода, пока идёт запись, открыто предпрослушивание или
 * доигрывает анимация отмены: мигающая точка, таймер с сотыми, «‹ Влево — отмена» (после
 * фиксации — «Отмена»); в предпрослушивании — корзина и волна с прогрессом.
 * Последнее состояние держится, пока поле ввода прячет пилюлю обратно.
 */
@Composable
internal fun RecordingBar(voice: VoiceInput, ui: RecordingUi, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(voice.state) }
    if (voice.state !is RecorderState.Idle) last = voice.state
    Box(
        // Касания не проходят к полю ввода под пилюлей
        modifier.height(PillHeight).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
    ) {
        Box(Modifier.matchParentSize().glass(RoundedCornerShape(percent = 50)))
        AnimatedContent(
            targetState = last,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it is RecorderState.Recorded },
            transitionSpec = { (fadeIn(tween(CONTENT_SWAP_MS)) togetherWith fadeOut(tween(CONTENT_SWAP_MS))).using(SizeTransform(clip = false)) },
            label = "recordingBar",
        ) { shown ->
            when (shown) {
                is RecorderState.Recording -> RecordingContent(shown, active = voice.state is RecorderState.Recording, voice, ui)
                is RecorderState.Recorded -> PreviewContent(shown, active = voice.state is RecorderState.Recorded, voice, ui)
                RecorderState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RecordingContent(state: RecorderState.Recording, active: Boolean, voice: VoiceInput, ui: RecordingUi) {
    val haptics = LocalHapticFeedback.current
    val colors = ReachTheme.colors
    // При отмене таймер и надписи гаснут, остаётся падающий в корзину микрофон
    val fade = Modifier.graphicsLayer { alpha = if (ui.cancelling) 1f - (ui.trash.value / TRASH_FADE).coerceIn(0f, 1f) else 1f }
    Row(Modifier.fillMaxSize().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordingDot(ui, active)
        Spacer(Modifier.width(4.dp))
        RecordingTimer(state.startedAt, running = active, modifier = fade)
        Box(
            Modifier.weight(1f).fillMaxHeight().padding(end = BAR_END_INSET).then(fade),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(state.locked, label = "cancelLabel") { locked ->
                if (locked) {
                    Text(
                        stringResource(R.string.cancel),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = active) {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                ui.discard(voice)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.accent,
                    )
                } else {
                    SlideToCancel(ui)
                }
            }
        }
    }
}

/** «‹ Влево — отмена»: едет за пальцем и бледнеет к порогу отмены; по тексту бежит блик, стрелка покачивается. */
@Composable
private fun SlideToCancel(ui: RecordingUi) {
    val colors = ReachTheme.colors
    val transition = rememberInfiniteTransition(label = "slideToCancel")
    val shimmer = transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)),
        label = "shimmer",
    )
    val nudge = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(NUDGE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nudge",
    )
    val highlight = colors.label
    Row(
        Modifier
            .graphicsLayer {
                translationX = ui.dragX
                alpha = 1f - ui.cancelProgress
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                val x = shimmer.value * size.width
                val band = size.width * 0.35f
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent), startX = x - band, endX = x + band),
                    blendMode = BlendMode.SrcAtop,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_chevron_left),
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { translationX = -nudge.value * 3.dp.toPx() },
            tint = colors.secondaryLabel,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.voice_slide_to_cancel),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondaryLabel,
            maxLines = 1,
        )
    }
}

/** Мигающая красная точка; при отмене на её месте микрофон подлетает и падает в корзину. */
@Composable
private fun RecordingDot(ui: RecordingUi, active: Boolean) {
    val colors = ReachTheme.colors
    Box(Modifier.size(DOT_SLOT), contentAlignment = Alignment.Center) {
        if (ui.cancelling) {
            TrashDrop(ui)
        } else {
            val blink = rememberInfiniteTransition(label = "dot").animateFloat(
                initialValue = 1f,
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(tween(BLINK_MS, delayMillis = BLINK_HOLD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "dot",
            )
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer { alpha = if (active) blink.value else 1f }
                    .background(colors.destructive, CircleShape),
            )
        }
    }
}

/** Анимация отмены по [RecordingUi.trash]: микрофон взлетает, переворачивается и падает в выехавшую корзину. */
@Composable
private fun TrashDrop(ui: RecordingUi) {
    val colors = ReachTheme.colors
    Icon(
        painterResource(R.drawable.ic_delete),
        contentDescription = null,
        modifier = Modifier.size(22.dp).graphicsLayer {
            val t = ui.trash.value
            val shown = when {
                t < BIN_IN_START -> 0f
                t < BIN_IN_END -> (t - BIN_IN_START) / (BIN_IN_END - BIN_IN_START)
                t < BIN_OUT_START -> 1f
                else -> 1f - (t - BIN_OUT_START) / (1f - BIN_OUT_START)
            }
            alpha = shown
            translationY = (1f - LinearOutSlowInEasing.transform(shown)) * BIN_DROP.toPx()
        },
        tint = colors.secondaryLabel,
    )
    Icon(
        painterResource(R.drawable.ic_mic),
        contentDescription = null,
        modifier = Modifier.size(20.dp).graphicsLayer {
            val t = ui.trash.value
            val rise = MIC_RISE.toPx()
            if (t < MIC_TOP) {
                val k = FastOutSlowInEasing.transform(t / MIC_TOP)
                translationY = -rise * k
                rotationZ = 180f * k
                alpha = 1f
            } else {
                val k = FastOutLinearInEasing.transform(((t - MIC_TOP) / (MIC_IN - MIC_TOP)).coerceIn(0f, 1f))
                translationY = -rise + (rise + 4.dp.toPx()) * k
                rotationZ = 180f
                scaleX = 1f - 0.4f * k
                scaleY = 1f - 0.4f * k
                alpha = if (t < MIC_IN) 1f else 0f
            }
        },
        tint = colors.destructive,
    )
}

@Composable
private fun RecordingTimer(startedAt: Long, running: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt, running) {
        while (running) {
            withFrameMillis { }
            now = SystemClock.elapsedRealtime()
        }
    }
    Text(
        formatRecordingTime(now - startedAt),
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        color = ReachTheme.colors.label,
        maxLines = 1,
    )
}

/** Предпрослушивание: корзина и синяя пилюля с play/pause, волной (с прогрессом) и длительностью. */
@Composable
private fun PreviewContent(state: RecorderState.Recorded, active: Boolean, voice: VoiceInput, ui: RecordingUi) {
    val haptics = LocalHapticFeedback.current
    val colors = ReachTheme.colors
    val current by rememberUpdatedState(voice)
    val playing = state.previewPlaying
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, active) {
        if (!active) return@LaunchedEffect
        if (playing) {
            while (true) {
                withFrameMillis { }
                progress = current.previewProgress()
            }
        } else {
            // Доиграло до конца — волна снова пустая; пауза — прогресс остаётся
            val p = current.previewProgress()
            progress = if (p >= PLAYED_TO_END) 0f else p
        }
    }
    // Пилюля раскрывается справа налево — от кнопки, из которой «вышла» запись
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing)) }
    val levels = remember(state.recording) { decodeWaveform(state.recording.waveform) }
    val duration = state.recording.durationSec.toLong()
    Row(Modifier.fillMaxSize().padding(start = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = active, onClickLabel = stringResource(R.string.voice_delete)) {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    ui.discard(voice)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.voice_delete),
                modifier = Modifier.size(22.dp).graphicsLayer {
                    // Отмена: корзина «вздрагивает»
                    rotationZ = if (ui.cancelling) kotlin.math.sin(ui.trash.value * WOBBLE_TURNS) * 14f * (1f - ui.trash.value) else 0f
                },
                tint = colors.accent,
            )
        }
        Row(
            Modifier
                .weight(1f)
                .height(PREVIEW_HEIGHT)
                .graphicsLayer { alpha = if (ui.cancelling) 1f - (ui.trash.value / TRASH_FADE).coerceIn(0f, 1f) else 1f }
                .drawWithContent { clipRect(left = size.width * (1f - reveal.value)) { this@drawWithContent.drawContent() } }
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.accent)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(enabled = active, onClick = voice.onTogglePreview),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(playing, label = "previewPlay") { isPlaying ->
                    Icon(
                        painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (isPlaying) R.string.video_pause else R.string.video_play),
                        modifier = Modifier.size(18.dp),
                        tint = colors.onAccent,
                    )
                }
            }
            Waveform(
                levels = levels,
                progress = progress,
                onSeek = null,
                modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 6.dp),
                played = colors.onAccent,
                rest = colors.onAccent.copy(alpha = 0.4f),
            )
            Text(
                formatDuration(if (playing || progress > 0f) (progress * duration).toLong() else duration),
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = colors.onAccent,
                maxLines = 1,
            )
        }
    }
}

/** Время записи как в Telegram iOS: минуты, секунды и сотые — «0:01,74». */
internal fun formatRecordingTime(elapsedMs: Long): String {
    val ms = elapsedMs.coerceAtLeast(0)
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d,%02d", seconds / 60, seconds % 60, ms / 10 % 100)
}

private val CANCEL_DISTANCE = 120.dp
/** Дольше — запись, короче — тап. */
private const val HOLD_MS = 250L
private val LOCK_DISTANCE = 80.dp

/** Большой круг под пальцем (Telegram iOS — около 96 pt) и место под «облако» вокруг него. */
private val BIG_CIRCLE = 96.dp
private const val PULSE_ROOM = 1.6f
private val BIG_ICON = 30.dp
/** Размер обычной кнопки относительно большого круга — до него круг сжимается в предпрослушивании. */
private val SEND_SCALE = PillHeight / BIG_CIRCLE
/** Значок «отправить» в обычной кнопке (20 dp) относительно [BIG_ICON]. */
private const val SMALL_ICON_RATIO = 20f / 30f
private const val CANCEL_SHRINK = 0.2f
private const val PULSE_BASE = 0.06f
private const val PULSE_BREATH = 0.06f
private const val PULSE_LEVEL = 0.4f
private const val PULSE_ALPHA = 0.24f

/** Капсула замка: снизу на этом расстоянии от центра кнопки, над большим кругом. */
private val LOCK_WIDTH = 40.dp
private val LOCK_HEIGHT = 72.dp
private val LOCK_BOTTOM = BIG_CIRCLE / 2 + 20.dp
private val LOCK_RISE = 24.dp
private val LOCK_ELEVATION = 8.dp
private val LOCK_SHADOW = Color.Black.copy(alpha = 0.18f)

/** Правый край пилюли под большим кругом — надпись по центру видимой части. */
private val BAR_END_INSET = 28.dp
private val DOT_SLOT = 28.dp
private val PREVIEW_HEIGHT = 34.dp
private val MIC_RISE = 44.dp
private val BIN_DROP = 26.dp

private val HINT_MAX_WIDTH = 240.dp
private val HINT_BACKGROUND = Color(0xE6202022)

// Тайминги — на глаз по поведению Telegram iOS, без видеореференса (#296); сверить по видео
private const val ICON_FADE_MS = 150
private const val MODE_SWAP_MS = 220
private const val CIRCLE_OUT_MS = 180
private const val BREATH_MS = 1_400
private const val LOCK_MORPH_MS = 260
private const val BOB_MS = 900
private const val HINT_MS = 2_500L
private const val HINT_FADE_MS = 200
private const val CONTENT_SWAP_MS = 200
private const val SHIMMER_MS = 1_800
private const val NUDGE_MS = 700
private const val BLINK_MS = 500
private const val BLINK_HOLD_MS = 200
private const val REVEAL_MS = 280
private const val TRASH_MS = 900
/** Доля [TRASH_MS], за которую гаснут таймер и надписи. */
private const val TRASH_FADE = 0.2f
private const val MIC_TOP = 0.38f
private const val MIC_IN = 0.72f
private const val BIN_IN_START = 0.28f
private const val BIN_IN_END = 0.48f
private const val BIN_OUT_START = 0.8f
private const val WOBBLE_TURNS = 25f
private const val PLAYED_TO_END = 0.98f
