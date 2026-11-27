package io.github.iufb.reach.core.voip;

import android.media.projection.MediaProjection;

import org.webrtc.EglBase;

/**
 * Класс, который платформенный код tgcalls ищет по имени (у Telegram — org.telegram.messenger.voip.VideoCapturerDevice;
 * имя переписывает native/voip/CMakeLists.txt). Даже аудиозвонок создаёт через него фабрики видеокодеков WebRTC,
 * поэтому общий EGL-контекст нужен уже сейчас. Захват камеры и экрана — #411.
 */
public final class VideoCapturerDevice {
    private static EglBase eglBase;

    /** Зовёт AndroidContext (натив) — пока только для видео. */
    public VideoCapturerDevice(boolean screencast) {}

    /** Зовёт натив при уничтожении AndroidContext. */
    public void onDestroy() {}

    /** Общий EGL-контекст для аппаратных кодеков. Зовёт AndroidInterface (натив). */
    public static synchronized EglBase.Context getSharedEGLContext() {
        if (eglBase == null) {
            eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN);
        }
        return eglBase != null ? eglBase.getEglBaseContext() : null;
    }

    /** Захват звука экрана (WebRtcAudioRecord) — не поддерживаем. */
    public static MediaProjection getMediaProjection() {
        return null;
    }
}
