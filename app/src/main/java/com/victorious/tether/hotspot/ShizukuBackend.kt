package com.victorious.tether.hotspot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import com.victorious.tether.BuildConfig
import com.victorious.tether.data.Config
import com.victorious.tether.data.HotspotInfo
import com.victorious.tether.root.SoftApUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Stealth backend without root: asks Shizuku to host [SoftApUserService] as the shell user and
 * calls it over Binder. Needs the Shizuku app running (Wireless debugging or root) and its
 * permission granted to this app.
 */
class ShizukuBackend(private val context: Context) : HotspotBackend {
    private var connection: ServiceConnection? = null
    private var service: IBinder? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, SoftApUserService::class.java.name)
    ).processNameSuffix("softap").daemon(false).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    override suspend fun start(config: Config): HotspotInfo {
        requireReady()
        val binder = bind()
        val reply = transact(binder, SoftApUserService.TX_START) {
            writeString(config.ssid); writeString(config.password); writeInt(if (config.hiddenSsid) 1 else 0)
        }
        if (reply != "OK") throw HotspotException("Shizuku helper: ${reply.ifBlank { "no reply" }}")
        return HotspotInfo(config.ssid, config.password, config.hiddenSsid)
    }

    override suspend fun stop() {
        runCatching { service?.let { transact(it, SoftApUserService.TX_STOP) {} } }
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        connection = null; service = null
    }

    private fun requireReady() {
        if (!Shizuku.pingBinder()) throw HotspotException("Shizuku is not running. Open the Shizuku app and start it.")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw HotspotException("Shizuku permission not granted to Victorious Tether")
        }
    }

    private suspend fun bind(): IBinder = try {
        withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        service = binder
                        if (cont.isActive) cont.resume(binder)
                    }
                    override fun onServiceDisconnected(name: ComponentName) { service = null }
                }
                connection = conn
                Shizuku.bindUserService(args, conn)
                cont.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, conn, true) } }
            }
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw HotspotException("Timed out starting the Shizuku service", e)
    }

    private suspend fun transact(binder: IBinder, code: Int, write: Parcel.() -> Unit): String =
        withContext(Dispatchers.IO) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.write()
                binder.transact(code, data, reply, 0)
                reply.readException()
                reply.readString().orEmpty()
            } catch (e: Exception) {
                throw HotspotException("Shizuku call failed: ${e.message}", e)
            } finally {
                data.recycle(); reply.recycle()
            }
        }
}
