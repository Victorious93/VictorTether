package com.victorious.tether.root

import android.os.Binder
import android.os.Parcel

/**
 * Runs inside Shizuku's process (shell uid) and is loaded from this APK. A bare [Binder] with
 * hand-rolled transaction codes keeps us free of AIDL. Replies carry one string: "OK" or "ERR ...".
 */
class SoftApUserService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val result = try {
            when (code) {
                TX_START -> { SoftApControl.start(data.readString().orEmpty(), data.readString().orEmpty(), data.readInt() == 1); "OK" }
                TX_STOP -> { SoftApControl.stop(); "OK" }
                else -> return super.onTransact(code, data, reply, flags)
            }
        } catch (e: Throwable) {
            "ERR ${SoftApControl.describe(e)}"
        }
        reply?.writeNoException()
        reply?.writeString(result)
        return true
    }

    companion object {
        const val TX_START = FIRST_CALL_TRANSACTION
        const val TX_STOP = FIRST_CALL_TRANSACTION + 1
    }
}
