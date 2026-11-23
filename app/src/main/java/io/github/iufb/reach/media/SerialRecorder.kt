package io.github.iufb.reach.media

import java.util.concurrent.Executor

/**
 * Порядок вызовов к рекордеру вне главного потока (#312). `prepare/start/stop/release` у `MediaRecorder`
 * блокируют на десятки миллисекунд, поэтому идут на [worker] — однопоточном, строго по очереди
 * вызовов: «стоп до окончания старта», «отмена во время старта», «повторное нажатие во время стопа»
 * просто выполняются друг за другом и не пересекаются.
 *
 * Каждая поставленная задача выполняется, даже если вызывающий уже ушёл (отменён scope), — иначе
 * микрофон остался бы занят. Результаты приходят в [deliver] (главный поток).
 * Рекордер в каждый момент один: [handle] трогает только [worker].
 */
class SerialRecorder<H : Any>(
    private val engine: Engine<H>,
    private val worker: Executor,
    private val deliver: Executor,
) {
    /** Сами вызовы к рекордеру; выполняются только на [worker]. */
    interface Engine<H : Any> {
        /** Подготовить и начать запись в [path]; `null` — не удалось (рекордер уже отпущен). */
        fun start(path: String): H?

        /** Громкость с прошлого вызова. */
        fun amplitude(handle: H): Int

        /** Остановить и отпустить; `false` — запись не удалась (например, ни одного кадра). */
        fun stop(handle: H): Boolean
    }

    private var handle: H? = null

    /** Начать запись; [onResult] — удалось ли. Не остановленная прошлая запись сначала останавливается. */
    fun start(path: String, onResult: (Boolean) -> Unit = {}) = worker.execute {
        handle?.let(::stopQuietly)
        handle = runCatching { engine.start(path) }.getOrNull()
        val started = handle != null
        deliver.execute { onResult(started) }
    }

    /** Остановить текущую запись; [onResult] — `false`, если её не было или она не удалась. */
    fun stop(onResult: (Boolean) -> Unit = {}) = worker.execute {
        val current = handle
        handle = null
        val ok = current != null && stopQuietly(current)
        deliver.execute { onResult(ok) }
    }

    /** Громкость текущей записи; 0 — записи нет. */
    fun amplitude(onResult: (Int) -> Unit) = worker.execute {
        val level = handle?.let { runCatching { engine.amplitude(it) }.getOrDefault(0) } ?: 0
        deliver.execute { onResult(level) }
    }

    private fun stopQuietly(current: H): Boolean = runCatching { engine.stop(current) }.getOrDefault(false)
}
