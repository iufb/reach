package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/** Голосовое сообщение. [waveform] — уровни 0..31 (как их хранит Telegram), [transcript] — готовая расшифровка. */
data class VoiceInfo(
    /** Секунды. */
    val duration: Int,
    val waveform: List<Int>,
    val file: TdFile,
    val transcript: String?,
)

internal fun TdApi.VoiceNote.toInfo() = VoiceInfo(
    duration = duration,
    waveform = decodeWaveform(waveform),
    file = voice.toTdFile(),
    transcript = (speechRecognitionResult as? TdApi.SpeechRecognitionResultText)?.text?.takeIf { it.isNotBlank() },
)

/** Waveform Telegram: подряд упакованные 5-битные значения, младшие биты — первыми. */
fun decodeWaveform(bytes: ByteArray): List<Int> {
    val count = bytes.size * 8 / 5
    return List(count) { i ->
        val bit = i * 5
        val index = bit / 8
        val low = bytes[index].toInt() and 0xFF
        val high = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
        ((low or (high shl 8)) shr (bit % 8)) and 0x1F
    }
}

/** Обратное к [decodeWaveform]: уровни 0..31 → байты для `InputMessageVoiceNote`. */
fun encodeWaveform(levels: List<Int>): ByteArray {
    val bytes = ByteArray((levels.size * 5 + 7) / 8)
    levels.forEachIndexed { i, level ->
        val value = level.coerceIn(0, 0x1F)
        val bit = i * 5
        val index = bit / 8
        val shifted = value shl (bit % 8)
        bytes[index] = (bytes[index].toInt() or (shifted and 0xFF)).toByte()
        if (index + 1 < bytes.size) bytes[index + 1] = (bytes[index + 1].toInt() or (shifted shr 8)).toByte()
    }
    return bytes
}
