package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdoptedEngineTest {
    private val rootfs = File("/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime/rootfs")
    private val root = rootfs.canonicalPath
    private val record = EngineHandoff.Record(pid = 4242, port = 3080, token = "abc123", rootfs = root)
    private val uid = 10245

    private fun alive(
        uidValue: Int = uid,
        command: String = "/opt/node/bin/node /usr/local/bin/dsh web",
        cwd: String? = "$root/root",
    ) = AdoptedEngine.Liveness.Alive(uidValue, command, cwd)

    private fun healthy(ok: Boolean = true) = AdoptedEngine.HealthCheck { _, _ -> ok }

    private fun check(
        recordValue: EngineHandoff.Record? = record,
        rootfsValue: File = rootfs,
        liveness: (Int) -> AdoptedEngine.Liveness = { alive() },
        health: AdoptedEngine.HealthCheck = healthy(),
    ) = AdoptedEngine.check(recordValue, rootfsValue, uid, liveness, health)

    @Test
    fun `all four facts true adopts`() {
        val result = check()
        assertTrue(result.adopted)
        assertEquals(record, result.record)
    }

    @Test
    fun `a missing record is not adopted`() {
        val result = check(recordValue = null)
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.NO_RECORD, result.reason)
    }

    @Test
    fun `a record from a different rootfs is rejected`() {
        val result = check(recordValue = record.copy(rootfs = "/some/other/root"))
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.ROOT_CHANGED, result.reason)
    }

    @Test
    fun `a dead pid is rejected`() {
        val result = check(liveness = { AdoptedEngine.Liveness.Absent })
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.GONE, result.reason)
    }

    @Test
    fun `unreadable proc retains the handoff instead of proving an exit`() {
        val result = check(liveness = { AdoptedEngine.Liveness.Unknown })
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.UNREACHABLE, result.reason)
        assertEquals(record, result.record)
    }

    @Test
    fun `another app's process is rejected`() {
        val result = check(liveness = { alive(uidValue = uid + 1) })
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.NOT_OURS, result.reason)
    }

    @Test
    fun `an owned process that is not the engine is rejected`() {
        val result = check(liveness = { alive(command = "/system/bin/sh -c sleep 60") })
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.NOT_THE_ENGINE, result.reason)
    }

    @Test
    fun `the engine must run with a cwd inside the rootfs`() {
        val outside = check(liveness = { alive(cwd = "/data/local/tmp") })
        assertFalse(outside.adopted)
        assertEquals(AdoptedEngine.OUTSIDE_ROOTFS, outside.reason)
        // An unreadable cwd is not evidence of being inside.
        val unknown = check(liveness = { alive(cwd = null) })
        assertFalse(unknown.adopted)
        assertEquals(AdoptedEngine.OUTSIDE_ROOTFS, unknown.reason)
    }

    @Test
    fun `a port that does not answer with the token is rejected`() {
        val result = check(health = healthy(ok = false))
        assertFalse(result.adopted)
        assertEquals(AdoptedEngine.UNREACHABLE, result.reason)
    }

    @Test
    fun `an unreachable live engine retains its identity to block a duplicate start`() {
        val result = check(health = healthy(ok = false))
        assertEquals(record, result.record)
    }

    @Test
    fun `adopted monitor waits through TCP failures until the engine identity disappears`() {
        val connections = ArrayDeque(listOf(false, false, false, false))
        val sightings = ArrayDeque<AdoptedEngine.Liveness>(listOf(alive(), alive(), AdoptedEngine.Liveness.Absent))
        val sleeps = mutableListOf<Long>()

        AdoptedEngineMonitor(
            record = record,
            rootfs = rootfs,
            myUid = uid,
            liveness = { sightings.removeFirst() },
            connect = { connections.removeFirst() },
            sleep = { sleeps += it },
        ).run()

        assertTrue("every disconnect was checked", connections.isEmpty())
        assertTrue("the monitor returned only for the final absent pid", sightings.isEmpty())
        assertEquals(listOf(1_000L, 1_000L, 2_000L, 2_000L), sleeps)
    }

    @Test
    fun `a live handoff blocks every fresh start even when this launch cannot adopt it`() {
        assertEquals(
            AdoptionAction.BLOCK,
            adoptionAction(check(health = healthy(ok = false)), allowAdopt = true),
        )
        assertEquals(AdoptionAction.BLOCK, adoptionAction(check(), allowAdopt = false))
        assertEquals(
            AdoptionAction.START_FRESH,
            adoptionAction(check(liveness = { AdoptedEngine.Liveness.Absent }), allowAdopt = false),
        )
    }

    @Test
    fun `the health check is asked for the recorded port and token, not a fresh guess`() {
        var asked: Pair<Int, String>? = null
        check(health = AdoptedEngine.HealthCheck { port, token ->
            asked = port to token
            true
        })
        assertEquals(3080 to "abc123", asked)
    }

    @Test
    fun `a dead engine is not probed at all`() {
        var probed = false
        val result = check(
            liveness = { AdoptedEngine.Liveness.Absent },
            health = AdoptedEngine.HealthCheck { _, _ -> probed = true; true },
        )
        assertFalse(result.adopted)
        assertFalse("health must not run without a live pid", probed)
    }
}
