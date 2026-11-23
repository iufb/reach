package io.github.iufb.reach.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class SerialRecorderTest {

    /** Фоновый поток вручную: задачи копятся, пока тест не выполнит их. */
    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class FakeEngine(private val failStart: Boolean = false, private val stopOk: Boolean = true) :
        SerialRecorder.Engine<Int> {
        val calls = mutableListOf<String>()
        val open = mutableSetOf<Int>()
        private var next = 1

        override fun start(path: String): Int? {
            calls += "start $path"
            if (failStart) return null
            return next++.also { open += it }
        }

        override fun amplitude(handle: Int): Int = handle * 100

        override fun stop(handle: Int): Boolean {
            calls += "stop $handle"
            open -= handle
            return stopOk
        }
    }

    private val worker = QueueExecutor()
    private val direct = Executor { it.run() }

    @Test fun nothingRunsOnTheCallersThread() {
        val engine = FakeEngine()
        val recorder = SerialRecorder(engine, worker, direct)
        recorder.start("a")
        recorder.stop()
        assertTrue(engine.calls.isEmpty())
        worker.drain()
        assertEquals(listOf("start a", "stop 1"), engine.calls)
    }

    @Test fun stopBeforeStartFinishedWaitsForIt() {
        val engine = FakeEngine()
        val recorder = SerialRecorder(engine, worker, direct)
        val results = mutableListOf<String>()
        recorder.start("a") { results += "started $it" }
        recorder.stop { results += "stopped $it" }
        worker.drain()
        assertEquals(listOf("started true", "stopped true"), results)
        assertTrue(engine.open.isEmpty())
    }

    @Test fun cancelAfterFailedStartDoesNotTouchTheRecorder() {
        val engine = FakeEngine(failStart = true)
        val recorder = SerialRecorder(engine, worker, direct)
        val results = mutableListOf<Boolean>()
        recorder.start("a") { results += it }
        recorder.stop { results += it }
        worker.drain()
        assertEquals(listOf(false, false), results)
        assertEquals(listOf("start a"), engine.calls)
    }

    @Test fun pressAgainDuringStopGetsAFreshRecorder() {
        val engine = FakeEngine()
        val recorder = SerialRecorder(engine, worker, direct)
        recorder.start("a")
        worker.drain()
        recorder.stop()
        recorder.start("b")
        recorder.stop()
        worker.drain()
        assertEquals(listOf("start a", "stop 1", "start b", "stop 2"), engine.calls)
        assertTrue(engine.open.isEmpty())
    }

    @Test fun startWithoutStopReleasesThePreviousMicrophone() {
        val engine = FakeEngine()
        val recorder = SerialRecorder(engine, worker, direct)
        recorder.start("a")
        recorder.start("b")
        worker.drain()
        assertEquals(listOf("start a", "stop 1", "start b"), engine.calls)
        assertEquals(setOf(2), engine.open)
    }

    @Test fun failedStopIsReported() {
        val engine = FakeEngine(stopOk = false)
        val recorder = SerialRecorder(engine, worker, direct)
        var result: Boolean? = null
        recorder.start("a")
        recorder.stop { result = it }
        worker.drain()
        assertEquals(false, result)
    }

    @Test fun engineExceptionsDoNotKillTheWorker() {
        val engine = object : SerialRecorder.Engine<Int> {
            override fun start(path: String): Int = error("boom")
            override fun amplitude(handle: Int): Int = 0
            override fun stop(handle: Int): Boolean = true
        }
        val recorder = SerialRecorder(engine, worker, direct)
        var started: Boolean? = null
        recorder.start("a") { started = it }
        worker.drain()
        assertEquals(false, started)
    }

    @Test fun amplitudeIsZeroWithoutRecording() {
        val engine = FakeEngine()
        val recorder = SerialRecorder(engine, worker, direct)
        val levels = mutableListOf<Int>()
        recorder.amplitude { levels += it }
        recorder.start("a")
        recorder.amplitude { levels += it }
        recorder.stop()
        recorder.amplitude { levels += it }
        worker.drain()
        assertEquals(listOf(0, 100, 0), levels)
    }
}
