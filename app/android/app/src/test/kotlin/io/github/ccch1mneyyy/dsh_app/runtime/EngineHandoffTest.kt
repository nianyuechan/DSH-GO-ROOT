package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineHandoffTest {
    private val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")
    private val filesDir: File = kotlin.io.path.createTempDirectory("handoff").toFile().apply { deleteOnExit() }

    private val record = EngineHandoff.Record(
        pid = 4242, port = 3080, token = "abc123", rootfs = rootfs.canonicalPath,
    )

    @Test
    fun `a saved record loads back unchanged`() {
        EngineHandoff.save(filesDir, record)
        assertEquals(record, EngineHandoff.load(filesDir))
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
    fun `an impossible port is rejected`() {
        EngineHandoff.file(filesDir).apply { parentFile?.mkdirs() }
            .writeText("schema=1\npid=1\nport=70000\ntoken=t\nrootfs=/r\n")
        assertNull(EngineHandoff.load(filesDir))
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
