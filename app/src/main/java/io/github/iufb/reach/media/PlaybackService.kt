package io.github.iufb.reach.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.MainActivity
import javax.inject.Inject

/**
 * Фоновое воспроизведение (PRODUCT.md §4.4): MediaSession поверх единственного [AudioPlayer].
 * Media3 сам держит сервис на переднем плане, пока играет, и рисует уведомление с управлением —
 * оно же на экране блокировки и для кнопок гарнитуры.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var audioPlayer: AudioPlayer

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, audioPlayer.player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Смахнули приложение из недавних: если ничего не играет — сервис больше не нужен. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Плеер — синглтон приложения, его не освобождаем; только сессию
        session?.release()
        session = null
        super.onDestroy()
    }
}
