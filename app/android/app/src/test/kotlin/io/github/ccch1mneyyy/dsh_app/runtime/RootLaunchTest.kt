package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RootLaunchTest {
    private val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")

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
        assertTrue(plan.script.contains("exec /system/bin/chroot '${rootfs.path}' /bin/sh -c "))
        assertTrue(plan.script.contains("/usr/bin/setpriv --reuid=10245 --regid=10245"))
        // A bare `chroot` would resolve through the host's PATH, which is not what runs here.
        assertFalse(Regex("exec chroot ").containsMatchIn(plan.script))
        assertFalse("the host has no Ubuntu tool tree", plan.script.contains("${rootfs.path}/usr/bin/setpriv"))
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
}
