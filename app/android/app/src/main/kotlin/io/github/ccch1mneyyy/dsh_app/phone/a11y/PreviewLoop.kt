package io.github.ccch1mneyyy.dsh_app.phone.a11y

/** Main-thread scheduling plus a single worker, abstracted for plain JVM tests. */
internal interface PreviewTasks {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
    fun execute(task: Runnable)
}

/** Captures only while the card is expanded; at most one capture is ever in flight. */
internal class PreviewLoop(
    private val source: () -> Shot,
    private val onResult: (Shot) -> Unit,
    private val tasks: PreviewTasks,
    private val intervalMs: Long = 1_100,
) {
    @Volatile var running = false
        private set
    private var inFlight = false
    private var repeatable = true
    private var generation = 0
    private val tick = Runnable { requestCapture() }

    fun start() = tasks.post {
        if (running) return@post
        running = true
        repeatable = true
        requestCapture()
    }

    fun refresh() = tasks.post {
        if (!running || !repeatable) return@post
        tasks.cancel(tick)
        requestCapture()
    }

    /** The visible application changed: drop any old in-flight frame, then capture the new source. */
    fun invalidate() = tasks.post {
        if (!running) return@post
        generation++
        repeatable = true
        tasks.cancel(tick)
        if (!inFlight) requestCapture()
    }

    fun stop() = tasks.post {
        if (!running && !inFlight) return@post
        running = false
        generation++
        tasks.cancel(tick)
    }

    private fun requestCapture() {
        if (!running || inFlight || !repeatable) return
        inFlight = true
        val capturedGeneration = generation
        tasks.execute {
            val result = runCatching(source).getOrElse {
                Shot(
                    null, null, null,
                    secure = false, error = it.javaClass.simpleName, repeatable = false,
                )
            }
            tasks.post {
                inFlight = false
                if (!running || capturedGeneration != generation) {
                    if (running) requestCapture()
                    return@post
                }
                onResult(result)
                repeatable = result.repeatable
                if (repeatable) tasks.postDelayed(tick, intervalMs)
            }
        }
    }
}
