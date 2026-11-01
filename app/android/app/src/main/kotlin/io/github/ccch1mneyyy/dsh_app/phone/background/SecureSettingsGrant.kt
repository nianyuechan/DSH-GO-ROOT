package io.github.ccch1mneyyy.dsh_app.phone.background

/**
 * WRITE_SECURE_SETTINGS for 自动恢复无障碍 ([io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yKeeper]):
 * which one-tap way to it the shell-uid engine offers right now, and the command for granting it
 * by hand (its `pm grant` part also runs as is in a root shell).
 */
object SecureSettingsGrant {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    // `a11yKeeper.path` in the `dsh/phone` status.
    const val GRANTED = "granted"

    /** Root is granted: the tap connects and grants. */
    const val ROOT = "root"

    /** Shizuku runs and has let us in: the tap grants. */
    const val SHIZUKU = "shizuku"

    /** Self-ADB is paired, and wireless debugging isn't known to be off: the tap connects and grants. */
    const val ADB = "adb"

    /** Shizuku runs but hasn't let us in: the tap asks it first, then grants. */
    const val SHIZUKU_PERMISSION = "shizukuPermission"

    /** Neither is set up (不打扰模式's page sets up all three). */
    const val SETUP = "setup"

    fun path(granted: Boolean, root: RootStatus, shizuku: ShizukuStatus, adb: AdbStatus): String = when {
        granted -> GRANTED
        root.granted -> ROOT
        shizuku.running && shizuku.granted -> SHIZUKU
        adb.paired && adb.wirelessDebugging != false -> ADB
        shizuku.running -> SHIZUKU_PERMISSION
        else -> SETUP
    }

    fun command(packageName: String): String = "adb shell pm grant $packageName $PERMISSION"
}
