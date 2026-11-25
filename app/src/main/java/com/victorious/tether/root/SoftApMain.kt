package com.victorious.tether.root

import java.util.Base64
import kotlin.system.exitProcess

/**
 * Entry point executed as root via `app_process` (see RootBackend).
 *
 * Usage: SoftApMain start <ssid-b64> <pass-b64> <hidden:true|false> | SoftApMain stop
 * Last stdout line is `OK` or `ERR <reason>`.
 */
object SoftApMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "start" -> {
                    val ssid = String(Base64.getDecoder().decode(args[1]), Charsets.UTF_8)
                    val pass = String(Base64.getDecoder().decode(args[2]), Charsets.UTF_8)
                    SoftApControl.start(ssid, pass, args[3].toBoolean())
                }
                "stop" -> SoftApControl.stop()
                else -> { println("ERR usage"); exitProcess(2) }
            }
            println("OK")
            exitProcess(0)
        } catch (e: Throwable) {
            println("ERR ${SoftApControl.describe(e)}")
            exitProcess(1)
        }
    }
}
