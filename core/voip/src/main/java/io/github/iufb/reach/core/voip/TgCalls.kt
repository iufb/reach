package io.github.iufb.reach.core.voip

import android.content.Context
import io.github.iufb.reach.core.voip.compat.ApplicationLoader
import org.webrtc.ContextUtils

/**
 * tgcalls (LGPL-3.0) + WebRTC из Telegram Android в `libtgcallsjni.so` (ADR-0015).
 * Библиотека грузится при первом обращении: на старт приложения она не влияет.
 */
object TgCalls {
    /** Слой протокола `min_layer` — как у Telegram Android (`VoIPService.CALL_MIN_LAYER`). */
    const val MIN_LAYER = 65

    private val loaded: Result<Unit> by lazy { runCatching { System.loadLibrary("tgcallsjni") } }

    /** Ошибка загрузки `.so` или `null`, если загрузилась. */
    val loadError: Throwable? get() = loaded.exceptionOrNull()

    /** Контекст приложения для Java-части WebRTC (аудио, кодеки). Звать до первого звонка. */
    fun init(context: Context) {
        val app = context.applicationContext
        ApplicationLoader.applicationContext = app
        ContextUtils.initialize(app)
    }

    /** Версии реализаций tgcalls для `callProtocol.library_versions`, напр. `2.7.7`, `11.0.0`. */
    fun versions(): List<String> {
        requireLoaded()
        return nativeVersions().toList()
    }

    /** `callProtocol.max_layer`. */
    fun maxLayer(): Int {
        requireLoaded()
        return nativeMaxLayer()
    }

    internal fun requireLoaded() = loaded.getOrThrow()

    @JvmStatic private external fun nativeVersions(): Array<String>

    @JvmStatic private external fun nativeMaxLayer(): Int
}
