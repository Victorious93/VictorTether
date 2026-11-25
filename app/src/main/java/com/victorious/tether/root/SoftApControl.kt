package com.victorious.tether.root

import android.content.Context
import android.os.Looper
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import android.net.wifi.WifiManager

/**
 * Privileged SoftAp/tethering calls, shared by the root helper ([SoftApMain], uid 0) and the
 * Shizuku user service ([SoftApUserService], uid 2000/shell). Everything here is reflection on
 * @SystemApi members, so it must stay free of AndroidX and of any Application context.
 */
object SoftApControl {
    private const val TETHERING_WIFI = 0
    private const val SECURITY_TYPE_WPA2_PSK = 1 // SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
    private const val BAND_2GHZ = 1 // SoftApConfiguration.BAND_2GHZ
    private const val SHELL_PACKAGE = "com.android.shell"

    @Volatile private var cached: Context? = null

    /** A system Context. As shell we act as com.android.shell so package/uid checks line up. */
    @Synchronized
    fun context(): Context {
        cached?.let { return it }
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        val system = at.getMethod("getSystemContext").invoke(thread) as Context
        val ctx = if (Process.myUid() == Process.SHELL_UID) {
            runCatching { system.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY) }.getOrDefault(system)
        } else system
        cached = ctx
        return ctx
    }

    fun start(ssid: String, pass: String, hidden: Boolean) {
        val ctx = context()
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // SoftApConfiguration.Builder is @SystemApi, so even the builder is reached by reflection.
        val cfgClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val b = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setSsid", String::class.java).invoke(b, ssid)
        builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
            .invoke(b, pass, SECURITY_TYPE_WPA2_PSK)
        builderClass.getMethod("setHiddenSsid", Boolean::class.javaPrimitiveType).invoke(b, hidden)
        builderClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(b, BAND_2GHZ) // widest client compatibility
        val config = builderClass.getMethod("build").invoke(b)

        runCatching { stop() } // config changes are ignored while the AP is up
        val applied = WifiManager::class.java.getMethod("setSoftApConfiguration", cfgClass).invoke(wifi, config) as Boolean
        if (!applied) error("setSoftApConfiguration rejected the configuration")
        startTethering()
    }

    fun stop() {
        val tm = tetheringManager()
        tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
    }

    /** Unwraps reflection noise so the UI shows the real cause. */
    fun describe(e: Throwable): String {
        val root = (e as? InvocationTargetException)?.targetException ?: e
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    private fun tetheringManager(): Any =
        context().getSystemService("tethering") ?: error("TetheringManager unavailable")

    private fun startTethering() {
        val tm = tetheringManager()
        val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val done = CountDownLatch(1)
        var failure: String? = null
        val callback = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, method, a ->
            when (method.name) {
                "onTetheringStarted" -> done.countDown()
                "onTetheringFailed" -> { failure = "tethering failed, error ${a?.firstOrNull()}"; done.countDown() }
            }
            null
        }
        tm.javaClass
            .getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, cbClass)
            .invoke(tm, TETHERING_WIFI, Executor { it.run() }, callback)
        if (!done.await(20, TimeUnit.SECONDS)) error("timed out waiting for tethering to start")
        failure?.let { error(it) }
    }
}
