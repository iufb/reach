package io.github.iufb.reach.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
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
import kotlinx.coroutines.withContext
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
    enum class Phase { Idle, Starting, Recording }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** `elapsedRealtime` начала записи; 0 — ещё не началась. */
    var startedAt by mutableLongStateOf(0L)
        private set

    /** Зафиксирована: палец можно отпустить. */
    var locked by mutableStateOf(false)
        private set

    var front by mutableStateOf(true)
        private set

    val previewView: PreviewView = PreviewView(context).apply {
        // TextureView: превью обрезается кругом, SurfaceView бы не обрезался
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private val preview = Preview.Builder().build()
    private val capture = VideoCapture.Builder(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(BIT_RATE)
            .build(),
    ).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()

    private var provider: ProcessCameraProvider? = null
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
        phase = Phase.Starting
    }

    /** Экран с превью готов: открыть камеру и, если запись ещё нужна, начать её. */
    suspend fun open(owner: LifecycleOwner) {
        val cameraProvider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
        this.owner = owner
        preview.setSurfaceProvider(previewView.surfaceProvider)
        bind(cameraProvider)
        if (phase == Phase.Starting && recording == null) beginRecording()
    }

    fun flip() {
        front = !front
        provider?.let { runCatching { bind(it) }.onFailure { e -> Log.w(TAG, "flip failed", e) } }
    }

    fun lock() {
        if (phase != Phase.Idle) locked = true
    }

    /** Остановить: [send] — отправить записанное, иначе удалить. */
    fun finish(send: Boolean) {
        sendOnFinish = send
        val current = recording
        if (current != null) current.stop() else close()
    }

    /** Экран с превью ушёл: камеру отпускаем, идущую запись отменяем. */
    fun release() {
        if (recording != null) finish(send = false)
        provider?.unbindAll()
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
        cameraProvider.bindToLifecycle(lifecycleOwner, selector, group)
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
        provider?.unbindAll()
    }

    private companion object {
        const val TAG = "VideoNoteCamera"
        const val DIR = "video_notes"
        const val MAX_DURATION_MS = 60_000L
        const val BIT_RATE = 1_000_000
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
