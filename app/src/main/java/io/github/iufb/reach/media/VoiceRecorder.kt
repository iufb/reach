package io.github.iufb.reach.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.chats.encodeWaveform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Записанное голосовое, готовое к отправке. */
class VoiceRecording(val path: String, val durationSec: Int, val waveform: ByteArray)

/** Что сейчас с записью голосового. */
sealed interface RecorderState {
    data object Idle : RecorderState

    /** Идёт запись. [locked] — зафиксирована (палец можно отпустить), [level] — громкость 0..1. */
    data class Recording(val startedAt: Long, val level: Float, val locked: Boolean) : RecorderState

    /** Запись остановлена в зафиксированном режиме: можно прослушать, удалить или отправить. */
    data class Recorded(val recording: VoiceRecording, val previewPlaying: Boolean) : RecorderState
}

/**
 * Запись голосовых (PRODUCT.md §4.4). Кодирует сам Android: `MediaRecorder` с `OutputFormat.OGG`
 * и `AudioEncoder.OPUS` (API 29+) даёт ровно то, что ждёт Telegram, — OGG/Opus, без libopus и муксера.
 * Waveform собирается из `maxAmplitude` во время записи.
 *
 * Не синглтон: живёт в ViewModel чата. Вызывается из главного потока, а сам `MediaRecorder` —
 * на отдельном потоке через [SerialRecorder] (#312): его `prepare/start/stop` блокируют ~80 мс.
 * Поэтому [state] меняется сразу по нажатию, а рекордер догоняет: «запись» показывается до того,
 * как микрофон реально открылся, а остановленная запись дописывается в файл чуть позже.
 */
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val recorder = SerialRecorder(MediaRecorderEngine(context), WORKER, ContextCompat.getMainExecutor(context))

    /** Номер записи: ответы рекордера относятся только к своей записи. */
    private var session = 0
    private var file: File? = null
    private var sampler: Job? = null
    private val levels = ArrayList<Float>()
    private var preview: MediaPlayer? = null

    /** Остановка записи для предпрослушивания: файл готов, когда она завершится; `false` — запись не удалась. */
    private var recordedReady: CompletableDeferred<Boolean>? = null

    /**
     * Начинает запись: состояние — сразу, микрофон открывается на фоне; не открылся — назад в
     * [RecorderState.Idle]. Разрешение `RECORD_AUDIO` проверяет вызывающий.
     */
    fun start(scope: CoroutineScope) {
        discard()
        val dir = File(context.cacheDir, VOICE_DIR).apply { mkdirs() }
        val output = File(dir, "${UUID.randomUUID()}.ogg")
        val id = ++session
        file = output
        levels.clear()
        _state.value = RecorderState.Recording(SystemClock.elapsedRealtime(), 0f, locked = false)
        recorder.start(output.path) { started -> if (!started) onStartFailed(id) }
        sampler = scope.launch {
            while (isActive) {
                val level = (amplitude() / MAX_AMPLITUDE).coerceIn(0f, 1f)
                val current = _state.value as? RecorderState.Recording ?: break
                if (session != id) break
                levels += level
                _state.value = current.copy(level = level)
                delay(SAMPLE_MS)
            }
        }
    }

    /** `maxAmplitude` — тоже вызов в медиасервер, поэтому с фонового потока. */
    private suspend fun amplitude(): Int = suspendCancellableCoroutine { cont -> recorder.amplitude { cont.resume(it) } }

    private fun onStartFailed(id: Int) {
        // Запись уже остановлена или начата новая — её остановка сама удалит свой файл
        if (session != id || _state.value !is RecorderState.Recording) return
        Log.w(TAG, "microphone unavailable")
        stopSampler()
        file?.delete()
        file = null
        _state.value = RecorderState.Idle
    }

    /** Зафиксировать: запись идёт без удержания кнопки. */
    fun lock() {
        val current = _state.value as? RecorderState.Recording ?: return
        _state.value = current.copy(locked = true)
    }

    /**
     * Остановить запись и вернуть результат; `null` — слишком коротко или запись не удалась (файл удалён).
     * Состояние сбрасывается сразу, результат — когда рекордер допишет файл.
     */
    suspend fun finish(): VoiceRecording? {
        val (output, recording) = takeRecording() ?: return null
        _state.value = RecorderState.Idle
        val ok = try {
            stopRecorder()
        } catch (e: CancellationException) {
            output.delete()
            throw e
        }
        if (!ok || recording == null) {
            output.delete()
            return null
        }
        return recording
    }

    /** Остановить зафиксированную запись и перейти к предпрослушиванию — сразу, файл дописывается на фоне. */
    fun stopForPreview() {
        val (output, recording) = takeRecording() ?: return
        if (recording == null) {
            _state.value = RecorderState.Idle
            recorder.stop { output.delete() }
            return
        }
        val id = session
        val ready = CompletableDeferred<Boolean>().also { recordedReady = it }
        file = output
        _state.value = RecorderState.Recorded(recording, previewPlaying = false)
        recorder.stop { ok ->
            ready.complete(ok)
            if (ok || session != id || recordedReady !== ready) return@stop
            Log.w(TAG, "recording failed")
            discard()
        }
    }

    /** Идущая запись: файл и результат (`null` — короче [MIN_DURATION_MS]); сэмплер остановлен, [file] снят. */
    private fun takeRecording(): Pair<File, VoiceRecording?>? {
        val current = _state.value as? RecorderState.Recording ?: return null
        val output = file ?: return null
        file = null
        stopSampler()
        val durationMs = SystemClock.elapsedRealtime() - current.startedAt
        if (durationMs < MIN_DURATION_MS) return output to null
        return output to VoiceRecording(output.path, ((durationMs + 500) / 1000).toInt().coerceAtLeast(1), waveform())
    }

    /** Записанное для отправки после предпрослушивания; состояние сбрасывается, файл остаётся для TDLib. */
    suspend fun takeRecorded(): VoiceRecording? {
        val recorded = _state.value as? RecorderState.Recorded ?: return null
        val ready = recordedReady
        releasePreview()
        file = null
        recordedReady = null
        _state.value = RecorderState.Idle
        // Отправили сразу после «стопа»: файл ещё дописывается
        if (ready != null && !ready.await()) {
            File(recorded.recording.path).delete()
            return null
        }
        return recorded.recording
    }

    suspend fun togglePreview() {
        // Плеер — только по дописанному файлу
        if (recordedReady?.await() == false) return
        val recorded = _state.value as? RecorderState.Recorded ?: return
        val player = preview ?: runCatching {
            MediaPlayer().apply {
                setDataSource(recorded.recording.path)
                setOnCompletionListener { setPreviewPlaying(false) }
                prepare()
            }
        }.getOrNull()?.also { preview = it } ?: return
        if (player.isPlaying) player.pause() else player.start()
        setPreviewPlaying(player.isPlaying)
    }

    /** Позиция предпрослушивания 0..1; `0` — плеер ещё не создан. */
    fun previewProgress(): Float {
        val player = preview ?: return 0f
        val duration = runCatching { player.duration }.getOrDefault(0)
        if (duration <= 0) return 0f
        return (runCatching { player.currentPosition }.getOrDefault(0).toFloat() / duration).coerceIn(0f, 1f)
    }

    /** Отменить запись или удалить записанное. Рекордер останавливается на фоне, файл удаляется после. */
    fun discard() {
        val output = file
        file = null
        if (_state.value is RecorderState.Recording) {
            stopSampler()
            recorder.stop { output?.delete() }
        } else {
            output?.delete()
        }
        releasePreview()
        recordedReady = null
        _state.value = RecorderState.Idle
    }

    private fun setPreviewPlaying(playing: Boolean) {
        val recorded = _state.value as? RecorderState.Recorded ?: return
        _state.value = recorded.copy(previewPlaying = playing)
    }

    private fun releasePreview() {
        preview?.release()
        preview = null
    }

    private fun stopSampler() {
        sampler?.cancel()
        sampler = null
    }

    private suspend fun stopRecorder(): Boolean {
        val result = CompletableDeferred<Boolean>()
        recorder.stop { result.complete(it) }
        return result.await()
    }

    /** [WAVEFORM_SIZE] уровней 0..31 по максимумам отрезков, нормировано по самому громкому месту. */
    private fun waveform(): ByteArray {
        if (levels.isEmpty()) return encodeWaveform(List(WAVEFORM_SIZE) { 0 })
        val peak = levels.max().takeIf { it > 0f } ?: 1f
        val values = List(WAVEFORM_SIZE) { i ->
            val from = i * levels.size / WAVEFORM_SIZE
            val to = ((i + 1) * levels.size / WAVEFORM_SIZE).coerceIn(from + 1, levels.size)
            val level = levels.subList(from, to).max() / peak
            // Корень — громкость воспринимается нелинейно, тихая речь не должна выглядеть пустой
            (sqrt(level) * 31).toInt()
        }
        return encodeWaveform(values)
    }

    /** `MediaRecorder` на OGG/Opus; все вызовы — на [WORKER]. */
    private class MediaRecorderEngine(private val context: Context) : SerialRecorder.Engine<MediaRecorder> {
        override fun start(path: String): MediaRecorder? {
            val started = MediaRecorder(context)
            return try {
                started.setAudioSource(MediaRecorder.AudioSource.MIC)
                started.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                started.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                started.setAudioChannels(1)
                started.setAudioSamplingRate(SAMPLE_RATE)
                started.setAudioEncodingBitRate(BIT_RATE)
                started.setOutputFile(path)
                started.prepare()
                started.start()
                started
            } catch (e: Exception) {
                Log.e(TAG, "recorder start failed", e)
                started.release()
                null
            }
        }

        override fun amplitude(handle: MediaRecorder): Int = runCatching { handle.maxAmplitude }.getOrDefault(0)

        override fun stop(handle: MediaRecorder): Boolean {
            // stop() бросает, если не успели записать ни одного кадра
            val ok = runCatching { handle.stop() }.isSuccess
            handle.release()
            return ok
        }
    }

    private companion object {
        /**
         * Один поток на все экраны чатов: закрытый чат отпускает микрофон раньше, чем следующий его
         * возьмёт. Живёт с процессом, без задач простаивает.
         */
        val WORKER: Executor = Executors.newSingleThreadExecutor { Thread(it, "VoiceRecorder").apply { isDaemon = true } }

        const val TAG = "VoiceRecorder"
        const val VOICE_DIR = "voice"
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 32_000
        const val SAMPLE_MS = 50L
        const val MAX_AMPLITUDE = 32767f
        const val MIN_DURATION_MS = 700L
        const val WAVEFORM_SIZE = 100
    }
}
