package io.github.ccch1mneyyy.dsh_app.phone.background

import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.root.RootAccess
import io.github.ccch1mneyyy.dsh_app.root.RootState
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** granted / connected plus a readiness verdict for the status Map. */
data class RootStatus(
    val granted: Boolean,
    val connected: Boolean,
) {
    fun map(): Map<String, Any?> = mapOf("granted" to granted, "connected" to connected)
}

/**
 * The root twin of [ShizukuStarter] and [AdbStarter]: `su -c app_process` launches
 * [PrivilegedDisplayMain] directly, and the framed Bundle protocol runs over the
 * child's merged stdio exactly as it does over an adb shell stream. No pairing, no
 * manager APK, no wireless debugging — the root manager's authorization is the
 * whole setup. The Android 14 floor still applies: it gates the trusted virtual
 * display, not the transport.
 */
class RootStarter(private val context: Context, private val onChanged: () -> Unit) {

    @Volatile private var channel: RootChannel? = null

    fun status(): RootStatus = RootStatus(
        granted = runCatching { RootAccess.probe() == RootState.granted }.getOrDefault(false),
        connected = channel?.alive() == true,
    )

    /** A one-line reason when not ready, else null. */
    fun reason(): String? = when (RootAccess.probe()) {
        RootState.granted -> null
        RootState.unauthorized -> AppStrings.get(context, R.string.background_root_unauthorized)
        RootState.unavailable -> AppStrings.get(context, R.string.background_root_unavailable)
    }

    /**
     * Re-asks the root manager (pops its authorization dialog again when denied
     * before). Blocking like [ShizukuStarter.requestPermission]; true when granted.
     */
    fun requestPermission(): Boolean {
        val granted = runCatching { RootAccess.probe(force = true) == RootState.granted }.getOrDefault(false)
        onChanged()
        return granted
    }

    /** Launches the privileged app_process through su (blocking; ~12 s worst case). */
    fun connect(): PrivilegedChannel? {
        channel?.takeIf { it.alive() }?.let { return it }
        if (RootAccess.probe() != RootState.granted) {
            android.util.Log.w("RootStarter", "connect: probe not granted")
            return null
        }
        channel?.let { runCatching { it.close() } }
        channel = null
        val process = try {
            ProcessBuilder("su", "-c", appProcessCommand())
                // The READY banner and su chatter ride the one stream, like adb's shell.
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            android.util.Log.w("RootStarter", "connect: spawn failed: ${e.javaClass.simpleName}: ${e.message}")
            onChanged()
            return null
        }
        android.util.Log.w("RootStarter", "connect: spawned su client alive=${process.isAlive}")
        thread(isDaemon = true, name = "dsh-root-engine-watch") {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            android.util.Log.w("RootStarter", "ENGINE EXITED: code=$code")
        }
        val value = RootChannel(process)
        val probe = value.probe(12, TimeUnit.SECONDS)
        android.util.Log.w("RootStarter", "connect: probe ok=${probe?.getBoolean(Proto.OK)} err=${probe?.getString(Proto.ERROR)} rev=${probe?.getString("revision")}")
        if (probe?.getBoolean(Proto.OK) == true && probe.getString("revision") == Proto.REVISION) {
            channel = value
            onChanged()
            return value
        }
        android.util.Log.w("RootStarter", "connect: probe rejected, closing")
        runCatching { value.close() }
        onChanged()
        return null
    }

    fun disconnect() {
        channel?.let { runCatching { it.close() } }
        channel = null
        onChanged()
    }

    private fun appProcessCommand(): String {
        val info = context.applicationInfo
        val classpath = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let { addAll(it.toList()) }
        }.joinToString(":")
        val main = PrivilegedDisplayMain::class.java.name
        return "CLASSPATH=$classpath app_process /system/bin $main"
    }
}
