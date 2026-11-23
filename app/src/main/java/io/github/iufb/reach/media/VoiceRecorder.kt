package io.github.iufb.reach.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.chats.encodeWaveform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
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
 * Не синглтон: живёт в ViewModel чата, [scope] — её scope. Вызывается из главного потока.
 */
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var sampler: Job? = null
    private val levels = ArrayList<Float>()
    private var preview: MediaPlayer? = null

    /** Начинает запись; `false` — микрофон недоступен. Разрешение `RECORD_AUDIO` проверяет вызывающий. */
    fun start(scope: CoroutineScope): Boolean {
        discard()
        val dir = File(context.cacheDir, VOICE_DIR).apply { mkdirs() }
        val output = File(dir, "${UUID.randomUUID()}.ogg")
        val started = MediaRecorder(context)
        try {
            started.setAudioSource(MediaRecorder.AudioSource.MIC)
            started.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            started.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            started.setAudioChannels(1)
            started.setAudioSamplingRate(SAMPLE_RATE)
            started.setAudioEncodingBitRate(BIT_RATE)
            started.setOutputFile(output.path)
            started.prepare()
            started.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed", e)
            started.release()
            output.delete()
            return false
        }
        recorder = started
        file = output
        levels.clear()
        val startedAt = SystemClock.elapsedRealtime()
        _state.value = RecorderState.Recording(startedAt, 0f, locked = false)
        sampler = scope.launch {
            while (isActive) {
                val level = (started.maxAmplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
                levels += level
                _state.value = (_state.value as? RecorderState.Recording)?.copy(level = level) ?: break
                delay(SAMPLE_MS)
            }
        }
        return true
    }

    /** Зафиксировать: запись идёт без удержания кнопки. */
    fun lock() {
        val current = _state.value as? RecorderState.Recording ?: return
        _state.value = current.copy(locked = true)
    }

    /** Остановить запись и вернуть результат; `null` — слишком коротко или запись не удалась (файл удалён). */
    fun finish(): VoiceRecording? {
        val current = _state.value as? RecorderState.Recording ?: return null
        val output = file ?: return null
        val durationMs = SystemClock.elapsedRealtime() - current.startedAt
        val ok = stopRecorder()
        _state.value = RecorderState.Idle
        if (!ok || durationMs < MIN_DURATION_MS) {
            output.delete()
            file = null
            return null
        }
        return VoiceRecording(output.path, ((durationMs + 500) / 1000).toInt().coerceAtLeast(1), waveform())
    }

    /** Остановить зафиксированную запись и перейти к предпрослушиванию. */
    fun stopForPreview() {
        val recording = finish() ?: return
        file = File(recording.path)
        _state.value = RecorderState.Recorded(recording, previewPlaying = false)
    }

    /** Записанное для отправки после предпрослушивания; состояние сбрасывается, файл остаётся для TDLib. */
    fun takeRecorded(): VoiceRecording? {
        val recorded = _state.value as? RecorderState.Recorded ?: return null
        releasePreview()
        file = null
        _state.value = RecorderState.Idle
        return recorded.recording
    }

    fun togglePreview() {
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

    /** Отменить запись или удалить записанное. */
    fun discard() {
        if (_state.value is RecorderState.Recording) stopRecorder()
        releasePreview()
        file?.delete()
        file = null
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

    private fun stopRecorder(): Boolean {
        sampler?.cancel()
        sampler = null
        val current = recorder ?: return false
        recorder = null
        // stop() бросает, если не успели записать ни одного кадра
        val ok = runCatching { current.stop() }.isSuccess
        current.release()
        return ok
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

    private companion object {
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
