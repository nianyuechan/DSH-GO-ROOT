package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs `dsh web` through proot or the optional direct glibc planner, using the
 * same composed manifest configuration. Reports ready after the token and socket.
 */
class DshProcess(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val rootfs: File,
    private val listener: Listener,
    private val adoptedLiveness: (Int) -> AdoptedEngine.Liveness = { AdoptedEngine.Liveness.Unknown },
    private val adoptedConnect: (Int) -> Boolean = { canConnect(it) },
    private val adoptedSleep: (Long) -> Unit = { Thread.sleep(it) },
    private val adoptedUid: () -> Int = { AndroidProcess.myUid() },
    private val adoptedStop: ((Int) -> Unit)? = null,
    private val processReaper: (() -> Boolean)? = null,
    private val mountCleanup: (List<String>) -> Boolean = { RootLaunch.cleanup(it) },
    private val persistPendingMounts: (EngineHandoff.PendingMounts) -> Unit = { pending ->
        EngineHandoff.savePending(context.filesDir, pending)
    },
    private val clearPendingMounts: () -> Unit = { EngineHandoff.clearPending(context.filesDir) },
    private val initialCleanupPids: Set<Int> = emptySet(),
    private val runtimeProcessReader: RuntimeProcessReader? = null,
    private val processKiller: (Int, Int) -> Unit = { pid, signal -> Os.kill(pid, signal) },
    private val appPid: () -> Int = { AndroidProcess.myPid() },
    private val processStarter: (ProcessBuilder) -> Process = { it.start() },
    private val rootTerminate: (List<Int>) -> Boolean = { RootLaunch.terminate(it) },
    private val rootResidual: (List<String>) -> List<String> = { RootLaunch.residual(it) },
    private val stopTimeoutMillis: Long = 15_000,
) {
    interface Listener {
        fun onLog(line: String, stream: String)
        fun onReady(source: DshProcess, baseUrl: String, token: String)
        fun onExit(source: DshProcess, code: Int, expected: Boolean)
        /** The start is busy turning PRoot's fake hard links into real files (true), or done (false). */
        fun onTidying(active: Boolean) {}
        /** A stop has outlived [DshProcess.stopTimeoutMillis]; the caller must stop waiting. */
        fun onStopTimeout(source: DshProcess) {}
    }

    @Volatile private var process: Process? = null
    /** Guarded by this: the blocking ProcessBuilder call itself runs outside the monitor. */
    private var spawning = false
    private var spawnFailed = false
    /**
     * Set instead of [process] by [adopt]: the engine was started by an earlier app process,
     * so there is no child of ours to hold. Liveness then comes from the pid file, and
     * [alive] is the single place that knows both shapes.
     */
    @Volatile private var adoptedPid: Int? = null
    /** The pid this instance launched or adopted; the root stop signal starts here. */
    @Volatile private var enginePid: Int? = null
    /** Whether the last root stop actually confirmed the recorded pid(s) are gone. */
    @Volatile private var rootStopVerified = true
    @Volatile private var stopTimeoutReported = false
    @Volatile private var stopCalls = 0
    @Volatile private var stopping = false
    @Volatile private var expectedExit = false
    @Volatile private var readyReported = false
    @Volatile private var waiter: Thread? = null
    @Volatile private var stopper: Thread? = null
    @Volatile private var processesReaped = false
    @Volatile private var mountTargetsComplete = true
    @Volatile var mountCleanupComplete: Boolean = true
        private set
    var mountTargets: List<String> = emptyList()
        private set
    // Share captured identities across launcher replacement within this app
    // process. Both capture and cleanup use the shared state's monitor.
    private val cleanupState by lazy {
        RuntimeProcessOwnership.cleanupState(rootfs.canonicalPath, adoptedUid())
    }
    private val cleanupKnown get() = cleanupState.known
    var port: Int = DEFAULT_PORT
        private set

    @Volatile var startupError: String? = null
        private set

    /** The launch command got the phone-control overlay (see [PhoneGuest.launchArg]). */
    var phoneMounted: Boolean = false
        private set

    /** Actual method for this process, fixed before it can be spawned. Safe mode stays proot. */
    var direct: Boolean = false
        private set

    /** Root mounted and dropped, then the engine runs in the real filesystem (see [RootLaunch]). */
    var root: Boolean = false
        private set

    val launchDiagnostics = LaunchDiagnostics()
    val executionMode: String? get() = launchDiagnostics.currentMode(alive)

    /** A spawned child, or an adopted identity retained until root liveness confirms its exit. */
    val alive: Boolean
        get() = process?.isAlive == true || adoptedPid != null

    /** This engine came from an earlier app process, so its launch command cannot change. */
    val adoptedEngine: Boolean get() = adoptedPid != null

    var directFallbackReason: String? = null
        private set

    /**
     * Take over an engine that is still running from an earlier app process instead of
     * spawning one. [AdoptedEngine.check] has already verified pid, uid, command, cwd and
     * an authenticated HTTP answer; this only wires the listener and reports ready.
     *
     * No child exists, so [stop] has to signal the pid file's process directly — the
     * adoption path keeps that working through [adoptedPid].
     */
    fun adopt(record: EngineHandoff.Record) {
        check(process == null && adoptedPid == null) { "already started" }
        adoptedPid = record.pid
        enginePid = record.pid
        port = record.port
        mountTargets = record.mountTargets
        mountTargetsComplete = record.mountTargetsComplete
        root = true
        launchDiagnostics.selected(LaunchDiagnostics.CHROOT, null)
        launchDiagnostics.spawned()
        readyReported = true
        listener.onLog("[dsh-app] adopted the running engine (pid ${record.pid}, port ${record.port})", "system")
        listener.onReady(this, "http://127.0.0.1:${record.port}", record.token)
        waiter = thread(name = "dsh-adopt-wait", isDaemon = true) {
            AdoptedEngineMonitor(
                record, rootfs, adoptedUid(), adoptedLiveness, adoptedConnect, adoptedSleep,
            ).run()
            val stopThread = synchronized(this) {
                adoptedPid = null
                stopper
            }
            stopThread?.join()
            if (stopThread == null) processesReaped = processReaper?.invoke() ?: killStrays()
            cleanupMounts()
            listener.onExit(this, 0, expectedExit)
        }
    }

    /**
     * [binds] are extra proot `-b host:guest` mounts, inserted before the guest command.
     * [safe] boots the rescue profile instead of `web` (see [SafeMode.profileArg]).
     * [rootEngine] tries the root-chroot launch first; [rootGranted] is the caller's su
     * verdict, so an ungranted device never reaches [RootLaunch].
     */
    fun start(guestEnv: Map<String, String>, binds: List<String> = emptyList(), safe: Boolean = false, phone: Boolean = false, directEngine: Boolean = false, directFailed: Boolean = false, rootEngine: Boolean = false, rootFailed: Boolean = false, rootGranted: Boolean = false, onLaunchMethod: ((Boolean) -> Unit)? = null) {
        check(process == null) { "already started" }
        // Root replaces the whole proot translation layer, so it outranks Direct: a chroot
        // launch needs neither the loader workarounds nor the hard-link tidying.
        val rootSelection = RootLaunch.select(rootfs, rootEngine, safe, rootFailed, rootGranted)
        var selection = if (rootSelection.root) DirectLaunch.Selection(direct = false) else DirectLaunch.select(rootfs, directEngine, safe, directFailed)
        // Only PRoot's fake hard links stand in Direct's way: tidy them once no runtime process is left.
        val tidy = !rootSelection.root && selection.fallbackReason == DirectLaunch.INCOMPATIBLE
        val cleanup = reapStrays(allowObservationGap = !selection.direct && !tidy)
        processesReaped = cleanup.complete
        if (tidy && cleanup.complete) selection = tidyLinks(binds)
        root = rootSelection.root
        directFallbackReason = rootSelection.fallbackReason ?: selection.fallbackReason ?: if (directEngine && safe) "safeRecovery" else null
        launchDiagnostics.selected(
            when {
                root -> LaunchDiagnostics.CHROOT
                selection.direct -> LaunchDiagnostics.DIRECT
                else -> LaunchDiagnostics.PROOT
            },
            directFallbackReason,
        )
        direct = selection.direct
        check(cleanup.complete || (!direct && !cleanup.ownedRemaining)) { "runtime cleanup incomplete; stop the engine and retry" }
        if (!cleanup.complete) listener.onLog("[dsh-app] starting ${if (root) "chroot" else "proot"} with incomplete process observation; no captured runtime identities remain", "system")
        val pidFile = File(rootfs, "tmp/dsh-web.pid")
        pidFile.delete() // also removes a stale/dangling symlink; never reuse a previous run's PID
        check(!java.nio.file.Files.exists(pidFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "cannot remove stale runtime pid file" }
        port = pickPort()
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        if (cleanup.canPruneTemp) {
            val reclaimed = ProotTempFiles.prune(tmp) { pid -> processReader.uid(pid) == null }
            if (reclaimed.removed > 0) listener.onLog("[dsh-app] reclaimed ${reclaimed.removed} stale proot temporary directories", "system")
            if (reclaimed.incomplete) listener.onLog("[dsh-app] proot temporary cleanup incomplete; retained entries for a later start", "system")
        }
        val vars = mapOf(
            "{NATIVE_LIB_DIR}" to context.applicationInfo.nativeLibraryDir,
            "{ROOTFS}" to rootfs.canonicalPath,
            "{TMP}" to tmp.absolutePath,
            "{FILES_DIR}" to context.filesDir.absolutePath,
            "{PORT}" to port.toString(),
        )
        fun expand(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

        val argv = ArrayList<String>()
        val sameBind = DirectLaunch.samePathBind(rootfs)
        // RootLaunch consumes the same composed proot-shaped argv DirectLaunch does, so the
        // `direct` flag only decides the same-path alias PRoot would have needed.
        val launchArgv = DirectLaunch.prootArguments(rootfs, manifest.launchArgv, direct)
        val allBinds = binds.filterNot { expand(it) == sameBind }.distinct()
        // PRoot's Node resolves through glibc, which fails A with a refused AAAA (#152): add Direct's resolver.
        // Chroot runs the rootfs's own Node, so it gets neither the translation nor this workaround.
        val effectiveGuestEnv = guestEnv + ("DSH_GO_RUNTIME_ROOT" to rootfs.canonicalPath) +
            listOfNotNull(if (direct || root) null else "NODE_OPTIONS" to DirectLaunch.prootNodeOptions(guestEnv["NODE_OPTIONS"]))
        var guestEnvPlaced = false
        check(manifest.launchArgv.isNotEmpty()) { "runtime manifest has no launch.argv" }
        var bindsPlaced = false
        var profileSelected = false
        // Message search: only with the overlay file in place (a missing --patch file stops DSH).
        var searchPending = !safe && SessionSearchGuest.file(rootfs).isFile
        // Subscription sign-in: the same rule for the auth bridge overlay.
        var authPending = !safe && AppAuthGuest.file(rootfs).isFile
        // On-device voice input: the recognizer's phone settings, same rule.
        var speechPending = !safe && SpeechGuest.file(rootfs).isFile
        for (arg in launchArgv) {
            if (!bindsPlaced && arg == "/usr/bin/env") {
                allBinds.forEach { argv += "-b"; argv += it }
                bindsPlaced = true
            }
            if (arg == GUEST_ENV) {
                effectiveGuestEnv.forEach { (k, v) -> argv += "$k=$v" }
                guestEnvPlaced = true
            } else {
                val selected = SafeMode.profileArg(arg, safe)
                if (selected != null) profileSelected = true
                // Phone control: add its --patch overlay to `dsh web` (never in safe mode).
                val withPhone = if (phone && !safe && !phoneMounted) PhoneGuest.launchArg(selected ?: arg) else null
                if (withPhone != null) phoneMounted = true
                val withSearch = if (searchPending) SessionSearchGuest.launchArg(withPhone ?: selected ?: arg) else null
                if (withSearch != null) searchPending = false
                val withAuth = if (authPending) AppAuthGuest.launchArg(withSearch ?: withPhone ?: selected ?: arg) else null
                if (withAuth != null) authPending = false
                val withSpeech = if (speechPending) SpeechGuest.launchArg(withAuth ?: withSearch ?: withPhone ?: selected ?: arg) else null
                if (withSpeech != null) speechPending = false
                argv += expand(withSpeech ?: withAuth ?: withSearch ?: withPhone ?: selected ?: arg)
            }
        }
        check(!safe || profileSelected) { "safe mode: this runtime's launch command has no `dsh web` to replace" }

        val builder = ProcessBuilder(argv).directory(context.filesDir)
        val env = builder.environment()
        env.clear()
        env["PATH"] = "/system/bin:/system/xbin"
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        manifest.launchEnv.forEach { (k, v) -> env[k] = expand(v) }
        if (!guestEnvPlaced) env.putAll(effectiveGuestEnv)
        if (direct) {
            val plan = DirectLaunch.prepare(rootfs, manifest.abi, argv, env.toMap())
            builder.command(plan.argv).directory(plan.cwd)
            env.clear()
            env.putAll(plan.environment)
        }
        if (root) {
            // The planner validates the composed argv and turns it into a launcher script.
            // su runs it; the script mounts, chroots and drops to this app's own uid, so
            // every ownership/stop/cleanup path below keeps working unchanged.
            val plan = RootLaunch.prepare(rootfs, argv, adoptedUid(), RootLaunch.supplementaryGroups())
            mountTargets = plan.mountTargets
            mountTargetsComplete = true
            mountCleanupComplete = false
            persistPendingMounts(EngineHandoff.PendingMounts(rootfs.canonicalPath, mountTargets))
            val script = File(context.filesDir, "root-launch.sh")
            script.writeText(plan.script)
            // The root manager's own chatter shares the stream; root commands never print it.
            builder.command(listOf("su", "-c", "exec sh ${DirectLaunch.quote(script.absolutePath)}"))
                .directory(context.filesDir)
            env.clear()
            env["PATH"] = "/system/bin:/system/xbin"
        }

        readyReported = false
        if (directFallbackReason == DirectLaunch.INCOMPATIBLE) listener.onLog("[dsh-app] direct fallback: proot hard-link backing files need compatible launch; user preference and data retained", "system")
        if (directFallbackReason == DirectLaunch.FAILED) listener.onLog("[dsh-app] direct fallback: direct launch failed before with this runtime; turn Direct on again to retry", "system")
        if (directFallbackReason == RootLaunch.FAILED) listener.onLog("[dsh-app] root fallback: the chroot launch failed before with this runtime; turn Root on again to retry", "system")
        if (directFallbackReason == RootLaunch.UNAVAILABLE) listener.onLog("[dsh-app] root fallback: this runtime is missing the chroot tools or the dsh entry", "system")
        listener.onLog("[dsh-app] launch method: ${when { root -> "root-chroot"; direct -> "direct-glibc"; else -> "proot" }}${if (directEngine && safe) " (safe recovery)" else ""}", "system")
        listener.onLog("[dsh-app] launching ${builder.command().joinToString(" ") { redact(it) }}", "system")
        // stop() came in while this was being prepared: don't launch at all.
        if (stopping) {
            if (root) stop() else listener.onExit(this, 0, true)
            return
        }
        // Chroot exposes the same real paths Direct does, so the agents text follows it too.
        onLaunchMethod?.invoke(direct || root)
        // Mark the spawn attempt before starting it: an app death at any later point must reap.
        if (root) persistPendingMounts(EngineHandoff.PendingMounts(rootfs.canonicalPath, mountTargets, spawned = true))
        val maySpawn = synchronized(this) {
            if (stopping) false else {
                spawnFailed = false
                spawning = true
                true
            }
        }
        if (!maySpawn) {
            if (root) stop() else listener.onExit(this, 0, true)
            return
        }
        val p = try {
            processStarter(builder)
        } catch (error: Throwable) {
            synchronized(this) {
                spawnFailed = true
                spawning = false
                (this as java.lang.Object).notifyAll()
            }
            throw error
        }
        synchronized(this) {
            process = p
        }
        try {
            if (root) {
                persistPendingMounts(EngineHandoff.PendingMounts(
                    rootfs.canonicalPath, mountTargets, spawned = true, pid = pidHint(),
                ))
            }
            launchDiagnostics.spawned()
            val out = pump(p.inputStream.bufferedReader(), "stdout")
            val err = pump(p.errorStream.bufferedReader(), "stderr")
            waiter = thread(name = "dsh-wait", isDaemon = true) {
                val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
                // The fatal message is usually the last output: let the pumps drain it before reporting.
                try {
                    out.join(2000)
                    err.join(2000)
                } catch (_: InterruptedException) {
                }
                // Report after a running stop() has finished: its killStrays() must not hit the next launch.
                val stopThread = synchronized(this) {
                    process = null
                    stopper
                }
                stopThread?.join()
                // An unexpected parent exit can leave tracees behind too. Keep the
                // controller's ownership until this UID sweep has finished.
                if (stopThread == null) processesReaped = processReaper?.invoke() ?: killStrays()
                cleanupMounts()
                listener.onExit(this, code, expectedExit)
            }
            if (stopping) stop()
        } finally {
            synchronized(this) {
                spawning = false
                (this as java.lang.Object).notifyAll()
            }
        }
    }

    /**
     * Direct was asked for and PRoot's fake hard links are the only obstacle: make
     * them real files ([L2sMigration]). Short of room or on an error this start stays
     * on PRoot with every name intact, and the next start carries on from there.
     */
    private fun tidyLinks(binds: List<String>): DirectLaunch.Selection {
        val started = System.nanoTime()
        // Every bind a PRoot launch may have used: this one's, plus the rootfs alias of earlier ones.
        val guestBinds = L2sMigration.bindTargets(
            DirectLaunch.prootArguments(rootfs, manifest.launchArgv, direct = false),
            binds + DirectLaunch.samePathBind(rootfs),
        )
        val result = try {
            L2sMigration(rootfs, ::syncFilesystems, guestBinds = guestBinds).run(onSlow = { listener.onTidying(true) })
        } catch (e: Exception) {
            listener.onLog("[dsh-app] direct fallback: tidying PRoot links stopped (${e.javaClass.simpleName}: ${e.message}); compatible launch this time, the next start continues", "system")
            return DirectLaunch.Selection(false, DirectLaunch.MIGRATION_FAILED)
        } finally {
            listener.onTidying(false)
        }
        if (result is L2sMigration.Result.NotEnoughSpace) {
            listener.onLog("[dsh-app] direct fallback: tidying PRoot links needs ${result.need shr 20} MB plus ${L2sMigration.MARGIN shr 20} MB spare, ${result.free shr 20} MB free; compatible launch this time", "system")
            return DirectLaunch.Selection(false, DirectLaunch.NOT_ENOUGH_SPACE)
        }
        listener.onLog("[dsh-app] direct: moved ${(result as L2sMigration.Result.Done).links} PRoot links to real files in ${(System.nanoTime() - started) / 1_000_000} ms", "system")
        val reason = DirectLaunch.compatibilityReason(rootfs)
        return DirectLaunch.Selection(reason == null, reason)
    }

    /** Waits for cleanup and exit reporting; zero waits until completion. */
    fun awaitExit(millis: Long = 0): Boolean {
        // The waiter joins the stopper before reporting exit. Startup can fail
        // after spawning but before allocating it, so await the stopper then.
        val ending = waiter?.takeIf { it.isAlive } ?: stopper
        ending?.join(millis)
        return ending?.isAlive != true && mountCleanupComplete
    }

    /** Preserve a startup failure through cleanup and the service's later STOP. */
    @Synchronized fun failStart(message: String) {
        if (stopping) {
            // Stop can arrive after start throws but before this catch path. If it found
            // neither a waiter nor a stopper, this path owns the one terminal callback.
            if (waiter == null && stopper == null) cleanupStoppedBeforeSpawn()
            return
        }
        startupError = message
        listener.onLog("[dsh-app] start failed: $message", "system")
        stop(expected = false)
        // A failure before ProcessBuilder.start (or before a child/stopper exists) has
        // no waiter to perform the root mount cleanup on its behalf.
        if (waiter == null && stopper == null) cleanupMounts()
    }

    /**
     * proot ignores SIGTERM, so stop DSH itself first (pid file written by the launch
     * command), then SIGQUIT proot, which kills every remaining tracee.
     */
    @Synchronized fun stop(expected: Boolean = true) {
        stopping = true
        if (expected) expectedExit = true
        if (stopper?.isAlive == true) return
        stopTimeoutReported = false
        stopCalls += 1
        // An adopted engine has no child of ours: signal its pid file like any other run,
        // and let the adoption waiter report the exit.
        val adopted = adoptedPid
        if (adopted == null && spawning) {
            stopper = thread(name = "dsh-stop", isDaemon = true) {
                val spawned = awaitSpawn()
                // After the spawn gate closes a missing waiter can never appear: the stopper
                // owns that failure's final callback; otherwise the waiter reports the exit.
                if (spawned != null) {
                    stopChildAndReportExit(spawned)
                } else if (spawnFailed) {
                    if (root && !mountCleanupComplete) cleanupStoppedBeforeSpawn()
                    else listener.onExit(this, 0, expectedExit)
                }
            }
            armStopWatchdog()
            return
        }
        if (process == null) {
            if (adopted == null) {
                if (root && !mountCleanupComplete) {
                    stopper = thread(name = "dsh-stop", isDaemon = true) {
                        cleanupStoppedBeforeSpawn()
                    }
                    armStopWatchdog()
                }
                return
            }
            stopper = thread(name = "dsh-stop", isDaemon = true) {
                try {
                    if (adoptedStop != null) {
                        adoptedStop.invoke(adopted)
                    } else if (root) {
                        // Under root the engine is its own session leader; kill the tree
                        // instead of relying on /proc, which SELinux can hide from us.
                        val pids = listOfNotNull(adopted, enginePid).distinct().filter { it > 0 }
                        val terminated = pids.isNotEmpty() &&
                            runCatching { rootTerminate(pids) }.getOrDefault(false)
                        rootStopVerified = pids.isEmpty() || terminated
                        listener.onLog("[dsh-app] root stop: terminated=$terminated pids=${pids.size}", "system")
                    } else {
                        val node = guestRecord()
                        if (node != null) signalOwned(node, OsConstants.SIGTERM)
                        // The pid file is the only handle; fall back to it if /proc no longer
                        // matches an owned record (the engine may have re-exec'd). Waiting on the
                        // port rather than /proc keeps this correct under SELinux.
                        if (node == null) runCatching { Os.kill(adopted, OsConstants.SIGTERM) }
                    }
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (System.nanoTime() < deadline && adoptedConnect(port)) Thread.sleep(100)
                    if (adoptedConnect(port)) runCatching { Os.kill(adopted, OsConstants.SIGKILL) }
                } finally {
                    processesReaped = processReaper?.invoke() ?: killStrays()
                }
            }
            armStopWatchdog()
            return
        }
        val p = process ?: return
        stopper = thread(name = "dsh-stop", isDaemon = true) { stopChildAndReportExit(p) }
        armStopWatchdog()
    }

    /** Reports a wedged stop once; the controller turns it into a retryable error. */
    private fun armStopWatchdog() {
        val s = stopper ?: return
        if (stopTimeoutReported) return
        stopTimeoutReported = true
        thread(name = "dsh-stop-watchdog", isDaemon = true) {
            s.join(stopTimeoutMillis)
            if (s.isAlive) listener.onStopTimeout(this)
        }
    }

    private fun awaitSpawn(): Process? = synchronized(this) {
        while (spawning) (this as java.lang.Object).wait()
        process
    }

    private fun cleanupStoppedBeforeSpawn() {
        if (!processesReaped) processesReaped = processReaper?.invoke() ?: killStrays()
        cleanupMounts()
        listener.onExit(this, 0, expectedExit)
    }

    /** Once allocated the waiter reports exit; otherwise this stopper is the sole owner. */
    private fun stopChildAndReportExit(p: Process) {
        stopChild(p)
        if (waiter == null) listener.onExit(this, 0, expectedExit)
    }

    private fun stopChild(p: Process) {
        try {
            val node = if (p.isAlive) guestRecord() else null
            // Root has no proot parent to SIGQUIT: the chrooted node is the whole tree,
            // and the su client is a plain child that closes on stdin EOF.
            val proot = if (direct || root) null else node?.let { child -> processSnapshot().records.firstOrNull { it.pid == child.parent && it.proot } }
            if (root) {
                // SELinux can hide the node from /proc ("observation unavailable"), so do
                // not depend on guestRecord() here. The engine's own pid is the session
                // leader (setsid), so a negative-pid kill reaches the node child too.
                val pids = listOfNotNull(enginePid, pidHint(), adoptedPid).distinct().filter { it > 0 }
                val terminated = pids.isNotEmpty() &&
                    runCatching { rootTerminate(pids) }.getOrDefault(false)
                rootStopVerified = pids.isEmpty() || terminated
                listener.onLog("[dsh-app] root stop: terminated=$terminated pids=${pids.size}", "system")
                if (p.isAlive) p.destroy()
            } else if (node != null) {
                signalOwned(node, OsConstants.SIGTERM)
            } else if (direct && p.isAlive) {
                p.destroy() // loader/preload may fail before writing its pid file
            }
            if (!waitExit(p, 5000) && proot != null) signalOwned(proot, OsConstants.SIGQUIT)
            waitExit(p, 2000)
        } finally {
            try {
                if (p.isAlive) p.destroyForcibly()
            } finally {
                // Reap tracees before waiting: an OEM Process.waitFor may not
                // return until they exit. The waiter still retains this owner.
                processesReaped = processReaper?.invoke() ?: killStrays()
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                val waiterAlive = synchronized(this) { waiter?.isAlive == true }
                if (!waiterAlive) {
                    cleanupMounts()
                    // stopChildAndReportExit owns the no-waiter callback; only a retry
                    // whose old waiter has already finished needs to report here.
                    if (waiter != null && stopCalls > 1 && mountCleanupComplete) {
                        listener.onExit(this, 0, expectedExit)
                    }
                }
            }
        }
    }

    private fun cleanupMounts() {
        val cleaned = runCatching {
            !root || (mountTargets.isNotEmpty() && mountCleanup(mountTargets))
        }.getOrDefault(false)
        // Never unmount by prefix: this only asks about the targets this instance recorded.
        val residual = if (root && cleaned) {
            runCatching { rootResidual(mountTargets) }.getOrDefault(mountTargets)
        } else {
            emptyList()
        }
        mountCleanupComplete = (!root || (processesReaped && rootStopVerified)) &&
            mountTargetsComplete && cleaned && residual.isEmpty()
        if (root && mountCleanupComplete) clearPendingMounts()
        if (!mountCleanupComplete) {
            if (residual.isNotEmpty()) {
                listener.onLog("[dsh-app] this instance's root mounts remain: ${residual.size}; other mounts are left untouched", "system")
            }
            listener.onLog("[dsh-app] root mount cleanup incomplete; handoff retained for a safe retry", "system")
        }
    }

    private fun waitExit(p: Process, millis: Long): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (p.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
        return !p.isAlive
    }

    /** Guest `dsh web` identity, retained through the final pre-signal PID-reuse check. */
    @Synchronized private fun guestRecord(): RuntimeProcessRecord? = synchronized(cleanupState) {
        captureGuestRecord()
    }

    private fun captureGuestRecord(): RuntimeProcessRecord? {
        return try {
            val pid = File(rootfs, "tmp/dsh-web.pid").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return null
            val records = processSnapshot().records
            val owned = RuntimeProcessOwnership.owned(records, setOf(rootfs.absolutePath, rootfs.canonicalPath), AndroidProcess.myPid())
            owned.forEach { cleanupKnown[it.pid] = it.started }
            val record = owned.firstOrNull { it.pid == pid } ?: return null
            if (record.argv.any { it.contains("/opt/node") || it.contains("dsh") }) record else null
        } catch (_: Exception) {
            null
        }
    }

    private fun signalOwned(record: RuntimeProcessRecord, value: Int) = runCatching {
        val current = RuntimeProcessOwnership.stat(processReader.stat(record.pid))
        if (current?.second == record.started && processReader.uid(record.pid) == adoptedUid()) {
            processKiller(record.pid, value)
        }
    }

    private fun pump(reader: java.io.BufferedReader, stream: String) = thread(name = "dsh-$stream", isDaemon = true) {
        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    listener.onLog(line, stream)
                    if (!readyReported) detectToken(line)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized private fun detectToken(line: String) {
        if (readyReported || stopping) return
        val match = TOKEN_URL.find(line) ?: return
        val url = match.value
        val token = TOKEN_PARAM.find(url)?.groupValues?.get(1) ?: return
        readyReported = true
        if (root) runCatching {
            // The pid file may name the wrapping sh; keep it for the root stop path.
            enginePid = pidHint()
            persistPendingMounts(EngineHandoff.PendingMounts(
                rootfs.canonicalPath, mountTargets, spawned = true, pid = enginePid ?: -1,
            ))
        }
        val base = "http://127.0.0.1:$port"
        thread(name = "dsh-ready", isDaemon = true) {
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && !stopping && process?.isAlive == true) {
                if (canConnect(port)) {
                    listener.onReady(this, base, token)
                    return@thread
                }
                Thread.sleep(250)
            }
            if (!stopping && process?.isAlive == true) {
                failStart("token seen but port $port never accepted connections")
            }
        }
    }

    /** Reads only this app's uid. Environment values are used for ownership, never logged. */
    private val processReader = runtimeProcessReader ?: object : RuntimeProcessReader {
        override fun pids(): List<Int> = (File("/proc").listFiles() ?: throw IOException("cannot enumerate /proc"))
            .mapNotNull { it.name.toIntOrNull() }
        override fun uid(pid: Int): Int? = try {
            Os.stat("/proc/$pid").st_uid
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) null else throw error
        } catch (_: NoSuchFileException) { null }
        private fun text(pid: Int, name: String): String = Files.readAllBytes(File("/proc/$pid/$name").toPath()).toString(Charsets.UTF_8)
        override fun stat(pid: Int): String = text(pid, "stat")
        override fun command(pid: Int): String = text(pid, "cmdline")
        override fun environment(pid: Int): String = text(pid, "environ")
        override fun cwd(pid: Int): String = Os.readlink("/proc/$pid/cwd")
    }

    private fun processSnapshot(known: Set<Int> = emptySet()): RuntimeProcessSnapshot =
        RuntimeProcessOwnership.scan(processReader, adoptedUid(), appPid(), known)

    private fun pidHint(): Int? = try {
        File(rootfs, "tmp/dsh-web.pid").readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) { null }

    private fun remember(snapshot: RuntimeProcessSnapshot) {
        snapshot.retired.forEach { cleanupKnown.remove(it) }
        for (record in snapshot.records) {
            if (cleanupKnown[record.pid]?.let { it != record.started } == true) cleanupKnown.remove(record.pid)
        }
    }

    /** Reap a captured process tree before releasing #17 ownership to another launch. */
    @Synchronized fun killStrays(): Boolean = reapStrays().complete

    private data class CleanupResult(val complete: Boolean, val ownedRemaining: Boolean, val canPruneTemp: Boolean = false)

    /** Unknown ambient processes are not proof of ownership. The default-mode
     * startup policy may continue after reaping every captured identity, while
     * killStrays itself still reports the observation as incomplete. */
    @Synchronized private fun reapStrays(allowObservationGap: Boolean = false): CleanupResult = synchronized(cleanupState) {
        reapStraysLocked(allowObservationGap)
    }

    private fun reapStraysLocked(allowObservationGap: Boolean): CleanupResult {
        val myPid = appPid()
        val roots = setOf(rootfs.absolutePath, rootfs.canonicalPath)
        val hinted = pidHint()?.let { setOf(it) }.orEmpty() + initialCleanupPids
        val deadline = System.nanoTime() + 2_000_000_000L
        var first = true
        while (true) {
            val snapshot = processSnapshot(cleanupKnown.keys + hinted)
            remember(snapshot)
            val owned = RuntimeProcessOwnership.owned(snapshot.records, roots, myPid, cleanupKnown)
            owned.forEach { cleanupKnown[it.pid] = it.started }
            val ownedRemaining = owned.isNotEmpty() || cleanupKnown.isNotEmpty()
            if (!ownedRemaining && snapshot.observed) {
                // This cache is shared across rootfs instances. Another engine may still use its glue.
                val temp = File(context.cacheDir, "proot-tmp").absolutePath
                val idle = snapshot.records.none { it.proot || it.marker != null || RuntimeProcessOwnership.within(it.cwd, temp) }
                return CleanupResult(true, false, canPruneTemp = idle)
            }
            owned.filter { it.proot }.forEach { signalOwned(it, if (first) OsConstants.SIGQUIT else OsConstants.SIGKILL) }
            owned.filterNot { it.proot }.forEach { signalOwned(it, OsConstants.SIGKILL) }
            first = false
            if ((!ownedRemaining && allowObservationGap) || System.nanoTime() >= deadline) {
                listener.onLog("[dsh-app] cleanup incomplete: ${owned.size} owned process(es) remain; " +
                    "${snapshot.uncertain.size} process observation(s) unavailable" +
                    if (snapshot.enumerationFailed) "; /proc enumeration unavailable" else "", "system")
                return CleanupResult(false, ownedRemaining)
            }
            Thread.sleep(25)
        }
    }

    private fun redact(arg: String): String {
        val eq = arg.indexOf('=')
        if (eq <= 0) return arg
        val key = arg.substring(0, eq)
        return if (SECRET_KEY.containsMatchIn(key)) "$key=***" else arg
    }

    companion object {
        const val DEFAULT_PORT = 3080
        const val GUEST_ENV = "{GUEST_ENV}"
        private val TOKEN_URL = Regex("""https?://[^\s'"]*[?&#]token=[A-Za-z0-9._~%-]+""")
        private val TOKEN_PARAM = Regex("""[?&#]token=([A-Za-z0-9._~%-]+)""")
        private val SECRET_KEY = Regex("(?i)(key|token|secret|password)")

        fun canConnect(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
        } catch (_: Exception) {
            false
        }

        fun pickPort(): Int {
            for (candidate in DEFAULT_PORT..DEFAULT_PORT + 20) {
                try {
                    ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).close()
                    return candidate
                } catch (_: Exception) {
                }
            }
            return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
    }
}

/** One sync(2): finished copies and renames are on disk before anything depends on them. */
internal fun syncFilesystems() {
    val sync = ProcessBuilder("/system/bin/sync").redirectErrorStream(true).start()
    sync.inputStream.use { it.readBytes() }
    val code = sync.waitFor()
    if (code != 0) throw IOException("sync exited with $code")
}
