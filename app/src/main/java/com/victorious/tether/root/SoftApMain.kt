package com.victorious.tether.root

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Entry point executed as root via `app_process` (see RootBackend). It must stay free of
 * AndroidX/Compose and of anything needing an Application context.
 *
 * Usage: SoftApMain start <ssid-b64> <pass-b64> <hidden:true|false> | SoftApMain stop
 *
 * Uses reflection for the @SystemApi calls, which are absent from the public SDK jar:
 *  - WifiManager.setSoftApConfiguration(SoftApConfiguration)
 *  - TetheringManager.startTethering(int, Executor, StartTetheringCallback) / stopTethering(int)
 * Root (uid 0) passes the NETWORK_SETTINGS / TETHER_PRIVILEGED checks in system_server.
 */
object SoftApMain {
    private const val TETHERING_WIFI = 0
    private const val SECURITY_TYPE_WPA2_PSK = 1 // SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
    private const val BAND_2GHZ = 1 // SoftApConfiguration.BAND_2GHZ

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread) as Context

            when (args.firstOrNull()) {
                "start" -> {
                    val ssid = String(Base64.getDecoder().decode(args[1]), Charsets.UTF_8)
                    val pass = String(Base64.getDecoder().decode(args[2]), Charsets.UTF_8)
                    start(ctx, ssid, pass, args[3].toBoolean())
                    println("OK")
                }
                "stop" -> { stopTethering(ctx); println("OK") }
                else -> { println("ERR usage"); exitProcess(2) }
            }
            exitProcess(0)
        } catch (e: Throwable) {
            val root = (e as? InvocationTargetException)?.targetException ?: e
            println("ERR ${root.javaClass.simpleName}: ${root.message}")
            exitProcess(1)
        }
    }

    private fun start(ctx: Context, ssid: String, pass: String, hidden: Boolean) {
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

        runCatching { stopTethering(ctx) } // config changes are ignored while the AP is up
        val applied = WifiManager::class.java
            .getMethod("setSoftApConfiguration", cfgClass)
            .invoke(wifi, config) as Boolean
        if (!applied) error("setSoftApConfiguration rejected the configuration")
        startTethering(ctx)
    }

    private fun tetheringManager(ctx: Context): Any =
        ctx.getSystemService("tethering") ?: error("TetheringManager unavailable")

    private fun startTethering(ctx: Context) {
        val tm = tetheringManager(ctx)
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

    private fun stopTethering(ctx: Context) {
        val tm = tetheringManager(ctx)
        tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
    }
}
