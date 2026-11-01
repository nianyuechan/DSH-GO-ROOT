package io.github.ccch1mneyyy.dsh_app.runtime

/** One launch attempt, retained after exit but replaced before the next attempt. No preferences. */
class LaunchDiagnostics {
    @Volatile var selectedMode: String? = null
        private set
    @Volatile var fallbackReason: String? = null
        private set
    @Volatile var spawned = false
        private set
    @Volatile var failureReason: String? = null
        private set

    fun selected(direct: Boolean, fallback: String?) = selected(if (direct) DIRECT else PROOT, fallback)

    /** [mode] is one of [PROOT], [DIRECT] or [CHROOT]. */
    fun selected(mode: String, fallback: String?) {
        selectedMode = mode
        fallbackReason = fallback
    }

    fun spawned() { spawned = true }
    fun failed(reason: String) { failureReason = reason }
    fun currentMode(alive: Boolean): String? = if (spawned && alive) selectedMode else null

    companion object {
        const val PROOT = "proot"
        const val DIRECT = "direct"
        /** Root mounts and drops, then the engine runs in the real filesystem (see [RootLaunch]). */
        const val CHROOT = "chroot"
    }
}
