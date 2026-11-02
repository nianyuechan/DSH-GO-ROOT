package io.github.ccch1mneyyy.dsh_app.root

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs [TuningCatalog]'s commands as root and reports what actually happened.
 *
 * Every action is read-first: the current value decides whether to write at all, and the
 * value is read again afterwards so the card shows the device's answer rather than the
 * intent. A ROM that silently ignores a write therefore shows as "still off", not as done.
 *
 * All commands are the catalog's own fixed strings. No caller-provided text reaches `su` —
 * the only variable is the fixed package name this app installs under.
 */
object SystemTuning {

    /** What one action ended up as. */
    data class Outcome(
        val id: String,
        /** Already applied before this run, so nothing was written. */
        val alreadyApplied: Boolean,
        /** The write ran, and the read-back agrees. */
        val applied: Boolean,
        /** Raw read-back, trimmed; null when the read itself failed. */
        val observed: String?,
        /** Stable reason code when this run did not end applied. */
        val reason: String?,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "id" to id, "alreadyApplied" to alreadyApplied, "applied" to applied,
            "observed" to observed, "reason" to reason,
        )
    }

    /**
     * Applies every catalog action that is not already applied. [dangerous] actions are
     * skipped unless [allowDangerous], which the UI sets only after an explicit confirmation.
     * Callers are on a worker thread: this blocks for as long as `su` takes.
     */
    fun applyAll(
        allowDangerous: Boolean = false,
        shell: Shell = RootShell,
        observe: (TuningCatalog.Action) -> String? = { shell.read(it.read) },
    ): List<Outcome> = TuningCatalog.all.mapNotNull { action ->
        when {
            action.dangerous && !allowDangerous -> null
            action.apply == null -> null // its flow is applyAccessibility; never a one-liner
            else -> apply(action, shell, observe)
        }
    }

    /** One action: read, write only if needed, read back. */
    fun apply(
        action: TuningCatalog.Action,
        shell: Shell = RootShell,
        observe: (TuningCatalog.Action) -> String? = { shell.read(it.read) },
    ): Outcome {
        val before = observe(action)
        if (before == null) return Outcome(action.id, alreadyApplied = false, applied = false, observed = null, reason = "readFailed")
        if (action.isApplied(before)) return Outcome(action.id, alreadyApplied = true, applied = true, observed = before, reason = null)
        val command = action.apply ?: return Outcome(action.id, false, false, before, "needsExplicitFlow")
        if (!shell.run(command)) return Outcome(action.id, false, false, before, "writeFailed")
        val after = observe(action)
        return if (after != null && action.isApplied(after)) {
            Outcome(action.id, alreadyApplied = false, applied = true, observed = after, reason = null)
        } else {
            Outcome(action.id, alreadyApplied = false, applied = false, observed = after, reason = "notObserved")
        }
    }

    /**
     * Appends this app's service to `enabled_accessibility_services` without touching the
     * others. Never a blind overwrite: the existing list is read first, and an unreadable
     * list is a refusal rather than a guess.
     */
    fun applyAccessibility(
        service: String,
        shell: Shell = RootShell,
    ): Outcome {
        val action = TuningCatalog.accessibilityAutoGrant
        val before = shell.read(action.read)
            ?: return Outcome(action.id, false, false, null, "readFailed")
        val current = before.trim().let { if (it == "null") "" else it }
        val parts = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.contains(service)) return Outcome(action.id, true, true, before, null)
        val updated = (parts + service).joinToString(":")
        if (!shell.run("settings put secure enabled_accessibility_services '${quote(updated)}'")) {
            return Outcome(action.id, false, false, before, "writeFailed")
        }
        val after = shell.read(action.read)
            ?: return Outcome(action.id, false, false, before, "readFailed")
        return if (after.contains(service)) Outcome(action.id, false, true, after, null)
        else Outcome(action.id, false, false, after, "notObserved")
    }

    /** Single quotes only: a value already containing one is refused, never silently mangled. */
    private fun quote(value: String): String {
        require('\'' !in value) { "unexpected quote in a settings value" }
        return value
    }

    /** The shell boundary, so the decision logic above stays testable. */
    interface Shell {
        /** Combined stdout+stderr of [command], or null when su could not be run at all. */
        fun read(command: String): String?

        /** True when [command] exited zero. */
        fun run(command: String): Boolean
    }

    /** The real one: one `su -c` per command, with a watchdog on the read. */
    object RootShell : Shell {
        private const val TIMEOUT_SECONDS = 12L

        override fun read(command: String): String? = execute(command)?.first

        override fun run(command: String): Boolean = execute(command)?.second == true

        private fun execute(command: String): Pair<String, Boolean>? {
            val process = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            } catch (_: Exception) {
                return null
            }
            val latch = CountDownLatch(1)
            val output = StringBuilder()
            thread(isDaemon = true, name = "dsh-tuning") {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            output.append(buffer, 0, read)
                        }
                    }
                }
                latch.countDown()
            }
            val finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return null
            }
            val exit = process.waitFor()
            return output.toString().trim() to (exit == 0)
        }
    }
}
