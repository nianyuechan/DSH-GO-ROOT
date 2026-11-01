package io.github.ccch1mneyyy.dsh_app.root

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** How the root path sees this device. */
enum class RootState { unavailable, unauthorized, granted }

/**
 * su availability probe, shared by the root phone-control path and later root
 * engine work. A plain Magisk-compatible su CLI is all this app ever talks to,
 * so KernelSU, Magisk and APatch look identical here.
 *
 * Deliberately a raw [ProcessBuilder] spawn instead of libsu: KernelSU 3.3's su
 * answers libsu's handshake but swallows every later command's output (code=0,
 * empty streams), while a plain `su -c` stream reads fine. [probe] may block
 * while a root manager waits for the person's decision; callers already sit on
 * worker threads, and the read watchdog caps the wait.
 */
object RootAccess {
    private const val TIMEOUT_SECONDS = 12L
    private val state = java.util.concurrent.atomic.AtomicReference<RootState?>(null)

    /**
     * Probes once per app session unless [force]. `force` also re-asks the
     * manager: a fresh su request pops its authorization dialog again.
     */
    fun probe(force: Boolean = false): RootState {
        if (!force) state.get()?.let { return it }
        synchronized(this) {
            if (!force) state.get()?.let { return it }
            return runProbe().also { state.set(it) }
        }
    }

    /** Cheap cached check for callers that only need a yes/no. */
    fun granted(): Boolean = probe() == RootState.granted

    fun invalidate() = state.set(null)

    private fun runProbe(): RootState {
        val output = suOutput("id")
        if (output.any { it.contains("uid=0") }) return RootState.granted
        // su exists in PATH but no root: a manager refused or the request
        // timed out. Nothing at all: this device has no su.
        val found = output.ifEmpty {
            runCatching { plainOutput("command -v su") }.getOrDefault(emptyList())
        }
        return if (found.any { it.isNotBlank() }) RootState.unauthorized else RootState.unavailable
    }

    /** `su -c <command>` with a watchdog; returns the trimmed stdout lines. */
    private fun suOutput(command: String): List<String> {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: Exception) {
            android.util.Log.w("RootAccess", "su spawn failed: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        return readWithTimeout(process)
    }

    /** The non-root fallback lookup, run as this app's own uid. */
    private fun plainOutput(command: String): List<String> {
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (_: Exception) {
            return emptyList()
        }
        return readWithTimeout(process)
    }

    private fun readWithTimeout(process: Process): List<String> {
        val latch = CountDownLatch(1)
        var lines: List<String> = emptyList()
        val reader = thread(isDaemon = true, name = "dsh-root-probe") {
            try {
                lines = process.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: Exception) {
            }
            latch.countDown()
        }
        val done = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!done) {
            process.destroy()
            reader.join(2000)
            android.util.Log.w("RootAccess", "probe timed out; su client destroyed")
        } else {
            process.waitFor()
        }
        return lines
    }
}
