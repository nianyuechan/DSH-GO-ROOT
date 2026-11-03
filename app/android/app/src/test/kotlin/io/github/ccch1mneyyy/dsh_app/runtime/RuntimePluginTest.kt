package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.SharedPreferences
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimePluginTest {
    private class RecordingResult : MethodChannel.Result {
        val answered = CountDownLatch(1)
        var value: Any? = null
        var errorCode: String? = null
        var thread: String? = null

        override fun success(result: Any?) {
            value = result
            thread = Thread.currentThread().name
            answered.countDown()
        }

        override fun error(code: String, message: String?, details: Any?) {
            errorCode = code
            thread = Thread.currentThread().name
            answered.countDown()
        }

        override fun notImplemented() {
            errorCode = "notImplemented"
            thread = Thread.currentThread().name
            answered.countDown()
        }
    }

    private class RecordingEvents : EventChannel.EventSink {
        val values = mutableListOf<Any?>()
        override fun success(event: Any?) { values += event }
        override fun error(code: String?, message: String?, details: Any?) = Unit
        override fun endOfStream() = Unit
    }

    private class Fixture {
        val originalProbe = RootLaunch.suProbe
        val base = Files.createTempDirectory("runtime-plugin").toFile()
        val rootfs = File(base, "rootfs")
        val plugin = allocate(RuntimePlugin::class.java)
        val controller = allocate(RuntimeController::class.java)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "dsh-runtime-io") }
        val preferences = mutableMapOf<String, Any?>("rootEngine" to true)

        init {
            for (path in listOf("usr/bin/setpriv", "usr/bin/env", "bin/sh", "usr/local/bin/dsh")) {
                File(rootfs, path).apply {
                    parentFile!!.mkdirs()
                    writeText("test")
                    setExecutable(true)
                }
            }
            val manifest = RuntimeManifest(
                runtimeVersion = "v1", abi = "x64", rootfsAsset = "rootfs", rootfsBytes = 0,
                rootfsSha256 = null, uncompressedBytes = 0, dshVersion = "dsh", nodeVersion = "node",
                launchArgv = emptyList(), launchEnv = emptyMap(), raw = allocate(JSONObject::class.java),
            )
            val installer = allocate(RuntimeInstaller::class.java)
            setField(installer, "rootfs", rootfs)
            setField(installer, "versionFile", File(base, "rootfs.version"))
            setField(controller, "manifest\$delegate", lazy { manifest })
            setField(controller, "installer\$delegate", lazy { installer })
            setField(controller, "phase", RuntimeController.Phase.NOT_INSTALLED)
            setField(controller, "safeState", SafeState.OFF)
            setField(controller, "managedSkillGate", ManagedSkillRuntimeGate())
            setField(controller, "networkAccess", allocate(NetworkAccess::class.java))
            setField(controller, "sinks", LinkedHashSet<RuntimeController.EventSink>())
            setField(controller, "prefs", preferences(preferences))
            setField(plugin, "controller", controller)
            setField(plugin, "worker", executor)
            plugin.postMain = { action -> action() }
            RootLaunch.invalidate()
        }

        fun close() {
            (getField(plugin, "worker") as ExecutorService).shutdownNow()
            RootLaunch.suProbe = originalProbe
            RootLaunch.invalidate()
            base.deleteRecursively()
        }
    }

    @Test
    fun `getStatus and onListen never probe su even when root was saved`() {
        val fixture = Fixture()
        var probes = 0
        RootLaunch.suProbe = { probes += 1; true }
        try {
            val status = RecordingResult()
            fixture.plugin.onMethodCall(MethodCall("getStatus", null), status)
            val statusMap = status.value as Map<*, *>
            assertEquals(true, statusMap["rootEngineSupported"])
            assertEquals(false, statusMap["rootEngineAvailable"])
            assertNull(status.errorCode)

            val events = RecordingEvents()
            fixture.plugin.onListen(null, events)
            val event = events.values.single() as Map<*, *>
            assertEquals("status", event["type"])
            assertEquals(false, (event["status"] as Map<*, *>)["rootEngineAvailable"])
            assertEquals(0, probes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the explicit root switch probes su on the runtime io worker`() {
        val fixture = Fixture()
        var probes = 0
        var probeThread: String? = null
        RootLaunch.suProbe = {
            probes += 1
            probeThread = Thread.currentThread().name
            true
        }
        try {
            val result = RecordingResult()
            fixture.plugin.onMethodCall(MethodCall("setRootEngine", mapOf("enabled" to true)), result)

            assertTrue(result.answered.await(5, TimeUnit.SECONDS))
            assertNull(result.errorCode)
            assertEquals(1, probes)
            assertEquals("dsh-runtime-io", probeThread)
            assertEquals("dsh-runtime-io", result.thread)
            assertFalse(Thread.currentThread().name == probeThread)
        } finally {
            fixture.close()
        }
    }

    private companion object {
        fun setField(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply {
                isAccessible = true
                set(target, value)
            }
        }

        fun getField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            @Suppress("UNCHECKED_CAST")
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
        }

        fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putString", "putInt", "putLong", "putFloat", "putStringSet" -> {
                        values[args[0] as String] = args[1]
                        editor
                    }
                    "remove" -> { values.remove(args[0]); editor }
                    "clear" -> { values.clear(); editor }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                val key = args?.getOrNull(0) as? String
                when (method.name) {
                    "contains" -> values.containsKey(key)
                    "getBoolean" -> values[key] as? Boolean ?: args[1] as Boolean
                    "getString" -> values[key] as? String ?: args[1]
                    "getInt" -> values[key] as? Int ?: args[1] as Int
                    "getLong" -> values[key] as? Long ?: args[1] as Long
                    "getFloat" -> values[key] as? Float ?: args[1] as Float
                    "getAll" -> values.toMap()
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
