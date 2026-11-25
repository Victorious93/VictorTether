package com.victorious.tether.hotspot

import com.victorious.tether.data.Config
import com.victorious.tether.data.HotspotInfo

class HotspotException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface HotspotBackend {
    /** Brings the access point up and returns the credentials clients must use. */
    suspend fun start(config: Config): HotspotInfo

    suspend fun stop()
}
