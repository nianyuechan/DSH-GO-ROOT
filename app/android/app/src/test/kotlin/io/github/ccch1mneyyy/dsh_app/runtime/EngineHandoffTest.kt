package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineHandoffTest {
    private val posixRoot = "/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs"
    private val rootfs = File(posixRoot)
    private val filesDir: File = kotlin.io.path.createTempDirectory("handoff").toFile().apply { deleteOnExit() }

    private val record = EngineHandoff.Record(
        pid = 4242, port = 3080, token = "abc123", rootfs = rootfs.canonicalPath,
        mountTargets = listOf("$posixRoot/proc"),
    )

    @Test
    fun `a saved record loads back unchanged`() {
        EngineHandoff.save(filesDir, record)
        assertEquals(record, EngineHandoff.load(filesDir))
    }

    @Test
    fun `mount targets survive the handoff`() {
        val withMounts = record.copy(mountTargets = listOf("/data/root/proc", "/data/root/My Files"))
        EngineHandoff.save(filesDir, withMounts)
        assertEquals(withMounts, EngineHandoff.load(filesDir))
    }

    @Test
    fun `legacy records without mounts fall back to the standard targets but stay incomplete`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }.writeText(
            "schema=1\npid=4242\nport=3080\ntoken=abc123\nrootfs=${rootfs.canonicalPath}\n",
        )
        val loaded = EngineHandoff.load(filesDir)
        assertEquals(RootLaunch.standardMountTargets(rootfs), loaded?.mountTargets)
        assertEquals(false, loaded?.toMap()?.get("mountTargetsComplete"))
    }

    @Test
    fun `no file is no record`() {
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `clearing removes the record`() {
        EngineHandoff.save(filesDir, record)
        EngineHandoff.clear(filesDir)
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `a hand-edited or truncated file is rejected, not half-read`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }.writeText("{not json")
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `a record from an older schema is discarded`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }
            .writeText("schema=0\npid=1\nport=3080\ntoken=x\nrootfs=/r\n")
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `a record missing its token is not adoptable`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }
            .writeText("schema=1\npid=1\nport=3080\ntoken=\nrootfs=/r\n")
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `corrupt mount data rejects the whole record`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }
            .writeText("schema=1\npid=1\nport=3080\ntoken=t\nrootfs=/r\nmounts=%%%\n")
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `empty handoff mounts are corrupt and block reset`() {
        EngineHandoff.save(filesDir, record.copy(mountTargets = emptyList()))

        assertNull(EngineHandoff.load(filesDir))
        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { true })
        }
        assertTrue(EngineHandoff.file(filesDir).exists())
    }

    @Test
    fun `empty pending mounts are corrupt and block reset`() {
        EngineHandoff.savePending(filesDir, EngineHandoff.PendingMounts(rootfs.canonicalPath, emptyList()))

        assertNull(EngineHandoff.loadPending(filesDir))
        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { true })
        }
        assertTrue(EngineHandoff.pendingFile(filesDir).exists())
    }

    @Test
    fun `an impossible port is rejected`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }
            .writeText("schema=1\npid=1\nport=70000\ntoken=t\nrootfs=/r\n")
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `pending mounts persist separately and are never an adoptable engine`() {
        val pending = EngineHandoff.PendingMounts(
            rootfs = rootfs.canonicalPath,
            mountTargets = listOf("$posixRoot/proc", "$posixRoot/My Files"),
        )

        EngineHandoff.savePending(filesDir, pending)

        assertEquals(pending, EngineHandoff.loadPending(filesDir))
        assertNull(EngineHandoff.load(filesDir))
    }

    @Test
    fun `reset rejects a corrupt handoff instead of deleting its rootfs`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }.writeText("truncated")
        var cleaned = false

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { cleaned = true; true })
        }

        assertFalse(cleaned)
        assertTrue(EngineHandoff.file(filesDir).exists())
    }

    @Test
    fun `reset keeps a live handoff without attempting mount cleanup`() {
        EngineHandoff.save(filesDir, record.copy(mountTargets = listOf("$posixRoot/proc")))
        var cleaned = false

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { true }, mountCleanup = { cleaned = true; true })
        }

        assertFalse(cleaned)
        assertEquals(record.copy(mountTargets = listOf("$posixRoot/proc")), EngineHandoff.load(filesDir))
    }

    @Test
    fun `reset keeps a legacy handoff even when standard cleanup succeeds`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }.writeText(
            "schema=1\npid=4242\nport=3080\ntoken=abc123\nrootfs=${rootfs.canonicalPath}\n",
        )

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { true })
        }

        assertTrue(EngineHandoff.file(filesDir).exists())
    }

    @Test
    fun `reset clears records only after verified cleanup`() {
        val mounts = listOf("$posixRoot/proc")
        EngineHandoff.save(filesDir, record.copy(mountTargets = mounts))
        EngineHandoff.savePending(filesDir, EngineHandoff.PendingMounts(rootfs.canonicalPath, mounts))
        var cleaned = 0

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { cleaned += 1; false })
        }
        assertTrue(EngineHandoff.file(filesDir).exists())
        assertTrue(EngineHandoff.pendingFile(filesDir).exists())

        EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { cleaned += 1; true })

        assertEquals(3, cleaned)
        assertFalse(EngineHandoff.file(filesDir).exists())
        assertFalse(EngineHandoff.pendingFile(filesDir).exists())
    }

    @Test
    fun `reset rejects a corrupt pending mount ledger`() {
        EngineHandoff.pendingFile(filesDir).apply { parentFile?.mkdirs() }.writeText("truncated")

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { true })
        }

        assertTrue(EngineHandoff.pendingFile(filesDir).exists())
    }

    @Test
    fun `spawned pending identity survives a cold save`() {
        val mounts = listOf("$posixRoot/proc")
        EngineHandoff.savePending(filesDir, EngineHandoff.PendingMounts(rootfs.canonicalPath, mounts))
        EngineHandoff.pendingFile(filesDir).appendText("spawned=true\npid=4242\n")

        val loaded = EngineHandoff.loadPending(filesDir)!!
        EngineHandoff.savePending(filesDir, loaded)

        val saved = EngineHandoff.pendingFile(filesDir).readText()
        assertTrue(saved.contains("spawned=true"))
        assertTrue(saved.contains("pid=4242"))
    }

    @Test
    fun `spawned pending reset refuses cleanup without a process sweep`() {
        val mounts = listOf("$posixRoot/proc")
        EngineHandoff.savePending(filesDir, EngineHandoff.PendingMounts(rootfs.canonicalPath, mounts))
        EngineHandoff.pendingFile(filesDir).appendText("spawned=true\npid=4242\n")
        var cleaned = false

        assertThrows(IllegalStateException::class.java) {
            EngineHandoff.cleanupForReset(filesDir, isAlive = { false }, mountCleanup = { cleaned = true; true })
        }

        assertFalse(cleaned)
        assertTrue(EngineHandoff.pendingFile(filesDir).exists())
    }

    @Test
    fun `saving replaces the previous record atomically`() {
        EngineHandoff.save(filesDir, record)
        val next = record.copy(pid = 5555, token = "def456")
        EngineHandoff.save(filesDir, next)
        assertEquals(next, EngineHandoff.load(filesDir))
        assertFalse("the temp file must not survive", File(EngineHandoff.file(filesDir).path + ".tmp").exists())
    }
}
