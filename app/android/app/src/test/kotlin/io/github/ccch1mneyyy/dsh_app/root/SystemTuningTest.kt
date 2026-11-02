package io.github.ccch1mneyyy.dsh_app.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTuningTest {

    /** A shell the test scripts: [read] answers from [state], [run] counts and can fail. */
    private class FakeShell(
        private val state: Map<String, String> = emptyMap(),
        private val readFails: Set<String> = emptySet(),
        private val writeFails: Boolean = false,
    ) : SystemTuning.Shell {
        val commands = mutableListOf<String>()

        override fun read(command: String): String? {
            commands += "read:$command"
            if (readFails.any { command.contains(it) }) return null
            return state.entries.firstOrNull { command.contains(it.key) }?.value ?: "null"
        }

        override fun run(command: String): Boolean {
            commands += "run:$command"
            return !writeFails
        }
    }

    @Test
    fun `an already applied action is not written again`() {
        val shell = FakeShell()
        val result = SystemTuning.apply(
            TuningCatalog.phantom,
            shell,
            observe = { "0\n2147483647" },
        )
        assertTrue(result.alreadyApplied)
        assertTrue(result.applied)
        assertNull(result.reason)
        assertTrue("no write should run", shell.commands.isEmpty())
    }

    @Test
    fun `an unapplied action is written and read back`() {
        var reads = 0
        var writes = 0
        val shell = object : SystemTuning.Shell {
            override fun read(command: String): String? = "unused"
            override fun run(command: String): Boolean { writes++; return true }
        }
        val result = SystemTuning.apply(
            TuningCatalog.phantom,
            shell = shell,
            observe = { if (reads++ == 0) "1\n1000" else "0\n2147483647" },
        )
        assertFalse(result.alreadyApplied)
        assertTrue(result.applied)
        assertNull(result.reason)
        assertEquals("exactly one write", 1, writes)
        assertEquals("one read before, one after", 2, reads)
    }

    @Test
    fun `a write that the ROM ignores is reported as not observed`() {
        val result = SystemTuning.apply(
            TuningCatalog.phantom,
            shell = object : SystemTuning.Shell {
                override fun read(command: String): String? = "1\n1000"
                override fun run(command: String): Boolean = true
            },
            observe = { "1\n1000" },
        )
        assertFalse(result.applied)
        assertEquals("notObserved", result.reason)
    }

    @Test
    fun `a failed read reports readFailed and writes nothing`() {
        val shell = FakeShell(readFails = setOf("phantom"))
        val result = SystemTuning.apply(TuningCatalog.phantom, shell)
        assertFalse(result.applied)
        assertEquals("readFailed", result.reason)
        assertNull(result.observed)
        assertTrue("no write after an unreadable state", shell.commands.none { it.startsWith("run:") })
    }

    @Test
    fun `a failed write reports writeFailed`() {
        val shell = FakeShell(writeFails = true)
        val result = SystemTuning.apply(TuningCatalog.standbyBucket, shell, observe = { "5" })
        assertFalse(result.applied)
        assertEquals("writeFailed", result.reason)
    }

    @Test
    fun `dangerous actions need an explicit opt-in`() {
        val shell = FakeShell()
        val skipped = SystemTuning.applyAll(allowDangerous = false, shell = shell) { "0\n2147483647" }
        assertFalse(skipped.any { it.id == TuningCatalog.accessibilityAutoGrant.id })

        val included = SystemTuning.applyAll(allowDangerous = true, shell = shell) { "0\n2147483647" }
        // It still has no one-liner apply, so it is never run by applyAll even when allowed.
        assertFalse(included.any { it.id == TuningCatalog.accessibilityAutoGrant.id })
    }

    @Test
    fun `applyAll covers the one-liner actions in catalog order`() {
        val shell = FakeShell()
        val results = SystemTuning.applyAll(shell = shell) { "0\n2147483647" }
        val ids = results.map { it.id }
        assertEquals(
            listOf(
                TuningCatalog.phantom.id,
                TuningCatalog.batteryExempt.id,
                TuningCatalog.standbyBucket.id,
                TuningCatalog.miuiPowerKeeper.id,
            ),
            ids,
        )
    }

    // ---- accessibility ----------------------------------------------------

    @Test
    fun `accessibility appends without disturbing the other services`() {
        var current = "com.example/.Reader"
        val shell = object : SystemTuning.Shell {
            override fun read(command: String): String? = current
            override fun run(command: String): Boolean {
                current += ":io.github.ccch1mneyyy.dsh_app/.PhoneA11yService"
                return true
            }
        }
        val result = SystemTuning.applyAccessibility(
            "io.github.ccch1mneyyy.dsh_app/.PhoneA11yService", shell,
        )
        assertTrue(result.applied)
        assertFalse(result.alreadyApplied)
        assertTrue("the other service must survive", current.startsWith("com.example/.Reader:"))
    }

    @Test
    fun `accessibility does nothing when the service is already listed`() {
        val shell = object : SystemTuning.Shell {
            var wrote = false
            override fun read(command: String): String? = "com.example/.Reader:io.github.ccch1mneyyy.dsh_app/.PhoneA11yService"
            override fun run(command: String): Boolean { wrote = true; return true }
        }
        val result = SystemTuning.applyAccessibility("io.github.ccch1mneyyy.dsh_app/.PhoneA11yService", shell)
        assertTrue(result.alreadyApplied)
        assertFalse("must not rewrite the same list", shell.wrote)
    }

    @Test
    fun `accessibility refuses when the current list cannot be read`() {
        val shell = object : SystemTuning.Shell {
            override fun read(command: String): String? = null
            override fun run(command: String): Boolean = error("must not write blind")
        }
        val result = SystemTuning.applyAccessibility("io.github.ccch1mneyyy.dsh_app/.PhoneA11yService", shell)
        assertFalse(result.applied)
        assertEquals("readFailed", result.reason)
    }

    @Test
    fun `an empty accessibility list is written without a leading separator`() {
        var written: String? = null
        val shell = object : SystemTuning.Shell {
            override fun read(command: String): String? = written ?: "null"
            override fun run(command: String): Boolean {
                written = "io.github.ccch1mneyyy.dsh_app/.PhoneA11yService"
                return true
            }
        }
        SystemTuning.applyAccessibility("io.github.ccch1mneyyy.dsh_app/.PhoneA11yService", shell)
        assertEquals("io.github.ccch1mneyyy.dsh_app/.PhoneA11yService", written)
    }
}
