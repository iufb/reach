package io.github.iufb.reach.ui

import android.content.Context
import android.util.Log
import io.github.iufb.reach.BuildConfig
import java.io.File

/**
 * Переключатели для замеров производительности на устройстве (#304), только в debug-сборках:
 * в release [get] всегда `null`, и код ведёт себя как без файла.
 *
 * Читаются один раз при старте процесса из `files/perf-flags` (строки `ключ=значение`):
 * ```
 * adb shell run-as io.github.iufb.reach.testdc sh -c 'echo glass=solid > files/perf-flags'
 * adb shell am force-stop io.github.iufb.reach.testdc
 * ```
 * Ключи: `glass` = `solid` (подложка без размытия везде) | `blur` (живое размытие везде, в т. ч. в чате);
 * `tgs` = `static` (первый кадр) | `hw` (аппаратный рендер Lottie); `tgsfps` = кадров в секунду.
 */
object PerfFlags {
    @Volatile private var values: Map<String, String> = emptyMap()

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        val file = File(context.filesDir, "perf-flags")
        values = runCatching {
            if (!file.exists()) return
            file.readLines().mapNotNull { line ->
                val (key, value) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                key.trim() to value.trim()
            }.toMap()
        }.getOrDefault(emptyMap())
        if (values.isNotEmpty()) Log.i("Reach", "perf-flags: $values")
    }

    operator fun get(key: String): String? = if (BuildConfig.DEBUG) values[key] else null
}
