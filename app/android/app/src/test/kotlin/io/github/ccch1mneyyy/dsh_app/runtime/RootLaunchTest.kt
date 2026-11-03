package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RootLaunchTest {
    private val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")
    private val originalAbsolutePath = DirectLaunch.absolutePath
    private val originalCanonicalRoot = RootLaunch.canonicalRoot
    private val originalCleanupStarter = RootLaunch.cleanupStarter
    private val originalTerminateStarter = RootLaunch.terminateStarter
    private val originalResidualStarter = RootLaunch.residualStarter

    @Before
    fun usePosixPaths() {
        DirectLaunch.absolutePath = { it.startsWith('/') }
        RootLaunch.canonicalRoot = { it }
    }

    @After
    fun restorePathSemantics() {
        DirectLaunch.absolutePath = originalAbsolutePath
        RootLaunch.canonicalRoot = originalCanonicalRoot
        RootLaunch.cleanupStarter = originalCleanupStarter
        RootLaunch.terminateStarter = originalTerminateStarter
        RootLaunch.residualStarter = originalResidualStarter
    }

    private fun composedArgv(vararg binds: Pair<String, String>): List<String> = buildList {
        add("${rootfs.path}-native/proot")
        add("--kill-on-exit")
        add("-r"); add(rootfs.path)
        for ((host, guest) in binds) { add("-b"); add("$host:$guest") }
        add("/usr/bin/env"); add("-i")
        add("HOME=/root")
        add("PATH=/usr/local/bin:/usr/bin:/bin")
        add("TMPDIR=/tmp")
        add("DSH_GO_RUNTIME_ROOT=${rootfs.path}")
        add("/bin/sh"); add("-c")
        add("echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 3080")
    }

    private val gids = intArrayOf(3003, 3002, 1015, 1023)

    private class BlockingCleanupProcess(
        private val blockRead: Boolean = false,
        private val blockWait: Boolean = false,
    ) : Process() {
        val destroyed = CountDownLatch(1)
        @Volatile var destroyCalls = 0

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                if (blockRead) destroyed.await()
                return -1
            }
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()

        override fun waitFor(): Int {
            if (blockWait) destroyed.await()
            return 0
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyCalls += 1
            destroyed.countDown()
        }
    }

    private fun assertHungCleanupFails(process: BlockingCleanupProcess) {
        RootLaunch.cleanupStarter = { process }
        val finished = CountDownLatch(1)
        var result: Boolean? = null
        val worker = thread(isDaemon = true, name = "root-cleanup-test") {
            result = RootLaunch.cleanup(listOf("/data/root/proc"))
            finished.countDown()
        }
        try {
            assertTrue("cleanup must have a bounded watchdog", finished.await(20, TimeUnit.SECONDS))
            assertFalse(result!!)
            assertTrue("a timed-out su process must be destroyed", process.destroyCalls > 0)
        } finally {
            process.destroy()
            worker.join(2000)
        }
    }

    @Test
    fun `script mounts, drops to the app uid and keeps the pidfile semantics`() {
        val plan = RootLaunch.prepare(
            rootfs, composedArgv("/storage/emulated/0" to "/sdcard"), appUid = 10245, appGids = gids,
        )
        val script = plan.script
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertTrue(script.contains("set -e\n"))
        // lazy cleanup of every mount this script will create, self-bind included
        assertTrue(script.contains("umount -l '${rootfs.path}/proc' '${rootfs.path}/sys' '${rootfs.path}/dev' '${rootfs.path}${rootfs.path}' '${rootfs.path}/sdcard'"))
        // pseudo filesystems
        assertTrue(script.contains("mount -t proc proc '${rootfs.path}/proc'"))
        assertTrue(script.contains("mount --rbind /dev '${rootfs.path}/dev'"))
        assertTrue(script.contains("mount --rbind /sys '${rootfs.path}/sys'"))
        // the caller bind: mkdir then mount under the rootfs
        assertTrue(script.contains("mkdir -p '${rootfs.path}/sdcard'"))
        assertTrue(script.contains("mount --bind '/storage/emulated/0' '${rootfs.path}/sdcard'"))
        // self-bind keeps DSH_GO_RUNTIME_ROOT valid inside the chroot
        assertTrue(script.contains("mount --bind '${rootfs.path}' '${rootfs.path}${rootfs.path}'"))
        // drop to the app's own uid, keep the app's supplementary groups (AID_INET lives there)
        assertTrue(script.contains("--reuid=10245 --regid=10245 --groups=3003,3002,1015,1023 "))
        // guest env travels verbatim; the composed guest command runs through /bin/sh -c
        assertTrue(script.contains("'HOME=/root'"))
        assertTrue(script.contains("'DSH_GO_RUNTIME_ROOT=${rootfs.path}'"))
        // The whole inner command is one quoted argument to the host's chroot, so each
        // nested quote is shell-escaped ('\'' in the script text).
        assertTrue(script.contains("/bin/sh -c '/usr/bin/setpriv --reuid=10245 --regid=10245 --groups=3003,3002,1015,1023 /usr/bin/env -i "))
        assertTrue(script.contains(" /bin/sh -c '\\''echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 3080'\\'''"))
        assertEquals(File(rootfs, "root"), plan.cwd)
    }

    @Test
    fun `empty supplementary groups fall back to clear-groups`() {
        val plan = RootLaunch.prepare(rootfs, composedArgv(), appUid = 10245, appGids = IntArray(0))
        assertTrue(plan.script.contains("--clear-groups "))
        assertFalse(plan.script.contains("--groups="))
    }

    @Test
    fun `multiple binds each get a mount line`() {
        val plan = RootLaunch.prepare(
            rootfs,
            composedArgv("/storage/emulated/0" to "/sdcard", "/data/local/tmp/x" to "/opt/x"),
            appUid = 10245, appGids = gids,
        )
        assertTrue(plan.script.contains("mount --bind '/storage/emulated/0' '${rootfs.path}/sdcard'"))
        assertTrue(plan.script.contains("mount --bind '/data/local/tmp/x' '${rootfs.path}/opt/x'"))
    }

    @Test
    fun `an unknown future shell template is rejected`() {
        val argv = composedArgv().dropLast(1) + "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --bogus"
        try {
            RootLaunch.prepare(rootfs, argv, appUid = 10245, appGids = gids)
            fail("expected the unknown template to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unsupported"))
        }
    }

    @Test
    fun `a non-web profile is rejected`() {
        val argv = composedArgv().dropLast(1) + "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh serve"
        try {
            RootLaunch.prepare(rootfs, argv, appUid = 10245, appGids = gids)
            fail("expected the non-web profile to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("web profile"))
        }
    }

    @Test
    fun `a missing guest environment is rejected`() {
        val argv = listOf("${rootfs.path}-native/proot", "-r", rootfs.path, "/bin/sh", "-c", "echo hi")
        try {
            RootLaunch.prepare(rootfs, argv, appUid = 10245, appGids = gids)
            fail("expected the missing guest environment to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("guest environment"))
        }
    }

    @Test
    fun `unsupported guest command shape is rejected`() {
        val argv = composedArgv().dropLast(3) + listOf("/bin/bash", "-c", "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 3080")
        try {
            RootLaunch.prepare(rootfs, argv, appUid = 10245, appGids = gids)
            fail("expected the unsupported guest command to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unsupported guest command"))
        }
    }

    @Test
    fun `the tools exec from the rootfs, never the host`() {
        val plan = RootLaunch.prepare(rootfs, composedArgv(), appUid = 10245, appGids = gids)
        // The host shell can only resolve the host's own chroot; everything after it is
        // the rootfs's own paths, which exist only once we are inside.
        assertTrue(plan.script.contains("exec /system/bin/setsid /system/bin/chroot '${rootfs.path}' /bin/sh -c "))
        assertTrue(plan.script.contains("/usr/bin/setpriv --reuid=10245 --regid=10245"))
        // A bare `chroot` would resolve through the host's PATH, which is not what runs here.
        assertFalse(Regex("exec chroot ").containsMatchIn(plan.script))
        assertFalse("the host has no Ubuntu tool tree", plan.script.contains("${rootfs.path}/usr/bin/setpriv"))
    }

    @Test
    fun `setsid sits outside the chroot so the engine leaves the app's process group`() {
        val plan = RootLaunch.prepare(rootfs, composedArgv(), appUid = 10245, appGids = gids)
        val line = plan.script.lines().last { it.startsWith("exec ") }
        // Order matters: setsid(1) must be the host's, before the chroot replaces the root.
        val sid = line.indexOf("/system/bin/setsid")
        val chroot = line.indexOf("/system/bin/chroot")
        assertTrue("both tools must be present", sid >= 0 && chroot >= 0)
        assertTrue("setsid must run before the chroot", sid < chroot)
    }

    @Test
    fun `the pseudo filesystems come from mount, never from the proot binds`() {
        // A real launch carries -b /dev, -b /proc, -b /sys and their sub-paths. Each of
        // those has to disappear: after `--rbind /dev`, /dev/random is a character device
        // and `mkdir -p` over it aborts the whole script under `set -e`.
        val argv = composedArgv(
            "/dev" to "/dev",
            "/dev/urandom" to "/dev/random",
            "/proc/self/fd" to "/dev/fd",
            "/sys" to "/sys",
            "/storage/emulated/0" to "/sdcard",
        )
        val script = RootLaunch.prepare(rootfs, argv, appUid = 10245, appGids = gids).script
        assertFalse("no bind may target /dev", script.contains("mount --bind '/dev'"))
        assertFalse("no bind may target /dev/random", script.contains("'/dev/urandom'"))
        assertFalse("no bind may target /dev/fd", script.contains("mount --bind '/proc/self/fd'"))
        assertFalse("no bind may target /sys", script.contains("mount --bind '/sys'"))
        // The user bind still has to be there.
        assertTrue(script.contains("mount --bind '/storage/emulated/0' '${rootfs.path}/sdcard'"))
        // Exactly one mount per pseudo filesystem.
        assertEquals(1, Regex("mount -t proc proc").findAll(script).count())
        assertEquals(1, Regex("mount --rbind /dev ").findAll(script).count())
        assertEquals(1, Regex("mount --rbind /sys ").findAll(script).count())
    }

    @Test
    fun `a bind target that already exists does not abort the script`() {
        val script = RootLaunch.prepare(
            rootfs, composedArgv("/storage/emulated/0" to "/sdcard"), appUid = 10245, appGids = gids,
        ).script
        // set -e is on, so a surviving directory from an earlier run must not fail mkdir.
        assertTrue(script.contains("mkdir -p '${rootfs.path}/sdcard' 2>/dev/null || true"))
    }

    @Test
    fun `selection defers to safe mode, the saved choice and the last failure`() {
        assertFalse(RootLaunch.select(rootfs, requested = false, safe = false, granted = true).root)
        assertFalse("safe mode stays PRoot", RootLaunch.select(rootfs, requested = true, safe = true, granted = true).root)
        assertFalse("no su, no chroot", RootLaunch.select(rootfs, requested = true, safe = false, granted = false).root)
        val failed = RootLaunch.select(rootfs, requested = true, safe = false, failed = true, granted = true)
        assertFalse(failed.root)
        assertEquals(RootLaunch.FAILED, failed.fallbackReason)
    }

    @Test
    fun `a runtime without the chroot tools falls back with a named reason`() {
        val selection = RootLaunch.select(rootfs, requested = true, safe = false, granted = true)
        assertFalse(selection.root)
        // The test fixture is not a real rootfs, so the first missing tool names the reason.
        assertEquals("rootSetprivMissing", selection.fallbackReason)
        assertNotNull(RootLaunch.unavailable(rootfs))
    }

    @Test
    fun `cleanup unmounts in reverse order and verifies proc mount escaping`() {
        val script = RootLaunch.cleanupScript(listOf("/data/root/proc", "/data/root/My Files"))
        assertTrue(script.indexOf("umount -l '/data/root/My Files'") < script.indexOf("umount -l '/data/root/proc'"))
        assertTrue(script.contains("grep -F ' /data/root/My\\040Files ' /proc/self/mounts"))
        assertTrue(script.contains("then exit 1"))
    }

    @Test
    fun `cleanup escapes tabs and backslashes in proc mount paths`() {
        val script = RootLaunch.cleanupScript(listOf("/data/root/My\tFiles\\Backup"))
        assertTrue(script.contains("grep -F ' /data/root/My\\011Files\\134Backup ' /proc/self/mounts"))
    }

    @Test
    fun `cleanup reports a failed unmount verification`() {
        var requested: String? = null
        assertFalse(RootLaunch.cleanup(listOf("/data/root/proc")) { script ->
            requested = script
            1
        })
        assertTrue(requested!!.contains("umount -l '/data/root/proc'"))
    }

    @Test
    fun `cleanup watchdog destroys su when its output read hangs`() {
        assertHungCleanupFails(BlockingCleanupProcess(blockRead = true))
    }

    @Test
    fun `cleanup watchdog destroys su when its process wait hangs`() {
        assertHungCleanupFails(BlockingCleanupProcess(blockWait = true))
    }

    @Test
    fun `cleanup rejects an empty mount ledger`() {
        var called = false

        assertFalse(RootLaunch.cleanup(emptyList()) {
            called = true
            0
        })
        assertFalse(called)
    }

    @Test
    fun `cleanup is idempotent for missing paths and nested overmounts`() {
        val targets = listOf("/data/root/missing", "/data/root/missing/overmount")
        var calls = 0
        repeat(2) {
            assertTrue(RootLaunch.cleanup(targets) { script ->
                calls += 1
                assertTrue(script.contains("umount -l '/data/root/missing/overmount'"))
                assertTrue(script.contains("umount -l '/data/root/missing'"))
                assertTrue(script.indexOf("missing/overmount") < script.indexOf("'/data/root/missing'"))
                0
            })
        }
        assertEquals(2, calls)
    }

    @Test
    fun `cached availability does not probe su`() {
        val original = RootLaunch.suProbe
        var probes = 0
        RootLaunch.suProbe = { probes += 1; true }
        try {
            RootLaunch.invalidate()
            assertNull(RootLaunch.cachedGrant())
            assertFalse(RootLaunch.cachedAvailable(rootfs))
            assertEquals(0, probes)
        } finally {
            RootLaunch.suProbe = original
            RootLaunch.invalidate()
        }
    }

    @Test
    fun `supplementary groups come from proc status`() {
        val status = File.createTempFile("status", ".txt").apply {
            writeText("Name:\tdsh_app\nGroups:\t3003 3002 1015 1023\n")
            deleteOnExit()
        }
        // AID_INET (3003) is the one that lets the chrooted engine open sockets.
        assertArrayEquals(intArrayOf(3003, 3002, 1015, 1023), RootLaunch.supplementaryGroups(status))
    }

    @Test
    fun `a status file without groups yields an empty array`() {
        val status = File.createTempFile("status", ".txt").apply {
            writeText("Name:\tdsh_app\n")
            deleteOnExit()
        }
        assertArrayEquals(IntArray(0), RootLaunch.supplementaryGroups(status))
    }

    @Test
    fun `terminate signals the whole recorded process group and verifies it is gone`() {
        var captured: String? = null
        assertTrue(RootLaunch.terminate(listOf(123, 456, 123)) { script ->
            captured = script
            0
        })
        val script = captured!!
        // The engine is setsid's session leader, so the node child follows the group kill.
        assertTrue(script.contains("kill -TERM -- -\"\$pid\""))
        assertTrue(script.contains("kill -KILL -- -\"\$pid\""))
        assertTrue(script.contains("kill -0 \"\$pid\""))
        assertTrue("duplicates collapse", script.contains("for pid in 123 456; do"))
        assertFalse("terminate never unmounts anything", script.contains("umount"))
        assertFalse("terminate never scans the uid", script.contains("ps -u"))
    }

    @Test
    fun `terminate reports failure when a recorded pid is still alive`() {
        val finished = CountDownLatch(1)
        var result: Boolean? = null
        val worker = thread(isDaemon = true, name = "terminate-failure-test") {
            result = RootLaunch.terminate(listOf(123)) { 1 }
            finished.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(result!!)
        worker.join(2000)
    }

    @Test
    fun `terminate refuses an empty or invalid pid list`() {
        var called = false
        assertFalse(RootLaunch.terminate(listOf(0, -1)) {
            called = true
            0
        })
        assertFalse(called)
        assertFalse(RootLaunch.terminate(emptyList()) { 0 })
    }

    @Test
    fun `terminate watchdog destroys su when the kill script hangs`() {
        val process = BlockingCleanupProcess(blockRead = true)
        RootLaunch.terminateStarter = { process }
        val finished = CountDownLatch(1)
        var result: Boolean? = null
        val worker = thread(isDaemon = true, name = "terminate-watchdog-test") {
            result = RootLaunch.terminate(listOf(4242))
            finished.countDown()
        }
        try {
            assertTrue("terminate must have a bounded watchdog", finished.await(20, TimeUnit.SECONDS))
            assertFalse(result!!)
            assertTrue("a timed-out kill must be destroyed", process.destroyCalls > 0)
        } finally {
            process.destroy()
            worker.join(2000)
        }
    }

    @Test
    fun `residual reports only this instance's targets and never unmounts`() {
        var captured: String? = null
        val targets = listOf("/data/local/tmp/dsh-root/proc", "/data/local/tmp/dsh-root/data/local/tmp/dsh-root")
        val left = RootLaunch.residual(targets) { script ->
            captured = script
            "/data/local/tmp/dsh-root/proc\n"
        }
        assertEquals(listOf(targets[0]), left)
        val script = captured!!
        assertTrue(script.contains("grep -F ' ${targets[0]} ' /proc/self/mounts"))
        assertTrue(script.contains("grep -F ' ${targets[1]} ' /proc/self/mounts"))
        assertFalse("residual must not unmount", script.contains("umount"))
        assertFalse("residual must not use a wildcard", script.contains("grep -F '/data/root"))
    }

    @Test
    fun `residual ignores mounts that belong to someone else`() {
        val targets = listOf("/data/local/tmp/dsh-root/proc")
        assertEquals(emptyList<String>(), RootLaunch.residual(targets) { "/other/app/proc\n" })
    }

    @Test
    fun `an unanswered residual probe keeps the targets as residual`() {
        val targets = listOf("/data/local/tmp/dsh-root/proc")
        assertEquals(targets, RootLaunch.residual(targets) { null })
    }

    @Test
    fun `residual escapes proc mount paths`() {
        val script = RootLaunch.residualScript(listOf("/data/local/tmp/dsh-root/My Files"))
        assertTrue(script.contains("grep -F ' /data/local/tmp/dsh-root/My\\040Files ' /proc/self/mounts"))
        assertTrue(script.contains("echo '/data/local/tmp/dsh-root/My Files'"))
    }
}
