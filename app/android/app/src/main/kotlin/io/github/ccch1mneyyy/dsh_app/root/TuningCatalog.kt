package io.github.ccch1mneyyy.dsh_app.root

/**
 * The one-off device tuning that makes a root engine survive on aggressive ROMs.
 *
 * Every action is described as data — a read command, a write command, and the values that
 * make it already-done — so the whole decision can be tested without a device. Nothing here
 * runs a shell; [SystemTuning] does, and it runs exactly these strings.
 *
 * Two rules the list encodes:
 *
 *  * **Idempotent and read-first.** Applying an action twice must be harmless, and the
 *    "already applied" answer is read back rather than remembered, because a vendor ROM can
 *    reset a value on its own (MIUI does this to `device_config`).
 *  * **Nothing that changes the device's security posture is on by default.** The
 *    accessibility auto-grant is listed so the UI can offer it, but [Action.dangerous].
 */
object TuningCatalog {

    /** One knob. [read] prints the current value; [apply] changes it. */
    data class Action(
        val id: String,
        /** Human label shown in the settings card. */
        val title: String,
        val detail: String,
        /** Expected to survive reboots without being re-applied. */
        val persistent: Boolean,
        /** Changes what the device allows other apps to do; never preselected. */
        val dangerous: Boolean,
        /** Prints the current value; the answer decides [applied]. */
        val read: String,
        /** The exact command that applies the action; null when this action needs its own flow. */
        val apply: String?,
        /** True when [read]'s output means "already applied". */
        val isApplied: (String) -> Boolean,
    )

    /**
     * Phantom-process killers terminate the engine's node children in the background. This is
     * the Termux community's standard recipe: disable the monitor, raise the ceiling, and stop
     * the platform from syncing the config back.
     */
    val phantom = Action(
        id = "phantom",
        title = "关闭幻影进程杀手",
        detail = "阻止系统在后台杀掉引擎的 node 子进程。需要重启后仍保持生效。",
        persistent = true,
        dangerous = false,
        read = "settings get global settings_enable_monitor_phantom_procs; " +
            "device_config get activity_manager max_phantom_processes",
        apply = "device_config set_sync_disabled_for_tests persistent; " +
            "device_config put activity_manager max_phantom_processes 2147483647; " +
            "settings put global settings_enable_monitor_phantom_procs false",
        isApplied = { output ->
            val lines = output.lines().map { it.trim() }
            lines.any { it == "0" || it.equals("false", ignoreCase = true) } &&
                lines.any { it.toLongOrNull() == Int.MAX_VALUE.toLong() }
        },
    )

    /** Removes the app from Doze's background restrictions without a trip through vendor settings. */
    val batteryExempt = Action(
        id = "batteryExempt",
        title = "电池白名单",
        detail = "让系统不把 DSH Go 当作可随时回收的后台应用。",
        persistent = true,
        dangerous = false,
        read = "dumpsys deviceidle whitelist | grep -c io.github.ccch1mneyyy.dsh_app",
        apply = "dumpsys deviceidle whitelist +io.github.ccch1mneyyy.dsh_app",
        isApplied = { output -> output.lines().any { it.trim().toIntOrNull()?.let { n -> n > 0 } == true } },
    )

    /** Keeps the app out of the restrained standby bucket that throttles background work. */
    val standbyBucket = Action(
        id = "standbyBucket",
        title = "待机桶：活跃",
        detail = "避免限制型 ROM 把应用降速到受限待机桶。",
        persistent = false,
        dangerous = false,
        // HyperOS routes `am` per user; without --user 0 the write can be ignored while
        // the readback still shows the old bucket (5). Read and write use the same user.
        read = "am get-standby-bucket --user 0 io.github.ccch1mneyyy.dsh_app",
        apply = "am set-standby-bucket --user 0 io.github.ccch1mneyyy.dsh_app active",
        isApplied = { output -> output.lines().any { it.trim().toIntOrNull() == 10 } },
    )

    /**
     * Xiaomi's own power keeper, which ignores the AOSP battery whitelist. Written only when
     * the ROM actually exposes the setting, so the read is part of the approval.
     */
    val miuiPowerKeeper = Action(
        id = "miuiPowerKeeper",
        title = "MIUI 省电豁免",
        detail = "小米/红米上单独的非 AOSP 后台限制，按 ROM 探测后写入。",
        persistent = true,
        dangerous = false,
        read = "settings get system power_saver_app_manager",
        apply = "settings put system power_saver_app_manager 1",
        isApplied = { output -> output.lines().any { it.trim() == "1" } },
    )

    /**
     * Not preselected anywhere in the UI: rewriting `enabled_accessibility_services` changes
     * what every accessibility service on the device can read, so it is opt-in per install.
     *
     * No [Action.apply] on purpose. Appending to that setting must never overwrite the other
     * services a person uses, and getting that right needs the current list — so it goes
     * through [SystemTuning.applyAccessibility] rather than this catalog's one-liner path.
     */
    val accessibilityAutoGrant = Action(
        id = "accessibilityAuto",
        title = "自动恢复无障碍服务",
        detail = "允许应用在服务被系统关闭后自行恢复。会改变设备的安全姿态，默认不勾选。",
        persistent = true,
        dangerous = true,
        read = "settings get secure enabled_accessibility_services",
        apply = null,
        isApplied = { output -> output.contains("io.github.ccch1mneyyy.dsh_app") },
    )

    /** In display order. [all] is what the settings card renders. */
    val all: List<Action> = listOf(phantom, batteryExempt, standbyBucket, miuiPowerKeeper, accessibilityAutoGrant)

    fun byId(id: String): Action? = all.firstOrNull { it.id == id }

    /**
     * The actions worth offering on this ROM: [probe] returns each id's raw [Action.read]
     * output, and an action whose read fails outright is dropped rather than shown broken.
     */
    fun offered(probe: (String) -> String?): List<Action> =
        all.filter { action -> isSupported(action, probe(action.read)) }

    /** A missing standalone setting is unsupported, except an empty accessibility service list. */
    fun isSupported(action: Action, output: String?): Boolean = output != null && (
        action.id == accessibilityAutoGrant.id || !(
            action.read.startsWith("settings get ") && ';' !in action.read && (output.isBlank() || output.trim() == "null")
        )
    )

    /**
     * Whether [action] is already applied, from this run's probe. Null means "could not tell",
     * which the UI shows as unknown instead of "off" — a vendor ROM that blocks the read must
     * not look like a clean, untuned device.
     */
    fun status(action: Action, output: String?): Boolean? =
        output?.let { action.isApplied(it) }
}
