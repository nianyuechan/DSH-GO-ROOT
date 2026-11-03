package io.github.ccch1mneyyy.dsh_app.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningCatalogTest {

    @Test
    fun `every action has a stable id and a read command`() {
        val ids = TuningCatalog.all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue(TuningCatalog.all.all { it.read.isNotBlank() })
    }

    @Test
    fun `lookup by id finds every action`() {
        for (action in TuningCatalog.all) assertEquals(action, TuningCatalog.byId(action.id))
        assertNull(TuningCatalog.byId("noSuchAction"))
    }

    // ---- phantom ----------------------------------------------------------

    @Test
    fun `phantom counts as applied only when the monitor is off and the ceiling is raised`() {
        val phantom = TuningCatalog.phantom
        assertTrue(phantom.isApplied("0\n2147483647"))
        assertTrue(phantom.isApplied("false\n2147483647"))
        // Monitor disabled but ceiling still default: the child processes stay capped.
        assertFalse(phantom.isApplied("0\n1000"))
        // Ceiling raised but the monitor still sweeping.
        assertFalse(phantom.isApplied("1\n2147483647"))
        assertFalse(phantom.isApplied("null\nnull"))
    }

    // ---- battery ----------------------------------------------------------

    @Test
    fun `battery exemption reads the count of matching lines`() {
        val battery = TuningCatalog.batteryExempt
        assertTrue(battery.isApplied("1"))
        assertTrue(battery.isApplied("3"))
        assertFalse(battery.isApplied("0"))
        assertFalse(battery.isApplied(""))
    }

    // ---- standby ----------------------------------------------------------

    @Test
    fun `standby is applied only at the active bucket`() {
        val standby = TuningCatalog.standbyBucket
        // 10 is STAND_BY_BUCKET_ACTIVE; 5, 20, 30, 40 and 45 are the lower buckets.
        assertTrue(standby.isApplied("10"))
        assertFalse(standby.isApplied("5"))
        assertFalse(standby.isApplied("45"))
        assertFalse(standby.isApplied("error: no such package"))
        // Not persistent: re-apply on every launch, so it never claims to have survived.
        assertFalse(standby.persistent)
    }

    @Test
    fun `standby is read and written for the current user on HyperOS`() {
        val standby = TuningCatalog.standbyBucket
        assertTrue("read must target user 0", standby.read.contains("--user 0"))
        assertTrue("write must target user 0", standby.apply!!.contains("--user 0"))
        assertTrue(standby.apply!!.endsWith("io.github.ccch1mneyyy.dsh_app active"))
    }

    // ---- MIUI -------------------------------------------------------------

    @Test
    fun `miui power keeper reads its own setting`() {
        val miui = TuningCatalog.miuiPowerKeeper
        assertTrue(miui.isApplied("1"))
        assertFalse(miui.isApplied("0"))
        assertFalse(miui.isApplied("null"))
    }

    // ---- accessibility ----------------------------------------------------

    @Test
    fun `accessibility is dangerous and has no one-liner apply`() {
        val a11y = TuningCatalog.accessibilityAutoGrant
        assertTrue("must never be preselected", a11y.dangerous)
        assertNull("appending needs the current list, not a fixed command", a11y.apply)
        assertTrue(a11y.isApplied("com.example/.Service:io.github.ccch1mneyyy.dsh_app/.A11y"))
        assertFalse(a11y.isApplied("com.example/.Service"))
        assertFalse(a11y.isApplied("null"))
    }

    @Test
    fun `no other action is marked dangerous`() {
        val others = TuningCatalog.all.filter { it.id != TuningCatalog.accessibilityAutoGrant.id }
        assertTrue(others.none { it.dangerous })
    }

    // ---- offered / status -------------------------------------------------

    @Test
    fun `an action whose read fails is not offered`() {
        // A ROM without the MIUI setting cannot be probed, so it is dropped from the list.
        val offered = TuningCatalog.offered { command ->
            if (command.contains("power_saver_app_manager")) null else "0"
        }
        assertFalse(offered.any { it.id == TuningCatalog.miuiPowerKeeper.id })
        assertTrue(offered.any { it.id == TuningCatalog.phantom.id })
    }

    @Test
    fun `a null MIUI setting is not offered but an empty accessibility list is`() {
        val offered = TuningCatalog.offered { command ->
            if (command.startsWith("settings get ")) "null" else "0"
        }
        assertFalse(offered.any { it.id == TuningCatalog.miuiPowerKeeper.id })
        assertTrue(offered.any { it.id == TuningCatalog.accessibilityAutoGrant.id })
        assertTrue(offered.any { it.id == TuningCatalog.phantom.id })
    }

    @Test
    fun `a blank MIUI setting is not offered but a blank accessibility list is`() {
        for (blank in listOf("", " \n\t")) {
            val offered = TuningCatalog.offered { command ->
                when {
                    command.startsWith("settings get ") && ';' !in command -> blank
                    command == TuningCatalog.phantom.read -> blank
                    else -> "0"
                }
            }
            assertFalse(offered.any { it.id == TuningCatalog.miuiPowerKeeper.id })
            assertTrue(offered.any { it.id == TuningCatalog.accessibilityAutoGrant.id })
            assertTrue("compound reads are not missing settings", offered.any { it.id == TuningCatalog.phantom.id })
        }
    }

    @Test
    fun `every action is offered when the probe answers`() {
        val offered = TuningCatalog.offered { "0" }
        assertEquals(TuningCatalog.all.size, offered.size)
    }

    @Test
    fun `an unreadable status stays unknown rather than reading as off`() {
        // A vendor ROM that blocks the read must not look like a clean, untuned device.
        assertNull(TuningCatalog.status(TuningCatalog.phantom, null))
        assertEquals(false, TuningCatalog.status(TuningCatalog.phantom, "1\n1000"))
        assertEquals(true, TuningCatalog.status(TuningCatalog.phantom, "0\n2147483647"))
    }
}
