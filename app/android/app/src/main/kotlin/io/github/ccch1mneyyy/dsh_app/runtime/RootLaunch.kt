package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File

/**
 * Root-chroot launch planner, the root twin of [DirectLaunch]: the engine runs in the
 * real filesystem at full speed — no PRoot translation, no loader/library hacks, the
 * rootfs's own `/usr/local/bin/dsh` entry and pidfile semantics untouched.
 *
 * Root only mounts and drops: proc/sys/dev and the composed binds go up before the
 * chroot, then the engine is exec'd as a descendant of the mounting shell — whatever
 * mount namespace su provides, the engine inherits it (verified on KernelSU 3.3.0:
 * the su client execs into the command shell). The engine runs as the app's own uid,
 * so every ownership, stop and cleanup path in [DshProcess] works unchanged, and the
 * rootfs (extracted by this app) already belongs to that uid. Guest env pairs stay
 * guest-path-based exactly as PRoot received them; DSH_GO_RUNTIME_ROOT resolves inside
 * the chroot through the rootfs's self-bind. Pure JVM and testable, like [DirectLaunch].
 */
object RootLaunch {

    data class Plan(val script: String, val cwd: File)

    /**
     * The saved launch method, mirroring [DirectLaunch.Preference]. [failedRuntime]
     * pins the runtime version a root launch last failed with, so the fallback to
     * proot survives restarts until the runtime changes.
     */
    data class Preference(val saved: Boolean?, val available: Boolean, val failedRuntime: String?, val runtime: String?) {
        /** Off until the user turns it on, and only while su is actually available. */
        val requested: Boolean get() = (saved ?: false) && available

        /** Compatible until the runtime changes or the user turns Root on again. */
        val failed: Boolean get() = failedRuntime != null && failedRuntime == runtime

        /** What the switch shows: whether the next normal start tries the chroot. */
        val enabled: Boolean get() = requested && !failed
    }

    /**
     * Consumes the one fully expanded proot argv/env (same contract as
     * [DirectLaunch.prepare]: overlays, performance defaults and caller settings are
     * already composed) and returns the launcher script for [DshProcess] to write and
     * spawn through `su -c "exec sh '<script>'"`. [appGids] must carry the app's
     * supplementary groups — AID_INET (3003) lives there; without it the chrooted
     * engine has no network.
     */
    fun prepare(rootfs: File, argv: List<String>, appUid: Int, appGids: IntArray): Plan {
        val root = rootfs.canonicalFile
        val rootPath = root.path
        val envAt = argv.indexOf("/usr/bin/env")
        require(envAt > 0 && argv.getOrNull(envAt + 1) == "-i") { "root engine: missing guest environment" }
        val guest = linkedMapOf<String, String>()
        var at = envAt + 2
        while (at < argv.size && argv[at].contains('=')) {
            val pair = argv[at++].split('=', limit = 2)
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(pair[0])) { "root engine: invalid environment name" }
            guest[pair[0]] = pair[1]
        }
        require(guest.isNotEmpty()) { "root engine: empty guest environment" }
        require(argv.drop(at).take(2) == listOf("/bin/sh", "-c") && argv.size == at + 3) { "root engine: unsupported guest command" }
        DirectLaunch.webArguments(argv.last()) // reject an unknown future shell template early

        val mounts = DirectLaunch.mounts(argv.take(envAt), rootPath)
        // Bind targets live under the rootfs before the chroot happens.
        val preChroot = { guest: String -> rootPath + guest }
        val q = DirectLaunch::quote

        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("set -e")
            // Lazy cleanup of mounts a previous run left behind (idempotent): a shared
            // namespace keeps them visible after an unclean exit.
            append("umount -l")
            append(' '); append(q("$rootPath/proc"))
            append(' '); append(q("$rootPath/sys"))
            append(' '); append(q("$rootPath/dev"))
            append(' '); append(q("$rootPath$rootPath"))
            for (m in mounts) { append(' '); append(q(preChroot(m.guest))) }
            appendLine(" >/dev/null 2>&1 || true")
            appendLine("mount -t proc proc ${q("$rootPath/proc")}")
            appendLine("mount --rbind /dev ${q("$rootPath/dev")}")
            appendLine("mount --rbind /sys ${q("$rootPath/sys")}")
            for (m in mounts) {
                appendLine("mkdir -p ${q(preChroot(m.guest))}")
                appendLine("mount --bind ${q(m.host)} ${q(preChroot(m.guest))}")
            }
            // The runtime root mounted at its own host path keeps DSH_GO_RUNTIME_ROOT
            // and same-path expectations valid inside the chroot.
            appendLine("mkdir -p ${q("$rootPath$rootPath")}")
            appendLine("mount --bind ${q(rootPath)} ${q("$rootPath$rootPath")}")
            append("exec chroot ${q(rootPath)} /usr/bin/setpriv --reuid=$appUid --regid=$appUid ")
            if (appGids.isEmpty()) append("--clear-groups ")
            else append("--groups=${appGids.joinToString(",")} ")
            append("exec /usr/bin/env -i")
            for ((key, value) in guest) { append(' '); append(q("$key=$value")) }
            append(" /bin/sh -c ${q(argv.last())}")
            appendLine()
        }
        return Plan(script, File(root, "root"))
    }
}
