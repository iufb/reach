package io.github.iufb.reach.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.util.UUID
import kotlin.math.min

/** Записанный кружок, готовый к отправке (`InputVideoNote`). */
class VideoNoteRecording(
    val path: String,
    val durationSec: Int,
    /** Сторона квадрата, ≤ 640. */
    val length: Int,
    val thumbnailPath: String?,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
)

/**
 * Камера для видеокружков (PRODUCT.md §4.4): CameraX, квадратный кадр через `ViewPort` 1:1,
 * SD-качество, фронтальная камера по умолчанию с зеркалом, до 60 с. Живёт на экране чата
 * (нужны `LifecycleOwner` и `PreviewView`); все вызовы — из главного потока.
 *
 * Порядок: [start] → экран показывает превью и зовёт [open] → запись → [finish].
 * Готовый файл приходит в колбэк [start]; отменённый или слишком короткий удаляется.
 */
@Stable
class VideoNoteCamera(private val context: Context) {
    /** [Recorded] — запись остановлена в зафиксированном режиме: кружок проигрывается, его можно отправить или удалить. */
    enum class Phase { Idle, Starting, Recording, Recorded }

    /** Чем кончилась запись: экран по нему выбирает анимацию ухода (улетает в ленту или сжимается). */
    enum class Outcome { Send, Discard }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** `elapsedRealtime` начала записи; 0 — ещё не началась. */
    var startedAt by mutableLongStateOf(0L)
        private set

    /**
     * `elapsedRealtime` нажатия: таймер в поле ввода считает от него, а не от [startedAt] — иначе,
     * пока камера открывается и подбирает экспозицию (до ~2 с), ему не от чего считать, и он прыгал на 0.
     */
    var requestedAt by mutableLongStateOf(0L)
        private set

    /** Зафиксирована: палец можно отпустить. */
    var locked by mutableStateOf(false)
        private set

    var front by mutableStateOf(true)
        private set

    /** Вспышка: у задней камеры — фонарик, у фронтальной экран светит сам (рисует экран записи). */
    var flash by mutableStateOf(false)
        private set

    /** У открытой камеры есть фонарик. */
    var torchAvailable by mutableStateOf(false)
        private set

    /** `null` — запись ещё идёт; иначе — чем она кончается (финализация файла может занять ещё долю секунды). */
    var outcome by mutableStateOf<Outcome?>(null)
        private set

    /** Записанный файл в фазе [Phase.Recorded]. */
    var recordedPath by mutableStateOf<String?>(null)
        private set
    var recordedDurationSec by mutableStateOf(0)
        private set

    /** Доля проигранного в предпросмотре 0..1; пишет экран с плеером каждый кадр, читает полоса записи. */
    var reviewProgress = 0f

    /** Предпросмотр играет (в фазе [Phase.Recorded]). */
    var reviewPlaying by mutableStateOf(true)
        private set

    val previewView: PreviewView = PreviewView(context).apply {
        // TextureView: превью обрезается кругом, SurfaceView бы не обрезался
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /**
     * Автоэкспозиция сошлась. Сразу после открытия камера полсекунды подбирает экспозицию, и записанные
     * в это время кадры почти белые: кружок мигал белым в начале каждого круга и получал белое превью (#214).
     */
    private val exposureReady = MutableStateFlow(false)

    /** В превью уже настоящая картинка: камера открылась и экспозиция сошлась. До этого экран рисует заглушку. */
    val previewReady: StateFlow<Boolean> = exposureReady.asStateFlow()

    @OptIn(ExperimentalCamera2Interop::class)
    private val preview = Preview.Builder().also { builder ->
        Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val state = result.get(CaptureResult.CONTROL_AE_STATE)
                // Камера без автоэкспозиции не сообщает состояние — ждать нечего
                if (state == null || state in AE_SETTLED) exposureReady.value = true
            }
        })
    }.build()
    private val capture = VideoCapture.Builder(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(BIT_RATE)
            .build(),
    ).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()

    private var provider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var reviewOnFinish = false
    private var owner: LifecycleOwner? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var sendOnFinish = false
    private var onRecorded: ((String) -> Unit)? = null

    /** Палец лёг на кнопку: показать камеру и начать запись, как только она откроется. */
    fun start(onRecorded: (path: String) -> Unit) {
        if (phase != Phase.Idle) return
        this.onRecorded = onRecorded
        locked = false
        sendOnFinish = false
        reviewOnFinish = false
        outcome = null
        requestedAt = SystemClock.elapsedRealtime()
        phase = Phase.Starting
    }

    /** Экран с превью готов: открыть камеру и, если запись ещё нужна, начать её. */
    suspend fun open(owner: LifecycleOwner) {
        val cameraProvider = provider ?: awaitCameraProvider().also { provider = it }
        this.owner = owner
        preview.setSurfaceProvider(previewView.surfaceProvider)
        exposureReady.value = false
        bind(cameraProvider)
        // Пишем, когда экспозиция сошлась; если камера так и не сообщила — всё равно начинаем
        withTimeoutOrNull(EXPOSURE_TIMEOUT_MS) { exposureReady.first { it } }
        // Пока ждали, палец могли отпустить — тогда записывать уже нечего
        if (phase == Phase.Starting && recording == null) beginRecording()
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { runCatching { future.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) } },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun flip() {
        if (phase == Phase.Recorded) return
        front = !front
        // Новая камера тоже подбирает экспозицию: экран держит размытый последний кадр, пока она не сойдётся
        exposureReady.value = false
        provider?.let { runCatching { bind(it) }.onFailure { e -> Log.w(TAG, "flip failed", e) } }
    }

    fun toggleFlash() {
        flash = !flash
        applyTorch()
    }

    private fun applyTorch() {
        val camera = boundCamera ?: return
        torchAvailable = camera.cameraInfo.hasFlashUnit()
        if (torchAvailable) camera.cameraControl.enableTorch(flash && !front)
    }

    /** «Стоп» в зафиксированном режиме: запись — в предпросмотр ([Phase.Recorded]), а не сразу в чат. */
    fun stopForReview() {
        val current = recording ?: return finish(send = false)
        reviewOnFinish = true
        current.stop()
    }

    fun toggleReviewPlayback() {
        reviewPlaying = !reviewPlaying
    }

    fun lock() {
        if (phase != Phase.Idle) locked = true
    }

    /** Остановить: [send] — отправить записанное, иначе удалить. */
    fun finish(send: Boolean) {
        if (phase == Phase.Recorded) return finishReview(send)
        if (outcome == null) outcome = if (send) Outcome.Send else Outcome.Discard
        sendOnFinish = send
        reviewOnFinish = false
        val current = recording
        if (current != null) current.stop() else close()
    }

    private fun finishReview(send: Boolean) {
        val path = recordedPath ?: return close()
        outcome = if (send) Outcome.Send else Outcome.Discard
        val callback = onRecorded
        close()
        if (send) callback?.invoke(path) else File(path).delete()
    }

    /** Экран с превью ушёл: камеру отпускаем, идущую запись или предпросмотр отменяем. */
    fun release() {
        when {
            phase == Phase.Recorded -> finishReview(send = false)
            // Остановка уже идёт (отправка): камеру отпустит close() после финализации файла
            recording != null -> if (outcome == null) finish(send = false)
            phase != Phase.Idle -> {
                outcome = Outcome.Discard
                close()
            }
            else -> unbind()
        }
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        val lifecycleOwner = owner ?: return
        cameraProvider.unbindAll()
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val group = UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(1, 1), rotation).build())
            .addUseCase(preview)
            .addUseCase(capture)
            .build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, group)
        applyTorch()
    }

    // Разрешения CAMERA и RECORD_AUDIO проверяет кнопка до start()
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalPersistentRecording::class)
    private fun beginRecording() {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val output = File(dir, "${UUID.randomUUID()}.mp4")
        file = output
        val options = FileOutputOptions.Builder(output).setDurationLimitMillis(MAX_DURATION_MS).build()
        recording = capture.output.prepareRecording(context, options)
            .withAudioEnabled()
            // Смена камеры во время записи не прерывает её
            .asPersistentRecording()
            .start(ContextCompat.getMainExecutor(context)) { onEvent(it) }
    }

    private fun onEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                phase = Phase.Recording
                startedAt = SystemClock.elapsedRealtime()
            }
            is VideoRecordEvent.Finalize -> {
                val output = file
                val reachedLimit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                // Дошли до 60 с — отправляем, даже если палец ещё держит кнопку
                val send = (sendOnFinish || reachedLimit) && (!event.hasError() || reachedLimit)
                if (event.hasError() && !reachedLimit) Log.w(TAG, "recording failed: ${event.error}", event.cause)
                if (reviewOnFinish && output != null && (!event.hasError() || reachedLimit)) {
                    // Предпросмотр: файл остаётся, камера больше не нужна
                    recording = null
                    file = null
                    recordedPath = output.path
                    recordedDurationSec = ((event.recordingStats.recordedDurationNanos / 1_000_000 + 500) / 1000).toInt().coerceAtLeast(1)
                    reviewPlaying = true
                    phase = Phase.Recorded
                    unbind()
                    return
                }
                if (outcome == null) outcome = if (send) Outcome.Send else Outcome.Discard
                val callback = onRecorded
                close()
                if (output != null) {
                    if (send) callback?.invoke(output.path) else output.delete()
                }
            }
        }
    }

    private fun close() {
        recording = null
        file = null
        phase = Phase.Idle
        startedAt = 0
        locked = false
        onRecorded = null
        reviewOnFinish = false
        recordedPath = null
        recordedDurationSec = 0
        unbind()
    }

    private fun unbind() {
        provider?.unbindAll()
        boundCamera = null
        // Фонарик гаснет вместе с камерой; следующая запись — без вспышки, как в iOS
        flash = false
    }

    private companion object {
        const val TAG = "VideoNoteCamera"
        const val DIR = "video_notes"
        const val MAX_DURATION_MS = 60_000L
        const val BIT_RATE = 1_000_000
        const val EXPOSURE_TIMEOUT_MS = 1_000L
        val AE_SETTLED = setOf(
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
            CaptureResult.CONTROL_AE_STATE_LOCKED,
        )
    }
}

/**
 * Длительность, сторона и миниатюра записанного кружка; `null` — короче секунды или файл битый
 * (тогда файл удаляется).
 */
suspend fun describeVideoNote(path: String): VideoNoteRecording? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (durationMs < MIN_DURATION_MS || width <= 0 || height <= 0) {
            File(path).delete()
            return@withContext null
        }
        val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE, THUMB_SIZE)
        val thumbnail = frame?.let { bitmap ->
            runCatching {
                File(path.removeSuffix(".mp4") + "_thumb.jpg").also { out ->
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
                }
            }.getOrNull()
        }
        VideoNoteRecording(
            path = path,
            durationSec = ((durationMs + 500) / 1000).toInt().coerceAtLeast(1),
            length = min(min(width, height), MAX_LENGTH),
            thumbnailPath = thumbnail?.path,
            thumbnailWidth = frame?.width ?: 0,
            thumbnailHeight = frame?.height ?: 0,
        )
    } catch (e: Exception) {
        Log.w("VideoNoteCamera", "can't read recorded note", e)
        File(path).delete()
        null
    } finally {
        retriever.release()
    }
}

private const val MIN_DURATION_MS = 1_000L
private const val MAX_LENGTH = 640
private const val THUMB_SIZE = 320
private const val THUMB_QUALITY = 80
