package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File

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

    data class Record(val pid: Int, val port: Int, val token: String, val rootfs: String) {
        fun toMap(): Map<String, Any?> = mapOf(
            "pid" to pid, "port" to port, "token" to token, "rootfs" to rootfs,
        )
    }

    fun file(filesDir: File): File = File(filesDir, "runtime/engine-handoff")

    fun save(filesDir: File, record: Record) {
        val text = buildString {
            appendLine("schema=$SCHEMA")
            appendLine("pid=${record.pid}")
            appendLine("port=${record.port}")
            appendLine("token=${record.token}")
            appendLine("rootfs=${record.rootfs}")
        }
        val target = file(filesDir)
        target.parentFile?.mkdirs()
        // The engine may have died between reading the pid and writing this file: a
        // half-written record would point adoption at nothing, so replace atomically.
        val temp = File(target.path + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("cannot write engine handoff")
        }
    }

    /** Null when absent, unreadable, or written by a different shape. */
    fun load(filesDir: File): Record? = try {
        val values = file(filesDir).readLines()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }
            .toMap()
        val pid = values["pid"]?.toIntOrNull()
        val port = values["port"]?.toIntOrNull()
        val token = values["token"]
        val rootfs = values["rootfs"]
        when {
            values["schema"]?.toIntOrNull() != SCHEMA -> null
            pid == null || pid <= 0 -> null
            port == null || port !in 1..65535 -> null
            token.isNullOrEmpty() || rootfs.isNullOrEmpty() -> null
            // A newline cannot survive the line format, so a root path containing one was
            // never written by us; refuse rather than adopt against a truncated path.
            else -> Record(pid, port, token, rootfs)
        }
    } catch (_: Exception) {
        null
    }

    fun clear(filesDir: File) {
        file(filesDir).delete()
    }
}
