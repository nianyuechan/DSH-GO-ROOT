package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The identity of a root-chroot engine that outlives this app process: the pid from the
 * rootfs's own pid file, the loopback port, and the one-time web token the engine printed.
 *
 * The token only ever appears once on stdout, so adoption has no way to learn it later.
 * Writing it here at ready time is what makes [AdoptedEngine] possible at all.
 *
 * Stored in filesDir — app-private, never in the rootfs — so a shared rootfs or a runtime
 * reinstall cannot leak it, and nothing else on the device can read it.
 *
 * Plain `key=value` lines rather than JSON: the JVM unit tests run against Android's
 * stubbed `org.json`, and a format this small does not need a parser that cannot run there.
 */
object EngineHandoff {
    /** Bumped when the stored shape changes; an old file is discarded rather than guessed at. */
    private const val SCHEMA = 1

    data class Record(
        val pid: Int,
        val port: Int,
        val token: String,
        val rootfs: String,
        val mountTargets: List<String> = emptyList(),
        val mountTargetsComplete: Boolean = true,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "pid" to pid, "port" to port, "token" to token, "rootfs" to rootfs,
            "mountTargets" to mountTargets, "mountTargetsComplete" to mountTargetsComplete,
        )
    }

    /** Mounts captured before root spawn; this is cleanup state, never an adoptable engine. */
    data class PendingMounts(
        val rootfs: String,
        val mountTargets: List<String>,
        val spawned: Boolean = false,
        val pid: Int? = null,
    )

    fun file(filesDir: File): File = File(filesDir, "runtime/engine-handoff")

    fun pendingFile(filesDir: File): File = File(filesDir, "runtime/engine-mounts.pending")

    fun save(filesDir: File, record: Record) {
        val text = buildString {
            appendLine("schema=$SCHEMA")
            appendLine("pid=${record.pid}")
            appendLine("port=${record.port}")
            appendLine("token=${record.token}")
            appendLine("rootfs=${record.rootfs}")
            appendLine("mounts=${record.mountTargets.joinToString(",") { encodeMount(it) }}")
        }
        writeAtomically(file(filesDir), text, "cannot write engine handoff")
    }

    fun savePending(filesDir: File, pending: PendingMounts) {
        val text = buildString {
            appendLine("schema=$SCHEMA")
            appendLine("rootfs=${pending.rootfs}")
            appendLine("mounts=${pending.mountTargets.joinToString(",") { encodeMount(it) }}")
            appendLine("spawned=${pending.spawned}")
            pending.pid?.let { appendLine("pid=$it") }
        }
        writeAtomically(pendingFile(filesDir), text, "cannot write pending engine mounts")
    }

    /** Null when absent, unreadable, or written by a different shape. */
    fun load(filesDir: File): Record? = try {
        val values = readValues(file(filesDir))
        val pid = values["pid"]?.toIntOrNull()
        val port = values["port"]?.toIntOrNull()
        val token = values["token"]
        val rootfs = values["rootfs"]
        val mounts = values["mounts"]
        val mountTargets = mounts?.let(::decodeMounts)
        when {
            values["schema"]?.toIntOrNull() != SCHEMA -> null
            pid == null || pid <= 0 -> null
            port == null || port !in 1..65535 -> null
            token.isNullOrEmpty() || rootfs.isNullOrEmpty() -> null
            mountTargets?.isEmpty() == true -> null
            // A newline cannot survive the line format, so a root path containing one was
            // never written by us; refuse rather than adopt against a truncated path.
            else -> Record(
                pid, port, token, rootfs,
                mountTargets = mountTargets ?: RootLaunch.standardMountTargets(File(rootfs)),
                mountTargetsComplete = mounts != null,
            )
        }
    } catch (_: Exception) {
        null
    }

    fun loadPending(filesDir: File): PendingMounts? = try {
        val values = readValues(pendingFile(filesDir))
        val rootfs = values["rootfs"]
        val mounts = values["mounts"]
        val mountTargets = mounts?.let(::decodeMounts)
        val spawned = values["spawned"]?.toBooleanStrictOrNull()
        val pid = values["pid"]?.toIntOrNull()
        when {
            values["schema"]?.toIntOrNull() != SCHEMA -> null
            rootfs.isNullOrEmpty() || mountTargets.isNullOrEmpty() -> null
            values["spawned"] != null && spawned == null -> null
            values["pid"] != null && (pid == null || pid <= 0) -> null
            // A legacy pending file has no spawn marker, so it may already have a live engine.
            else -> PendingMounts(rootfs, mountTargets, spawned ?: true, pid)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Reset fails closed: a present but unreadable record, a live engine, incomplete legacy
     * mount information, or failed unmount verification keeps every record for another retry.
     */
    fun cleanupForReset(
        filesDir: File,
        isAlive: (Record) -> Boolean,
        mountCleanup: (List<String>) -> Boolean,
        reapPending: (PendingMounts) -> Boolean = { false },
    ) {
        val handoffFile = file(filesDir)
        val pendingFile = pendingFile(filesDir)
        val record = if (handoffFile.exists()) {
            load(filesDir) ?: throw IllegalStateException("engine handoff is unreadable; runtime was not deleted")
        } else null
        val pending = if (pendingFile.exists()) {
            loadPending(filesDir) ?: throw IllegalStateException("pending engine mounts are unreadable; runtime was not deleted")
        } else null
        if (record != null) {
            check(!isAlive(record)) { "root engine is still running; stop it before reset" }
            check(mountCleanup(record.mountTargets)) { "root mount cleanup failed; runtime was not deleted" }
            check(record.mountTargetsComplete) { "legacy engine handoff has incomplete mounts; runtime was not deleted" }
        }
        if (pending != null) {
            if (pending.spawned) {
                check(reapPending(pending)) { "pending root process cleanup failed; runtime was not deleted" }
            }
            check(mountCleanup(pending.mountTargets)) { "pending root mount cleanup failed; runtime was not deleted" }
        }
        if (record != null) clear(filesDir)
        if (pending != null) clearPending(filesDir)
    }

    private fun readValues(source: File): Map<String, String> = source.readLines()
        .mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
        }
        .toMap()

    private fun writeAtomically(target: File, text: String, error: String) {
        target.parentFile?.mkdirs()
        // The engine may have died between reading the pid and writing this file: a
        // half-written record would point adoption at nothing, so replace atomically.
        val temp = File(target.path + ".tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IllegalStateException(error, e)
        }
    }

    private fun encodeMount(path: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

    private fun decodeMounts(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        return value.split(',').map { encoded ->
            val path = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            require(path.startsWith('/') && path.none { it == '\u0000' || it == '\n' || it == '\r' })
            path
        }
    }

    fun clear(filesDir: File) {
        file(filesDir).delete()
    }

    fun clearPending(filesDir: File) {
        pendingFile(filesDir).delete()
    }
}
