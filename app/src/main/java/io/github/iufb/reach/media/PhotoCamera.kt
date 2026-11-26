package io.github.iufb.reach.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Камера для снимка из меню скрепки (#421): CameraX `Preview` + `ImageCapture`, задняя камера по
 * умолчанию. Превью показывается то в ячейке сетки, то на весь экран — [attach] переключает его на
 * нужный [PreviewView], камера при этом не переоткрывается. Все вызовы — из главного потока.
 */
@Stable
class PhotoCamera(private val context: Context) {

    var front by mutableStateOf(false)
        private set

    var flash by mutableStateOf(false)
        private set

    /** У открытой камеры есть вспышка. */
    var flashAvailable by mutableStateOf(false)
        private set

    /** Снимок делается: кнопка не принимает повторные нажатия. */
    var capturing by mutableStateOf(false)
        private set

    private val preview = Preview.Builder().build()
    private val capture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var cellView: PreviewView? = null
    private var fullView: PreviewView? = null

    /** Открыть камеру на [owner]; куда показывать превью — [attach]. */
    suspend fun open(owner: LifecycleOwner) {
        this.owner = owner
        val cameraProvider = provider ?: awaitProvider().also { provider = it }
        bind(cameraProvider)
    }

    /**
     * Показывать превью в [view]: [full] — камера на весь экран, иначе ячейка сетки. Пока открыт весь
     * экран, превью идёт только туда, даже если ячейка сетки пересоздастся позже (ленивая сетка
     * собирает свои элементы уже после остального экрана).
     */
    fun attach(view: PreviewView, full: Boolean) {
        if (full) fullView = view else cellView = view
        route()
    }

    /** Весь экран закрыт: превью снова в ячейке. */
    fun leaveFull() {
        fullView = null
        route()
    }

    /**
     * Последний кадр прошлого превью, пока новое не пошло: при переносе превью в другой `PreviewView`
     * CameraX перезапускает поток (~0,3–0,5 с), и без стоп-кадра ячейка и раскрывающаяся камера
     * были бы чёрными. Экраны рисуют его поверх превью.
     */
    var frozen by mutableStateOf<Bitmap?>(null)
        private set

    private var shownView: PreviewView? = null

    /** Запомнить текущий кадр заранее — до того, как превью уйдёт с экрана (тап по ячейке). */
    fun freeze() {
        frozen = shownView?.bitmap ?: frozen
    }
    private val streaming = Observer<PreviewView.StreamState> { state ->
        if (state == PreviewView.StreamState.STREAMING) frozen = null
    }

    private fun route() {
        val view = fullView ?: cellView ?: return
        if (view === shownView) return
        shownView?.let { old ->
            frozen = old.bitmap ?: frozen
            old.previewStreamState.removeObserver(streaming)
        }
        shownView = view
        preview.setSurfaceProvider(view.surfaceProvider)
        capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
        view.previewStreamState.observeForever(streaming)
    }

    fun flip() {
        front = !front
        provider?.let { runCatching { bind(it) }.onFailure { e -> Log.w(TAG, "flip failed", e) } }
    }

    fun toggleFlash() {
        flash = !flash
        capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    /** Снять фото в JPEG во временный файл приложения (в системную галерею не попадает). */
    suspend fun takePhoto(): File {
        capturing = true
        try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file)
                .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front })
                .build()
            suspendCancellableCoroutine { cont ->
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(Unit)
                        override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
                    },
                )
            }
            return file
        } finally {
            capturing = false
        }
    }

    /** Отпустить камеру; свои use case-ы, не чужие (камера кружков отвязывается сама). */
    fun close() {
        provider?.unbind(preview, capture)
        camera = null
        owner = null
        cellView = null
        fullView = null
        shownView?.previewStreamState?.removeObserver(streaming)
        shownView = null
        frozen = null
        flash = false
        capture.flashMode = ImageCapture.FLASH_MODE_OFF
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        val lifecycleOwner = owner ?: return
        cameraProvider.unbind(preview, capture)
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { runCatching { future.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) } },
            ContextCompat.getMainExecutor(context),
        )
    }

    private companion object {
        const val TAG = "PhotoCamera"
        const val DIR = "upload/camera"
    }
}
