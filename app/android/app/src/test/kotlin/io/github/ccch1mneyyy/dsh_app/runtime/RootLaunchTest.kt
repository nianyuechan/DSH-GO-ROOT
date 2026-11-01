package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(script.contains("/bin/sh -c 'echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 3080'"))
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
}
