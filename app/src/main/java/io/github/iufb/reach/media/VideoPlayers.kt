package io.github.iufb.reach.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Плееры Media3 для файлов TDLib (PRODUCT.md §4.4). Одна точка, где собирается ExoPlayer:
 * источник данных — [TdFileDataSource] (стриминг по мере загрузки), аудиофокус — на плеере.
 * Тот же источник пригодится GIF, голосовым и музыке.
 */
@Singleton
class VideoPlayers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Новый плеер, уже подготовленный к воспроизведению [file]. Освобождает вызывающий (`release`).
     * [muted] — без звука и без аудиофокуса (GIF не должны глушить музыку), [loop] — по кругу.
     */
    @OptIn(UnstableApi::class)
    fun create(file: TdFile, playWhenReady: Boolean = true, muted: Boolean = false, loop: Boolean = false): ExoPlayer {
        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(TdFileDataSource.Factory(td)))
        if (!muted) {
            builder
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
        }
        return builder.build().apply {
            if (muted) {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
            if (loop) repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(TdFileDataSource.uriFor(file.id)))
            this.playWhenReady = playWhenReady
            prepare()
        }
    }

    /** Сколько GIF в ленте играют одновременно: декодеры не бесконечны. */
    val inlineSlots = PlaybackSlots(MAX_INLINE_PLAYERS)

    /** Останавливает фоновую докачку, когда видео больше не смотрят; скачанная часть остаётся в кэше. */
    fun stopDownload(file: TdFile) {
        scope.launch { runCatching { td.send(TdApi.CancelDownloadFile(file.id, false)) } }
    }
}

/**
 * Ограничение одновременно живых плееров. Владелец просит место ([tryAcquire]) из эффекта
 * и отдаёт ([release]) при уходе с экрана; [holds] читается в Compose и сам перерисовывает ожидающих.
 */
class PlaybackSlots(private val max: Int) {
    private val holders = mutableStateListOf<Any>()

    fun holds(owner: Any): Boolean = owner in holders

    /** Изменение состояния — только из эффектов, не во время композиции. */
    fun tryAcquire(owner: Any): Boolean {
        if (owner in holders) return true
        if (holders.size >= max) return false
        holders.add(owner)
        return true
    }

    fun release(owner: Any) {
        holders.remove(owner)
    }

    /** Число занятых мест — чтобы ожидающие повторили попытку, когда кто-то ушёл. */
    val taken: Int get() = holders.size
}

private const val MAX_INLINE_PLAYERS = 3

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MediaEntryPoint {
    fun videoPlayers(): VideoPlayers
    fun audioPlayer(): AudioPlayer
    fun autoDownload(): AutoDownload
}

/** [VideoPlayers] из Compose — для экранов без своей ViewModel (полноэкранный просмотр). */
@Composable
internal fun rememberVideoPlayers(): VideoPlayers {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).videoPlayers() }
}

/** [AudioPlayer] из Compose — для мини-плеера, который висит на нескольких экранах. */
@Composable
internal fun rememberAudioPlayer(): AudioPlayer {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).audioPlayer() }
}
