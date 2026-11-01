package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.*
import org.junit.Test

class SecureSettingsGrantTest {
    @Test fun prefersAnAuthorizedChannelWithoutAnotherPrompt() {
        val root = RootStatus(granted = true, connected = false)
        val shizuku = ShizukuStatus(installed = true, running = true, granted = true)
        val adb = AdbStatus(paired = true, wirelessDebugging = true, connected = false)
        assertEquals(SecureSettingsGrant.GRANTED, SecureSettingsGrant.path(true, root, shizuku, adb))
        assertEquals(SecureSettingsGrant.ROOT, SecureSettingsGrant.path(false, root, shizuku, adb))
        assertEquals(SecureSettingsGrant.SHIZUKU,
            SecureSettingsGrant.path(false, root.copy(granted = false), shizuku, adb))
        assertEquals(SecureSettingsGrant.ADB,
            SecureSettingsGrant.path(false, root.copy(granted = false), shizuku.copy(granted = false), adb))
        assertEquals(SecureSettingsGrant.SHIZUKU_PERMISSION,
            SecureSettingsGrant.path(false, root.copy(granted = false), shizuku.copy(granted = false),
                adb.copy(wirelessDebugging = false)))
    }
}
