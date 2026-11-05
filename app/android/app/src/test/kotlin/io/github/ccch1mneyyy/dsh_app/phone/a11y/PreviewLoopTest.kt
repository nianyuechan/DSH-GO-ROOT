package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLoopTest {
    private class Tasks : PreviewTasks {
        val main = ArrayDeque<Runnable>()
        val worker = ArrayDeque<Runnable>()
        val delayed = mutableSetOf<Runnable>()

        override fun post(task: Runnable) { main.addLast(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { delayed += task }
        override fun cancel(task: Runnable) { delayed -= task }
        override fun execute(task: Runnable) { worker.addLast(task) }

        fun drainMain() { while (main.isNotEmpty()) main.removeFirst().run() }
        fun runCapture() { worker.removeFirst().run(); drainMain() }
    }

    private fun shot(repeatable: Boolean = true) = Shot(
        byteArrayOf(1), "image/jpeg",
        ImageGeometry(0, 0, 1f, 1f, 1, 1),
        secure = false, error = null, repeatable = repeatable,
    )

    @Test fun startCapturesOnceAndStopDropsAnInFlightResult() {
        val tasks = Tasks()
        var captures = 0
        val delivered = mutableListOf<Shot>()
        val loop = PreviewLoop({ captures++; shot() }, delivered::add, tasks)

        loop.start(); tasks.drainMain()
        loop.start(); tasks.drainMain()
        assertEquals(1, tasks.worker.size)
        loop.stop(); tasks.drainMain()
        tasks.runCapture()
        assertEquals(1, captures)
        assertTrue(delivered.isEmpty())
        assertTrue(tasks.delayed.isEmpty())
        assertFalse(loop.running)
    }

    @Test fun aCompletedCaptureSchedulesTheNextFrameWithoutOverlapping() {
        val tasks = Tasks()
        val delivered = mutableListOf<Shot>()
        val loop = PreviewLoop(::shot, delivered::add, tasks)

        loop.start(); tasks.drainMain()
        loop.refresh(); tasks.drainMain()
        assertEquals(1, tasks.worker.size)
        tasks.runCapture()
        assertEquals(1, delivered.size)
        assertEquals(1, tasks.delayed.size)
        tasks.delayed.first().run()
        assertEquals(1, tasks.worker.size)
    }

    @Test fun anApplicationSwitchDropsTheOldFrameAndCapturesTheNewOne() {
        val tasks = Tasks()
        var app = "old.app"
        val delivered = mutableListOf<Shot>()
        val loop = PreviewLoop({ shot().also { it.sourceKey = app } }, delivered::add, tasks)

        loop.start(); tasks.drainMain()
        loop.invalidate(); tasks.drainMain()
        app = "new.app"
        tasks.runCapture()
        assertTrue(delivered.isEmpty())
        assertEquals(1, tasks.worker.size)
        tasks.runCapture()

        assertEquals(listOf("new.app"), delivered.map { it.sourceKey })
    }

    @Test fun aStaticFrameIsNotPolledAndDoesNotRepublishTheModelSnapshot() {
        val tasks = Tasks()
        var modelSnapshot = "s7"
        var previewCaptures = 0
        fun observe(): String {
            modelSnapshot = "s8"
            return modelSnapshot
        }
        val loop = PreviewLoop({ previewCaptures++; shot(repeatable = false) }, {}, tasks)

        loop.start(); tasks.drainMain()
        tasks.runCapture()
        loop.refresh(); tasks.drainMain()

        assertEquals(1, previewCaptures)
        assertEquals("s7", modelSnapshot)
        assertTrue(tasks.delayed.isEmpty())
        assertTrue(tasks.worker.isEmpty())
    }
}
