package io.github.ccch1mneyyy.dsh_app.phone.background

import android.os.Bundle
import io.github.muntashirakon.adb.AdbStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One live link to the privileged engine. Three implementations, one contract: the driver
 * sends a request Bundle and gets a response Bundle, never caring whether the engine is
 * reached over a Shizuku binder, an adb stream or a su child's stdio. Calls are serialised
 * by the caller (the bridge's single worker thread); [execute] blocks.
 */
interface PrivilegedChannel {
    fun execute(request: Bundle): Bundle
    fun alive(): Boolean
    fun close()
}

/** Shizuku transport: a straight Binder call into [PrivilegedDisplayService]. */
class ShizukuChannel(private val remote: IPrivilegedDisplay) : PrivilegedChannel {
    override fun execute(request: Bundle): Bundle = remote.execute(request)
    override fun alive(): Boolean = runCatching { remote.asBinder().pingBinder() }.getOrDefault(false)
    override fun close() { /* UserService unbind is the starter's job */ }
}

/**
 * Self-ADB transport: framed Bundles over the adb shell stream that launched
 * [PrivilegedDisplayMain]. A response frame always follows its request frame on this
 * single stream, so one lock keeps request/response paired.
 */
class AdbChannel(private val stream: AdbStream) : PrivilegedChannel {
    private val lock = Any()
    private val out = stream.openOutputStream()
    private val input = stream.openInputStream()
    private var synced = false

    override fun execute(request: Bundle): Bundle = synchronized(lock) {
        if (!synced) {
            if (!Frames.syncToMagic(input)) return Bundle().apply {
                putBoolean(Proto.OK, false); putString(Proto.ERROR, "deviceUnavailable")
            }
            synced = true
        }
        Frames.write(out, Bundles.marshal(request))
        val frame = Frames.read(input) ?: return Bundle().apply {
            putBoolean(Proto.OK, false); putString(Proto.ERROR, "deviceUnavailable")
        }
        Bundles.unmarshal(frame)
    }

    override fun alive(): Boolean = runCatching { !stream.isClosed }.getOrDefault(false)

    override fun close() {
        runCatching { execute(Bundle().apply { putString(Proto.OP, Proto.OP_DESTROY) }) }
        runCatching { stream.close() }
    }
}

/**
 * Root transport: the same wire as [AdbChannel] over the merged stdio of the
 * `su -c app_process` child that [RootStarter] launched. stderr is folded into
 * the stream at spawn time, so [Frames.syncToMagic] skips the READY banner and
 * any su chatter exactly as it does on adb's multiplexed shell.
 */
class RootChannel(private val process: Process) : PrivilegedChannel {
    private val lock = Any()
    private val out = process.outputStream
    private val input = process.inputStream
    private var synced = false

    override fun execute(request: Bundle): Bundle = synchronized(lock) {
        if (!synced) {
            if (!Frames.syncToMagic(input)) return Bundle().apply {
                putBoolean(Proto.OK, false); putString(Proto.ERROR, "deviceUnavailable")
            }
            synced = true
        }
        Frames.write(out, Bundles.marshal(request))
        val frame = Frames.read(input) ?: return Bundle().apply {
            putBoolean(Proto.OK, false); putString(Proto.ERROR, "deviceUnavailable")
        }
        Bundles.unmarshal(frame)
    }

    override fun alive(): Boolean = runCatching { process.isAlive }.getOrDefault(false)

    /** [execute] with a deadline; the su client may sit waiting for the manager's decision. */
    fun probe(timeout: Long, unit: TimeUnit): Bundle? {
        val latch = CountDownLatch(1)
        var result: Bundle? = null
        thread(isDaemon = true, name = "dsh-root-probe") {
            result = runCatching { execute(Bundle().apply { putString(Proto.OP, Proto.OP_PROBE) }) }.getOrNull()
            latch.countDown()
        }
        if (!latch.await(timeout, unit)) {
            // Destroying the process closes the streams and ends the blocked worker.
            process.destroy()
            return null
        }
        return result
    }

    override fun close() {
        runCatching { if (process.isAlive) execute(Bundle().apply { putString(Proto.OP, Proto.OP_DESTROY) }) }
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }
}
