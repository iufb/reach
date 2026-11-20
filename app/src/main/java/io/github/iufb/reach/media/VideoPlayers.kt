package io.github.iufb.reach.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    /** Новый плеер, уже подготовленный к воспроизведению [file]. Освобождает вызывающий (`release`). */
    @OptIn(UnstableApi::class)
    fun create(file: TdFile, playWhenReady: Boolean = true): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(TdFileDataSource.Factory(td)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(TdFileDataSource.uriFor(file.id)))
                this.playWhenReady = playWhenReady
                prepare()
            }

    /** Останавливает фоновую докачку, когда видео больше не смотрят; скачанная часть остаётся в кэше. */
    fun stopDownload(file: TdFile) {
        scope.launch { runCatching { td.send(TdApi.CancelDownloadFile(file.id, false)) } }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MediaEntryPoint {
    fun videoPlayers(): VideoPlayers
}

/** [VideoPlayers] из Compose — для экранов без своей ViewModel (полноэкранный просмотр). */
@Composable
internal fun rememberVideoPlayers(): VideoPlayers {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).videoPlayers() }
}
