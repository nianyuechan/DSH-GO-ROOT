package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root-chroot launch planner, the root twin of [DirectLaunch]: the engine runs in the
 * real filesystem at full speed — no PRoot translation, no loader/library hacks, the
 * rootfs's own `/usr/local/bin/dsh` entry and pidfile semantics untouched.
 *
 * Root only mounts and drops: proc/sys/dev and the composed binds go up before the
 * chroot, then the engine is exec'd as a descendant of the mounting shell — whatever
 * mount namespace su provides, the engine inherits it (verified on KernelSU 3.3.0:
 * the su client execs into the command shell). The engine runs as the app's own uid,
 * so every ownership, stop and cleanup path in [DshProcess] works unchanged, and the
 * rootfs (extracted by this app) already belongs to that uid. Guest env pairs stay
 * guest-path-based exactly as PRoot received them; DSH_GO_RUNTIME_ROOT resolves inside
 * the chroot through the rootfs's self-bind. Pure JVM and testable, like [DirectLaunch].
 */
object RootLaunch {

    /** Fallback reason: a root launch already failed with this runtime. */
    const val FAILED = "rootFailed"
    /** The rootfs is missing a piece the chroot path needs (see [unavailable]). */
    const val UNAVAILABLE = "rootUnavailable"

    data class Plan(val script: String, val cwd: File)

    data class Selection(val root: Boolean, val fallbackReason: String? = null)

    /**
     * The saved launch method, mirroring [DirectLaunch.Preference]. [failedRuntime]
     * pins the runtime version a root launch last failed with, so the fallback to
     * proot survives restarts until the runtime changes.
     */
    data class Preference(val saved: Boolean?, val available: Boolean, val failedRuntime: String?, val runtime: String?) {
        /** Off until the user turns it on, and only while su is actually available. */
        val requested: Boolean get() = (saved ?: false) && available

        /** Compatible until the runtime changes or the user turns Root on again. */
        val failed: Boolean get() = failedRuntime != null && failedRuntime == runtime

        /** What the switch shows: whether the next normal start tries the chroot. */
        val enabled: Boolean get() = requested && !failed
    }

    /**
     * Chroot needs the rootfs's own setpriv/env and the dsh entry — none of the three is
     * on the host. These come from the runtime build, so a runtime created before the
     * root path existed cannot start. Pure file presence: no process is spawned.
     */
    fun unavailable(rootfs: File): String? = when {
        !File(rootfs, "usr/bin/setpriv").canExecute() -> "rootSetprivMissing"
        !File(rootfs, "usr/bin/env").canExecute() -> "rootEnvMissing"
        !File(rootfs, "bin/sh").isFile -> "rootShellMissing"
        !File(rootfs, "usr/local/bin/dsh").isFile -> "rootEntryMissing"
        else -> null
    }

    /**
     * Same contract as [DirectLaunch.select]: safe mode always wins, then the saved
     * preference, then the last failure, then the rootfs check.
     */
    fun select(rootfs: File, requested: Boolean, safe: Boolean, failed: Boolean = false, granted: Boolean = false): Selection {
        if (!requested || safe || !granted) return Selection(false)
        if (failed) return Selection(false, FAILED)
        val reason = unavailable(rootfs)
        return Selection(reason == null, reason)
    }

    /**
     * The app's supplementary groups, as `setpriv --groups` needs them. Android grants
     * network access through a group (AID_INET 3003) rather than a permission, so a
     * chrooted engine that drops this list has no sockets at all.
     *
     * `android.system.Os` has no getgroups; /proc/self/status reports the same list.
     */
    fun supplementaryGroups(statusFile: File = File("/proc/self/status")): IntArray {
        val line = runCatching {
            statusFile.useLines { lines -> lines.firstOrNull { it.startsWith("Groups:") } }
        }.getOrNull() ?: return IntArray(0)
        return line.removePrefix("Groups:").trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }.toIntArray()
    }

    /**
     * Whether `su` works here, cached for this app session. This is the engine's own
     * question — "can I mount and drop?" — and is deliberately separate from the phone
     * control channel's [RootStarter] state: that one is torn down with 不打扰模式 and
     * must not make an engine switch flicker when a task ends.
     *
     * A manager shows its authorization dialog on the first call, so this may block;
     * callers are already on a worker thread and the read watchdog caps the wait.
     */
    fun granted(force: Boolean = false): Boolean {
        if (!force) cachedGranted?.let { return it }
        synchronized(this) {
            if (!force) cachedGranted?.let { return it }
            return probeSu().also { cachedGranted = it }
        }
    }

    fun invalidate() {
        cachedGranted = null
    }

    @Volatile private var cachedGranted: Boolean? = null

    private fun probeSu(): Boolean {
        val process = try {
            ProcessBuilder("su", "-c", "id").start()
        } catch (_: Exception) {
            return false
        }
        val latch = CountDownLatch(1)
        var lines: List<String> = emptyList()
        val reader = thread(isDaemon = true, name = "dsh-root-probe") {
            lines = try {
                process.inputStream.bufferedReader().readLines().map { it.trim() }
            } catch (_: Exception) {
                emptyList()
            }
            latch.countDown()
        }
        val done = latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!done) {
            // A refused or unanswered request must not wedge the caller.
            process.destroy()
            reader.join(2000)
            return false
        }
        process.waitFor()
        return lines.any { it.contains("uid=0") }
    }

    /** The manager's own wait is unbounded; this is the app's ceiling on it. */
    private const val PROBE_TIMEOUT_SECONDS = 12L

    /**
     * Consumes the one fully expanded proot argv/env (same contract as
     * [DirectLaunch.prepare]: overlays, performance defaults and caller settings are
     * already composed) and returns the launcher script for [DshProcess] to write and
     * spawn through `su -c "exec sh '<script>'"`. [appGids] must carry the app's
     * supplementary groups — AID_INET (3003) lives there; without it the chrooted
     * engine has no network.
     */
    fun prepare(rootfs: File, argv: List<String>, appUid: Int, appGids: IntArray): Plan {
        val root = rootfs.canonicalFile
        val rootPath = root.path
        val envAt = argv.indexOf("/usr/bin/env")
        require(envAt > 0 && argv.getOrNull(envAt + 1) == "-i") { "root engine: missing guest environment" }
        val guest = linkedMapOf<String, String>()
        var at = envAt + 2
        while (at < argv.size && argv[at].contains('=')) {
            val pair = argv[at++].split('=', limit = 2)
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(pair[0])) { "root engine: invalid environment name" }
            guest[pair[0]] = pair[1]
        }
        require(guest.isNotEmpty()) { "root engine: empty guest environment" }
        require(argv.drop(at).take(2) == listOf("/bin/sh", "-c") && argv.size == at + 3) { "root engine: unsupported guest command" }
        DirectLaunch.webArguments(argv.last()) // reject an unknown future shell template early

        val mounts = DirectLaunch.mounts(argv.take(envAt), rootPath)
        // Bind targets live under the rootfs before the chroot happens.
        val preChroot = { guest: String -> rootPath + guest }
        val q = DirectLaunch::quote

        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("set -e")
            // Lazy cleanup of mounts a previous run left behind (idempotent): a shared
            // namespace keeps them visible after an unclean exit.
            append("umount -l")
            append(' '); append(q("$rootPath/proc"))
            append(' '); append(q("$rootPath/sys"))
            append(' '); append(q("$rootPath/dev"))
            append(' '); append(q("$rootPath$rootPath"))
            for (m in mounts) { append(' '); append(q(preChroot(m.guest))) }
            appendLine(" >/dev/null 2>&1 || true")
            appendLine("mount -t proc proc ${q("$rootPath/proc")}")
            appendLine("mount --rbind /dev ${q("$rootPath/dev")}")
            appendLine("mount --rbind /sys ${q("$rootPath/sys")}")
            for (m in mounts) {
                appendLine("mkdir -p ${q(preChroot(m.guest))}")
                appendLine("mount --bind ${q(m.host)} ${q(preChroot(m.guest))}")
            }
            // The runtime root mounted at its own host path keeps DSH_GO_RUNTIME_ROOT
            // and same-path expectations valid inside the chroot.
            appendLine("mkdir -p ${q("$rootPath$rootPath")}")
            appendLine("mount --bind ${q(rootPath)} ${q("$rootPath$rootPath")}")
            // The script runs under the HOST's /system/bin/sh, and the rootfs's own tools
            // are not reachable by their guest paths until we are inside it. So: one chroot
            // with the host's Toybox chroot, then the rootfs's setpriv drops the uid and
            // execs the engine. `chroot` needs root for the chroot(2) call alone; setpriv
            // is what actually takes the engine off root.
            val inner = "$SETPRIV --reuid=$appUid --regid=$appUid " +
                (if (appGids.isEmpty()) "--clear-groups " else "--groups=${appGids.joinToString(",")} ") +
                "$ENV -i " +
                guest.entries.joinToString(" ") { (key, value) -> q("$key=$value") } +
                " /bin/sh -c " + q(argv.last())
            appendLine("exec $HOST_CHROOT ${q(rootPath)} /bin/sh -c ${q(inner)}")
        }
        return Plan(script, File(root, "root"))
    }

    /**
     * The host's chroot (Toybox) is the only one the launching shell can resolve; every
     * path after it is the rootfs's own. These are Ubuntu 24.04's locations: util-linux
     * puts setpriv and env in /usr/bin.
     */
    private const val HOST_CHROOT = "/system/bin/chroot"
    private const val SETPRIV = "/usr/bin/setpriv"
    private const val ENV = "/usr/bin/env"
}
