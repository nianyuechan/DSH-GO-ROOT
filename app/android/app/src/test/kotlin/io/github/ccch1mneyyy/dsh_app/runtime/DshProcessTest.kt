package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DshProcessTest {
    private val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")
    private val root = rootfs.canonicalPath
    private val uid = 10245

    private class RecordingListener : DshProcess.Listener {
        val exited = CountDownLatch(1)
        val timedOut = CountDownLatch(1)
        val exits = AtomicInteger()
        val timeouts = AtomicInteger()
        var exitCode: Int? = null
        var expected: Boolean? = null

        override fun onLog(line: String, stream: String) {}
        override fun onReady(source: DshProcess, baseUrl: String, token: String) {}
        override fun onExit(source: DshProcess, code: Int, expected: Boolean) {
            exitCode = code
            this.expected = expected
            exits.incrementAndGet()
            exited.countDown()
        }

        override fun onStopTimeout(source: DshProcess) {
            timeouts.incrementAndGet()
            timedOut.countDown()
        }
    }

    private fun alive() = AdoptedEngine.Liveness.Alive(
        uid = uid,
        command = "/opt/node/bin/node /usr/local/bin/dsh web",
        cwd = "$root/root",
    )

    private class CleanupReader(
        private val pid: Int,
        private val uid: Int,
        private val root: String,
        private val enumerationFails: Boolean = false,
    ) : RuntimeProcessReader {
        val reads = mutableListOf<String>()
        private var alive = true

        fun reaped() { alive = false }

        override fun pids(): List<Int> = if (enumerationFails) throw IOException("enumeration unavailable") else emptyList()
        override fun uid(pid: Int): Int? = if (pid == this.pid && alive) uid else null
        override fun stat(pid: Int): String {
            reads += "$pid/stat"
            val fields = MutableList(20) { "0" }.also { it[0] = "S"; it[1] = "1"; it[19] = "100" }
            return "$pid (dsh) " + fields.joinToString(" ")
        }
        override fun command(pid: Int): String = "/opt/node/bin/node\u0000/usr/local/bin/dsh\u0000web\u0000"
        override fun environment(pid: Int): String = "DSH_GO_RUNTIME_ROOT=$root\u0000"
        override fun cwd(pid: Int): String = "$root/root"
    }

    private class TestContext : ContextWrapper(null) {
        lateinit var directory: File
        lateinit var info: ApplicationInfo
        override fun getCacheDir(): File = directory
        override fun getFilesDir(): File = directory
        override fun getApplicationInfo(): ApplicationInfo = info
    }

    private class LogicalRootFs(actual: File, private val logical: String) : File(actual.path) {
        override fun getCanonicalPath(): String = logical
        override fun getCanonicalFile(): File = File(logical)
    }

    private class BlockingProcess : Process() {
        val waiting = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        @Volatile private var running = true

        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive(): Boolean = running

        override fun waitFor(): Int {
            waiting.countDown()
            destroyed.await()
            return 0
        }

        override fun exitValue(): Int {
            check(!running)
            return 0
        }

        override fun destroy() {
            running = false
            destroyed.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private fun process(
        listener: RecordingListener,
        liveness: (Int) -> AdoptedEngine.Liveness = { AdoptedEngine.Liveness.Absent },
        connect: (Int) -> Boolean = { false },
        stop: (() -> Unit)? = {},
        reap: () -> Boolean = { true },
        cleanup: (List<String>) -> Boolean = { true },
        clearPending: () -> Unit = {},
        rootTerminate: (List<Int>) -> Boolean = { true },
        rootResidual: (List<String>) -> List<String> = { emptyList() },
        stopTimeoutMillis: Long = 15_000,
    ) = DshProcess(
        context = allocate(ContextWrapper::class.java),
        manifest = allocate(RuntimeManifest::class.java),
        rootfs = rootfs,
        listener = listener,
        adoptedLiveness = liveness,
        adoptedConnect = connect,
        adoptedSleep = {},
        adoptedUid = { uid },
        adoptedStop = stop?.let { signal -> { signal() } },
        processReaper = reap,
        mountCleanup = cleanup,
        clearPendingMounts = clearPending,
        rootTerminate = rootTerminate,
        rootResidual = rootResidual,
        stopTimeoutMillis = stopTimeoutMillis,
    )

    private fun spawnHarness(
        listener: RecordingListener,
        processStarter: (ProcessBuilder) -> Process,
        persistPending: (EngineHandoff.PendingMounts) -> Unit = {},
        cleanup: (List<String>) -> Boolean = { true },
        clearPending: () -> Unit = {},
        reap: () -> Boolean = { true },
        rootTerminate: (List<Int>) -> Boolean = { true },
        rootResidual: (List<String>) -> List<String> = { emptyList() },
        logicalRoot: String = "/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs",
        enumerationFails: Boolean = true,
    ): Pair<DshProcess, File> {
        val base = kotlin.io.path.createTempDirectory("spawn-failure").toFile()
        val actualRoot = File(base, "rootfs").apply { mkdirs() }
        for (path in listOf("usr/bin/setpriv", "usr/bin/env", "bin/sh", "usr/local/bin/dsh")) {
            File(actualRoot, path).apply {
                parentFile!!.mkdirs()
                writeText("test")
                setExecutable(true)
            }
        }
        val launchRoot = LogicalRootFs(actualRoot, logicalRoot)
        val context = allocate(TestContext::class.java).apply {
            directory = base
            info = allocate(ApplicationInfo::class.java).apply { nativeLibraryDir = base.path }
        }
        val manifest = RuntimeManifest(
            runtimeVersion = "v1", abi = "x86_64", rootfsAsset = "rootfs", rootfsBytes = 0,
            rootfsSha256 = null, uncompressedBytes = 0, dshVersion = "dsh", nodeVersion = "node",
            launchArgv = listOf(
                "{NATIVE_LIB_DIR}/libproot.so", "-r", "{ROOTFS}", "/usr/bin/env", "-i",
                "{GUEST_ENV}", "/bin/sh", "-c",
                "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port {PORT}",
            ),
            launchEnv = emptyMap(), raw = allocate(JSONObject::class.java),
        )
        return DshProcess(
            context = context,
            manifest = manifest,
            rootfs = launchRoot,
            listener = listener,
            adoptedUid = { uid },
            processReaper = reap,
            mountCleanup = cleanup,
            persistPendingMounts = persistPending,
            clearPendingMounts = clearPending,
            runtimeProcessReader = CleanupReader(4242, uid, logicalRoot, enumerationFails = enumerationFails),
            processStarter = processStarter,
            appPid = { 9999 },
            rootTerminate = rootTerminate,
            rootResidual = rootResidual,
        ) to base
    }

    @Test
    fun `stopping an adopted engine waits for its real exit before cleanup and callback`() {
        val firstCheck = CountDownLatch(1)
        val finishFirstCheck = CountDownLatch(1)
        val checks = AtomicInteger()
        val listener = RecordingListener()
        val mounts = listOf("$root/proc", "$root$root")
        val cleaned = mutableListOf<List<String>>()
        var stopSignals = 0
        val process = process(
            listener = listener,
            liveness = {
                if (checks.incrementAndGet() == 1) {
                    firstCheck.countDown()
                    finishFirstCheck.await(5, TimeUnit.SECONDS)
                    alive()
                } else {
                    AdoptedEngine.Liveness.Absent
                }
            },
            stop = { stopSignals += 1 },
            cleanup = { targets -> cleaned += targets; false },
        )
        process.adopt(EngineHandoff.Record(4242, 3080, "abc123", root, mounts))

        assertTrue(firstCheck.await(5, TimeUnit.SECONDS))
        process.stop()
        assertFalse("stop alone must not finish the adopted waiter", listener.exited.await(200, TimeUnit.MILLISECONDS))

        finishFirstCheck.countDown()
        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertEquals(1, stopSignals)
        assertEquals(listOf(mounts), cleaned)
        assertEquals(true, listener.expected)
        assertFalse("failed mount cleanup must reach awaitExit", process.awaitExit(1_000))
        assertFalse(process.alive)
    }

    @Test
    fun `startup failure without a waiter cleans mounts and propagates cleanup failure`() {
        val listener = RecordingListener()
        val mounts = listOf("$root/proc")
        val cleaned = mutableListOf<List<String>>()
        var pendingClears = 0
        val process = process(
            listener,
            cleanup = { targets -> cleaned += targets; false },
            clearPending = { pendingClears += 1 },
        )
        setField(process, "root", true)
        setField(process, "mountTargets", mounts)

        process.failStart("spawn failed")

        assertEquals(listOf(mounts), cleaned)
        assertFalse(process.awaitExit(1_000))
        assertEquals(0, pendingClears)
    }

    @Test
    fun `verified reaping and mount cleanup clear the pending ledger`() {
        val listener = RecordingListener()
        var pendingClears = 0
        val process = process(listener, cleanup = { true }, clearPending = { pendingClears += 1 })
        setField(process, "root", true)
        setField(process, "mountTargets", listOf("$root/proc"))
        setField(process, "processesReaped", true)

        process.failStart("spawn failed")

        assertTrue(process.awaitExit(1_000))
        assertEquals(1, pendingClears)
    }

    @Test
    fun `an adopted legacy handoff stays incomplete after standard cleanup`() {
        val listener = RecordingListener()
        val process = process(listener, cleanup = { true })
        process.adopt(EngineHandoff.Record(
            4242, 3080, "abc123", root, listOf("$root/proc"), mountTargetsComplete = false,
        ))

        process.stop()

        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertFalse(process.awaitExit(1_000))
    }

    @Test
    fun `successful mount cleanup without a completed process sweep is incomplete`() {
        val listener = RecordingListener()
        val process = process(listener, cleanup = { true })
        setField(process, "root", true)
        setField(process, "mountTargets", listOf("$root/proc"))

        process.failStart("spawn failed")

        assertFalse(process.awaitExit(1_000))
    }

    @Test
    fun `proot exit with a process observation gap completes and reports exit`() {
        val child = BlockingProcess()
        val listener = RecordingListener()
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            reap = { false },
        )
        try {
            process.start(emptyMap())
            child.destroy()

            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertTrue("PRoot must not retain ownership for a permitted observation gap", process.awaitExit(5_000))
            assertEquals(1, listener.exits.get())
            assertFalse(process.alive)
        } finally {
            child.destroy()
            base.deleteRecursively()
        }
    }

    @Test
    fun `root cleanup rejects an empty mount ledger`() {
        val listener = RecordingListener()
        var pendingClears = 0
        val process = process(listener, cleanup = { true }, clearPending = { pendingClears += 1 })
        setField(process, "root", true)
        setField(process, "mountTargets", emptyList<String>())
        setField(process, "processesReaped", true)

        process.failStart("spawn failed")

        assertFalse(process.awaitExit(1_000))
        assertEquals(0, pendingClears)
    }

    @Test
    fun `stopping an adopted engine cleans mounts before its successful callback`() {
        val listener = RecordingListener()
        val mounts = listOf("$root/proc", "$root$root")
        val cleaned = mutableListOf<List<String>>()
        val process = process(listener, cleanup = { targets -> cleaned += targets; true })
        process.adopt(EngineHandoff.Record(4242, 3080, "abc123", root, mounts))

        process.stop()

        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(mounts), cleaned)
        assertTrue(process.awaitExit(1_000))
        assertEquals(true, listener.expected)
    }

    @Test
    fun `a failed cleanup can be retried after the child is gone`() {
        val listener = RecordingListener()
        var attempts = 0
        var pendingClears = 0
        val process = process(
            listener,
            cleanup = { attempts += 1; attempts > 1 },
            clearPending = { pendingClears += 1 },
        )
        setField(process, "root", true)
        setField(process, "mountTargets", listOf("$root/proc"))

        process.failStart("spawn failed")
        assertFalse(process.awaitExit(1_000))
        process.stop()

        assertTrue(process.awaitExit(1_000))
        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertEquals(2, attempts)
        assertEquals(1, pendingClears)
    }

    @Test
    fun `stopping before spawn cleans pending mounts and reports exit`() {
        val listener = RecordingListener()
        var reaps = 0
        var pendingClears = 0
        val process = process(
            listener,
            reap = { reaps += 1; true },
            cleanup = { true },
            clearPending = { pendingClears += 1 },
        )
        setField(process, "root", true)
        setField(process, "mountTargets", listOf("$root/proc"))
        setField(process, "mountCleanupComplete", false)

        process.stop()

        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertTrue(process.awaitExit(1_000))
        assertEquals(1, reaps)
        assertEquals(1, pendingClears)
        assertEquals(1, listener.exits.get())
        assertEquals(true, listener.expected)
    }

    @Test
    fun `failed pre-spawn cleanup stays incomplete and can be retried`() {
        val listener = RecordingListener()
        var attempts = 0
        var pendingClears = 0
        val process = process(
            listener,
            cleanup = { attempts += 1; attempts > 1 },
            clearPending = { pendingClears += 1 },
        )
        setField(process, "root", true)
        setField(process, "mountTargets", listOf("$root/proc"))
        setField(process, "mountCleanupComplete", false)

        process.stop()

        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertFalse(process.awaitExit(1_000))
        assertEquals(0, pendingClears)

        process.stop()

        assertTrue(process.awaitExit(1_000))
        assertEquals(2, attempts)
        assertEquals(1, pendingClears)
        assertEquals(2, listener.exits.get())
    }

    @Test
    fun `cold reset directly reaps a pending pid omitted from proc enumeration`() {
        val pendingRootPath = "/pending-root-${System.nanoTime()}"
        val pendingRoot = File(pendingRootPath)
        val pending = EngineHandoff.PendingMounts(
            rootfs = pendingRootPath,
            mountTargets = listOf("$pendingRootPath/proc"),
            spawned = true,
            pid = 4242,
        )
        val filesDir = kotlin.io.path.createTempDirectory("pending-reap").toFile().apply { deleteOnExit() }
        EngineHandoff.savePending(filesDir, pending)
        val reader = CleanupReader(4242, uid, pendingRoot.canonicalPath)
        val listener = RecordingListener()
        var signalsReap = false
        val killed = mutableListOf<Int>()
        fun coldProcess(identity: EngineHandoff.PendingMounts) = DshProcess(
            context = allocate(TestContext::class.java).apply { directory = filesDir },
            manifest = allocate(RuntimeManifest::class.java),
            rootfs = File(identity.rootfs),
            listener = listener,
            adoptedUid = { uid },
            initialCleanupPids = setOfNotNull(identity.pid),
            runtimeProcessReader = reader,
            processKiller = { pid, _ -> killed += pid; if (signalsReap) reader.reaped() },
            appPid = { 9999 },
        )
        var mountCleanups = 0
        fun reset() = EngineHandoff.cleanupForReset(
            filesDir,
            isAlive = { false },
            mountCleanup = { mountCleanups += 1; true },
            reapPending = { identity -> coldProcess(identity).killStrays() },
        )

        assertFalse(File(pending.rootfs, "tmp/dsh-web.pid").exists())
        val blocked = runCatching { reset() }.exceptionOrNull()
        assertEquals("pending root process cleanup failed; runtime was not deleted", blocked?.message)
        assertEquals(0, mountCleanups)
        assertTrue(EngineHandoff.pendingFile(filesDir).exists())
        assertTrue("the pending pid must be inspected without enumeration", "4242/stat" in reader.reads)

        signalsReap = true
        reset()

        assertEquals(1, mountCleanups)
        assertTrue(4242 in killed)
        assertFalse(EngineHandoff.pendingFile(filesDir).exists())
    }

    @Test
    fun `pending root mounts block proot temp pruning`() {
        val child = BlockingProcess()
        val listener = RecordingListener()
        val logicalRoot = "/pending-prune-${System.nanoTime()}"
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            logicalRoot = logicalRoot,
            enumerationFails = false,
        )
        try {
            val stale = File(base, "proot-tmp/proot-2147483647-abcdef").apply { mkdirs() }
            EngineHandoff.savePending(base, EngineHandoff.PendingMounts(logicalRoot, listOf("$logicalRoot/proc")))

            process.start(emptyMap())

            assertTrue("retained root cleanup state must fail closed", stale.isDirectory)
        } finally {
            child.destroy()
            process.awaitExit(5_000)
            base.deleteRecursively()
        }
    }

    @Test
    fun `stop during a blocked spawn cannot be hidden by the pre-spawn cleanup stopper`() {
        val base = kotlin.io.path.createTempDirectory("spawn-stop").toFile()
        val actualRoot = File(base, "rootfs").apply { mkdirs() }
        for (path in listOf("usr/bin/setpriv", "usr/bin/env", "bin/sh", "usr/local/bin/dsh")) {
            File(actualRoot, path).apply {
                parentFile!!.mkdirs()
                writeText("test")
                setExecutable(true)
            }
        }
        val logicalRoot = "/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs"
        val launchRoot = LogicalRootFs(actualRoot, logicalRoot)
        assertNull(RootLaunch.unavailable(launchRoot))
        val context = allocate(TestContext::class.java).apply {
            directory = base
            info = allocate(ApplicationInfo::class.java).apply { nativeLibraryDir = base.path }
        }
        val manifest = RuntimeManifest(
            runtimeVersion = "v1", abi = "x86_64", rootfsAsset = "rootfs", rootfsBytes = 0,
            rootfsSha256 = null, uncompressedBytes = 0, dshVersion = "dsh", nodeVersion = "node",
            launchArgv = listOf(
                "{NATIVE_LIB_DIR}/libproot.so", "-r", "{ROOTFS}", "/usr/bin/env", "-i",
                "{GUEST_ENV}", "/bin/sh", "-c",
                "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port {PORT}",
            ),
            launchEnv = emptyMap(), raw = allocate(JSONObject::class.java),
        )
        val builderEntered = CountDownLatch(1)
        val finishSpawn = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val child = BlockingProcess()
        val listener = RecordingListener()
        val process = DshProcess(
            context = context,
            manifest = manifest,
            rootfs = launchRoot,
            listener = listener,
            adoptedUid = { uid },
            processReaper = {
                finishCleanup.await()
                true
            },
            mountCleanup = { true },
            persistPendingMounts = {},
            clearPendingMounts = {},
            runtimeProcessReader = CleanupReader(4242, uid, logicalRoot, enumerationFails = true),
            processStarter = { _ ->
                builderEntered.countDown()
                finishSpawn.await(5, TimeUnit.SECONDS)
                child
            },
            appPid = { 9999 },
            rootTerminate = { true },
            rootResidual = { emptyList() },
        )
        var startFailure: Throwable? = null
        val starter = thread(isDaemon = true, name = "blocked-spawn-test") {
            try {
                process.start(emptyMap(), rootEngine = true, rootGranted = true)
            } catch (error: Throwable) {
                startFailure = error
            }
        }
        try {
            assertTrue(builderEntered.await(5, TimeUnit.SECONDS))
            process.stop()
            val originalStopper = getField(process, "stopper") as Thread
            assertTrue(originalStopper.isAlive)

            finishSpawn.countDown()
            assertTrue(child.waiting.await(5, TimeUnit.SECONDS))
            starter.join(5000)
            assertFalse("start must finish its post-spawn stop attempt", starter.isAlive)
            assertNull(startFailure)
            assertTrue("the original stopper must still be blocked", originalStopper.isAlive)
            assertSame(originalStopper, getField(process, "stopper"))
            process.stop()
            finishCleanup.countDown()

            assertTrue("the second stop must not lose the newly spawned child", child.destroyed.await(5, TimeUnit.SECONDS))
            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertTrue(process.awaitExit(5_000))
            assertEquals(1, listener.exits.get())
            assertEquals(true, listener.expected)
        } finally {
            finishSpawn.countDown()
            finishCleanup.countDown()
            child.destroy()
            starter.join(5000)
            base.deleteRecursively()
        }
    }

    @Test
    fun `spawn failure while stop waits reports one terminal exit for proot and root`() {
        for (rootEngine in listOf(false, true)) {
            val builderEntered = CountDownLatch(1)
            val failSpawn = CountDownLatch(1)
            val listener = RecordingListener()
            var pendingClears = 0
            val (process, base) = spawnHarness(
                listener = listener,
                processStarter = { _ ->
                    builderEntered.countDown()
                    failSpawn.await(5, TimeUnit.SECONDS)
                    throw IOException("spawn failed")
                },
                clearPending = { pendingClears += 1 },
            )
            var startFailure: Throwable? = null
            val starter = thread(isDaemon = true, name = "failed-spawn-test") {
                try {
                    process.start(emptyMap(), rootEngine = rootEngine, rootGranted = rootEngine)
                } catch (error: Throwable) {
                    startFailure = error
                }
            }
            try {
                assertTrue(builderEntered.await(5, TimeUnit.SECONDS))
                process.stop()
                failSpawn.countDown()
                starter.join(5000)
                assertFalse(starter.isAlive)
                assertEquals("spawn failed", startFailure?.message)

                // This is the controller's catch path after start() propagates the failure.
                process.failStart("spawn failed")

                assertTrue("root=$rootEngine must report terminal exit", listener.exited.await(5, TimeUnit.SECONDS))
                assertTrue(process.awaitExit(1_000))
                assertEquals(1, listener.exits.get())
                assertEquals(true, listener.expected)
                if (rootEngine) assertEquals(1, pendingClears)
            } finally {
                failSpawn.countDown()
                starter.join(5000)
                base.deleteRecursively()
            }
        }
    }

    @Test
    fun `post spawn failure before a waiter lets the stopper finish cleanup and exit once`() {
        val afterChild = CountDownLatch(1)
        val failSetup = CountDownLatch(1)
        val persistCalls = AtomicInteger()
        val child = BlockingProcess()
        val listener = RecordingListener()
        val cleaned = mutableListOf<List<String>>()
        var pendingClears = 0
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            persistPending = {
                if (persistCalls.incrementAndGet() == 3) {
                    afterChild.countDown()
                    failSetup.await(5, TimeUnit.SECONDS)
                    throw IOException("waiter setup failed")
                }
            },
            cleanup = { targets -> cleaned += targets; true },
            clearPending = { pendingClears += 1 },
        )
        var startFailure: Throwable? = null
        val starter = thread(isDaemon = true, name = "failed-waiter-test") {
            try {
                process.start(emptyMap(), rootEngine = true, rootGranted = true)
            } catch (error: Throwable) {
                startFailure = error
            }
        }
        try {
            assertTrue(afterChild.await(5, TimeUnit.SECONDS))
            process.stop()
            failSetup.countDown()
            starter.join(5000)
            assertFalse(starter.isAlive)
            assertEquals("waiter setup failed", startFailure?.message)

            process.failStart("waiter setup failed")

            assertTrue(process.awaitExit(5_000))
            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertTrue(child.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(process.mountTargets), cleaned)
            assertEquals(1, pendingClears)
            assertEquals(1, listener.exits.get())
            assertEquals(true, listener.expected)
        } finally {
            failSetup.countDown()
            child.destroy()
            starter.join(5000)
            base.deleteRecursively()
        }
    }

    @Test
    fun `stop after spawn failure and before failStart reports one terminal exit without a child`() {
        val listener = RecordingListener()
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { _ -> throw IOException("spawn failed") },
        )
        var startFailure: Throwable? = null
        val starter = thread(isDaemon = true, name = "closed-spawn-gate-test") {
            try {
                process.start(emptyMap())
            } catch (error: Throwable) {
                startFailure = error
            }
        }
        try {
            // Joining is the deterministic gate: spawning is closed, but the controller catch
            // has not called failStart yet when this stop arrives.
            starter.join(5000)
            assertFalse(starter.isAlive)
            assertEquals("spawn failed", startFailure?.message)

            process.stop()
            process.failStart("spawn failed")

            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertTrue(process.awaitExit(1_000))
            assertEquals(1, listener.exits.get())
            assertEquals(true, listener.expected)
        } finally {
            starter.join(5000)
            base.deleteRecursively()
        }
    }

    @Test
    fun `stop after post spawn failure and before failStart finishes without a waiter exactly once`() {
        for (cleanupSucceeds in listOf(true, false)) {
            val persistCalls = AtomicInteger()
            val child = BlockingProcess()
            val listener = RecordingListener()
            var pendingClears = 0
            val (process, base) = spawnHarness(
                listener = listener,
                processStarter = { child },
                persistPending = {
                    if (persistCalls.incrementAndGet() == 3) throw IOException("waiter setup failed")
                },
                cleanup = { cleanupSucceeds },
                clearPending = { pendingClears += 1 },
            )
            var startFailure: Throwable? = null
            val starter = thread(isDaemon = true, name = "closed-waiter-gate-test") {
                try {
                    process.start(emptyMap(), rootEngine = true, rootGranted = true)
                } catch (error: Throwable) {
                    startFailure = error
                }
            }
            try {
                starter.join(5000)
                assertFalse(starter.isAlive)
                assertEquals("waiter setup failed", startFailure?.message)

                process.stop()
                process.failStart("waiter setup failed")

                assertTrue("cleanup=$cleanupSucceeds must report exit", listener.exited.await(5, TimeUnit.SECONDS))
                assertEquals(cleanupSucceeds, process.awaitExit(5_000))
                assertTrue(child.destroyed.await(5, TimeUnit.SECONDS))
                assertEquals(if (cleanupSucceeds) 1 else 0, pendingClears)
                assertEquals(1, listener.exits.get())
                assertEquals(true, listener.expected)
            } finally {
                child.destroy()
                starter.join(5000)
                base.deleteRecursively()
            }
        }
    }

    @Test
    fun `controller retains process ownership after incomplete cleanup`() {
        val source = process(RecordingListener())
        setField(source, "mountCleanupComplete", false)
        val controller = allocate(RuntimeController::class.java)
        setField(controller, "process", source)
        // A reset owns the final state; this test only pins the retained ownership.
        setField(controller, "resetting", true)

        val callback = runCatching { controller.onExit(source, 0, true) }

        assertSame(source, getField(controller, "process"))
        assertTrue(callback.isSuccess)
    }

    @Test
    fun `root stop terminates the recorded pid even when proc enumeration is unavailable`() {
        val child = BlockingProcess()
        val listener = RecordingListener()
        val terminated = mutableListOf<List<Int>>()
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            rootTerminate = { pids -> terminated += pids; true },
        )
        try {
            setField(process, "root", true)
            setField(process, "readyReported", true)
            setField(process, "mountTargets", listOf("$root/proc"))
            setField(process, "enginePid", 4242)
            setField(process, "process", child)

            process.stop()

            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(listOf(4242)), terminated)
            assertTrue("a verified kill lets cleanup complete", process.awaitExit(5_000))
            assertTrue(child.destroyed.await(2, TimeUnit.SECONDS))
            assertEquals(1, listener.exits.get())
        } finally {
            child.destroy()
            base.deleteRecursively()
        }
    }

    @Test
    fun `an unconfirmed root kill keeps cleanup incomplete`() {
        val child = BlockingProcess()
        val listener = RecordingListener()
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            rootTerminate = { false },
        )
        try {
            setField(process, "root", true)
            setField(process, "readyReported", true)
            setField(process, "mountTargets", listOf("$root/proc"))
            setField(process, "enginePid", 4242)
            setField(process, "process", child)

            process.stop()

            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertFalse("an unconfirmed kill must stay incomplete", process.awaitExit(1_000))
        } finally {
            child.destroy()
            base.deleteRecursively()
        }
    }

    @Test
    fun `mount residual keeps cleanup incomplete even when unmount succeeded`() {
        val child = BlockingProcess()
        val listener = RecordingListener()
        val mounts = listOf("$root/proc", "$root$root")
        val (process, base) = spawnHarness(
            listener = listener,
            processStarter = { child },
            rootResidual = { targets -> targets },
        )
        try {
            setField(process, "root", true)
            setField(process, "readyReported", true)
            setField(process, "mountTargets", mounts)
            setField(process, "enginePid", 4242)
            setField(process, "process", child)

            process.stop()

            assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
            assertFalse("this instance's remaining mounts must block the transition", process.awaitExit(1_000))
        } finally {
            child.destroy()
            base.deleteRecursively()
        }
    }

    @Test
    fun `adopted root stop terminates the recorded engine pid`() {
        val listener = RecordingListener()
        val terminated = mutableListOf<List<Int>>()
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val process = process(
            listener,
            liveness = {
                if (checks.incrementAndGet() == 1) {
                    release.await(5, TimeUnit.SECONDS)
                    alive()
                } else {
                    AdoptedEngine.Liveness.Absent
                }
            },
            stop = null,
            rootTerminate = { pids -> terminated += pids; true },
        )
        process.adopt(EngineHandoff.Record(4242, 3080, "abc123", root, listOf("$root/proc")))
        process.stop()
        release.countDown()

        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(listOf(4242)), terminated)
    }

    @Test
    fun `a wedged stop reports its timeout once and still finishes after release`() {
        val listener = RecordingListener()
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val process = process(
            listener,
            liveness = {
                if (checks.incrementAndGet() == 1) {
                    release.await(5, TimeUnit.SECONDS)
                    alive()
                } else {
                    AdoptedEngine.Liveness.Absent
                }
            },
            stop = { release.await(5, TimeUnit.SECONDS) },
            stopTimeoutMillis = 200,
        )
        process.adopt(EngineHandoff.Record(4242, 3080, "abc123", root, listOf("$root/proc")))
        process.stop()

        assertTrue("a wedged stop must reach the UI", listener.timedOut.await(5, TimeUnit.SECONDS))
        assertEquals(1, listener.timeouts.get())
        release.countDown()
        assertTrue(listener.exited.await(5, TimeUnit.SECONDS))
    }

    private fun getField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
