package io.github.iufb.reach.core.voip

/**
 * tgcalls (LGPL-3.0) + WebRTC из Telegram Android в `libtgcallsjni.so` (ADR-0015).
 * Пока только то, что нужно для `callProtocol` TDLib; сам звонок — #409.
 * Библиотека грузится при первом обращении: на старт приложения она не влияет.
 */
object TgCalls {
    /** Слой протокола `min_layer` — как у Telegram Android (`VoIPService.CALL_MIN_LAYER`). */
    const val MIN_LAYER = 65

    private val loaded: Result<Unit> by lazy { runCatching { System.loadLibrary("tgcallsjni") } }

    /** Ошибка загрузки `.so` или `null`, если загрузилась. */
    val loadError: Throwable? get() = loaded.exceptionOrNull()

    /** Версии реализаций tgcalls для `callProtocol.library_versions`, напр. `2.7.7`, `11.0.0`. */
    fun versions(): List<String> {
        loaded.getOrThrow()
        return nativeVersions().toList()
    }

    /** `callProtocol.max_layer`. */
    fun maxLayer(): Int {
        loaded.getOrThrow()
        return nativeMaxLayer()
    }

    @JvmStatic private external fun nativeVersions(): Array<String>

    @JvmStatic private external fun nativeMaxLayer(): Int
}
