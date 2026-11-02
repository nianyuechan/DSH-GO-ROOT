package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File

/**
 * Decides whether a running engine left behind by an earlier app process can be adopted
 * instead of killed and restarted.
 *
 * Adoption is deliberately paranoid: a wrong yes leaves the app talking to something it
 * did not start. All four facts must hold, and a partial observation counts as a no —
 * "could not check" is not "checked and fine".
 *
 *   1. a handoff record exists and matches the runtime root we are about to use
 *   2. the recorded pid is alive and owned by this app's uid
 *   3. its command line is the guest node, and its cwd is inside the rootfs
 *   4. the engine answers HTTP on the recorded port with the recorded token
 *
 * Pure logic plus injected readers, so the whole matrix is testable without a device.
 */
object AdoptedEngine {

    /** Reasons a candidate was rejected; each names a distinct fact, never a message. */
    const val NO_RECORD = "noHandoffRecord"
    const val ROOT_CHANGED = "handoffRootChanged"
    const val GONE = "handoffProcessGone"
    const val NOT_OURS = "handoffProcessNotOurs"
    const val NOT_THE_ENGINE = "handoffProcessNotEngine"
    const val OUTSIDE_ROOTFS = "handoffProcessOutsideRootfs"
    const val UNREACHABLE = "handoffEngineUnreachable"
    const val BAD_TOKEN = "handoffTokenRejected"

    /** What the caller could observe about one pid. */
    sealed interface Liveness {
        data class Alive(val uid: Int, val command: String, val cwd: String?) : Liveness
        /** /proc/<pid> is absent: the process is gone, not unobservable. */
        object Absent : Liveness
        /** The pid directory exists but could not be read; never treated as alive. */
        object Unknown : Liveness
    }

    /** Answers "does this port speak our engine with this token?" */
    fun interface HealthCheck {
        /** True only on an authenticated 200; any error or timeout is false. */
        fun healthy(port: Int, token: String): Boolean
    }

    /**
     * The real check, against the loopback server the engine printed. The token is
     * exchanged for the `dsh-auth-*` cookie (see the runtime manifest's `auth` block):
     * a bare `GET /` answers 401 without it, so reaching the port is not enough.
     *
     * Short timeouts on purpose — adoption runs while the user waits for the engine,
     * and a real engine on loopback answers in single-digit milliseconds.
     */
    fun httpHealthCheck(timeoutMillis: Int = 1500): HealthCheck = HealthCheck { port, token ->
        try {
            // 1. Exchange the one-time token for the session cookie; no redirect follow is
            //    needed, the Set-Cookie header on the 303 is the whole answer.
            val exchange = java.net.URL("http://127.0.0.1:$port/?token=$token").openConnection()
                as java.net.HttpURLConnection
            exchange.connectTimeout = timeoutMillis
            exchange.readTimeout = timeoutMillis
            exchange.instanceFollowRedirects = false
            val cookie = try {
                if (exchange.responseCode !in 300..399) null
                else exchange.headerFields["Set-Cookie"]
                    ?.firstOrNull { it.startsWith("dsh-auth-") }
                    ?.substringBefore(';')
            } finally {
                runCatching { exchange.disconnect() }
            } ?: return@HealthCheck false

            // 2. The cookie must actually be accepted: this is the part a stale token fails.
            val probe = java.net.URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
            probe.connectTimeout = timeoutMillis
            probe.readTimeout = timeoutMillis
            probe.instanceFollowRedirects = false
            probe.setRequestProperty("Cookie", cookie)
            try {
                probe.responseCode == 200
            } finally {
                runCatching { probe.disconnect() }
            }
        } catch (_: Exception) {
            false
        }
    }

    data class Result(val adopted: Boolean, val reason: String? = null, val record: EngineHandoff.Record? = null)

    /** The engine's editable guest entry, matched against /proc/<pid>/cmdline. */
    private const val ENGINE_COMMAND = "/opt/node"

    /**
     * [rootfs] is the canonical path of the root this launch will use. [record] is the
     * previous handoff, still on disk. Nothing here mutates state: the caller adopts or clears.
     */
    fun check(
        record: EngineHandoff.Record?,
        rootfs: File,
        myUid: Int,
        liveness: (Int) -> Liveness,
        health: HealthCheck,
    ): Result {
        if (record == null) return Result(false, NO_RECORD)
        val root = rootfs.canonicalPath
        if (record.rootfs != root) return Result(false, ROOT_CHANGED)
        when (val seen = liveness(record.pid)) {
            Liveness.Absent -> return Result(false, GONE)
            Liveness.Unknown -> return Result(false, GONE)
            is Liveness.Alive -> {
                if (seen.uid != myUid) return Result(false, NOT_OURS)
                if (!seen.command.contains(ENGINE_COMMAND)) return Result(false, NOT_THE_ENGINE)
                val cwd = seen.cwd ?: return Result(false, OUTSIDE_ROOTFS)
                if (!RuntimeProcessOwnership.within(cwd, root)) return Result(false, OUTSIDE_ROOTFS)
            }
        }
        return if (health.healthy(record.port, record.token)) Result(true, null, record)
        else Result(false, UNREACHABLE)
    }
}
