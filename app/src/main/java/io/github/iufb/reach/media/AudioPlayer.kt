package io.github.iufb.reach.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Что играет: сообщение с аудио (голосовое или музыка). */
data class AudioTrack(
    val chatId: Long,
    val messageId: Long,
    val file: TdFile,
    /** Для голосовых при старте шлём `openMessageContent` — «прослушано». */
    val isVoice: Boolean,
    val title: String = "",
    val performer: String = "",
    /** Длительность из сообщения, секунды — пока плеер не узнал точную. */
    val duration: Int = 0,
) {
    val key: String get() = "$chatId:$messageId"
}

/** Состояние единственного аудиоплеера приложения. */
data class AudioState(
    val track: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Есть ли что-то в очереди после текущего. */
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    fun isCurrent(chatId: Long, messageId: Long) = track?.chatId == chatId && track.messageId == messageId

    /** Доля проигранного (0..1). */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Единственный аудиоплеер (PRODUCT.md §4.4): одновременно играет что-то одно — голосовое или трек.
 * Очередь — сообщения чата после выбранного; по окончании играет следующее.
 * Живёт всё время процесса, поэтому не зависит от экрана чата. Вызывается из главного потока.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private var queue: List<AudioTrack> = emptyList()
    private var ticker: Job? = null

    /** Создаётся при первом воспроизведении, чтобы не держать декодеры зря. */
    @get:OptIn(UnstableApi::class)
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(TdFileDataSource.Factory(td)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val track = queue.getOrNull(player.currentMediaItemIndex)
            track?.let(::markListened)
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
            if (isPlaying) startTicker() else ticker?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Очередь доиграна — сбрасываем, чтобы пузырь вернулся в исходный вид
            if (playbackState == Player.STATE_ENDED) stop() else publish()
        }
    }

    /**
     * Тап по треку: если он уже текущий — пауза/продолжить, иначе играть его, а после — [following]
     * (следующие сообщения чата того же вида, от старых к новым).
     */
    fun toggle(track: AudioTrack, following: List<AudioTrack> = emptyList()) {
        if (_state.value.track?.key == track.key) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        play(listOf(track) + following.filter { it.key != track.key }, 0)
    }

    /** Играть [tracks] с позиции [index]. */
    fun play(tracks: List<AudioTrack>, index: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        player.setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(0, tracks.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    fun pause() {
        if (_state.value.track != null) player.pause()
    }

    fun resume() {
        if (_state.value.track != null) player.play()
    }

    /** Перемотка текущего трека; [fraction] 0..1. */
    fun seekTo(fraction: Float) {
        val duration = _state.value.durationMs.takeIf { it > 0 } ?: return
        player.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
        publish()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        // Как в плеерах: дальше начала трека — в начало, иначе — предыдущий
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun stop() {
        if (_state.value.track == null) return
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        ticker?.cancel()
        _state.value = AudioState()
    }

    private fun publish() {
        val track = queue.getOrNull(player.currentMediaItemIndex)
        if (track == null) {
            _state.value = AudioState()
            return
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track.duration * 1000L)
        _state.value = AudioState(
            track = track,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                publish()
                delay(TICK_MS)
            }
        }
    }

    private fun markListened(track: AudioTrack) {
        if (!track.isVoice) return
        scope.launch { runCatching { td.send(TdApi.OpenMessageContent(track.chatId, track.messageId)) } }
    }

    private fun AudioTrack.toMediaItem() = MediaItem.Builder()
        .setMediaId(key)
        .setUri(TdFileDataSource.uriFor(file.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { null })
                .setArtist(performer.ifBlank { null })
                .build(),
        )
        .build()

    private companion object {
        const val TICK_MS = 100L
        const val RESTART_THRESHOLD_MS = 3000L
    }
}
