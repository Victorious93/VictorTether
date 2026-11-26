package com.victorious.tether.service

import android.content.Context
import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.proxy.ClientPolicy
import com.victorious.tether.proxy.ProxyClientSnapshot
import com.victorious.tether.proxy.ProxyClients
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class SessionCheckpoint(val upstream: ProxyUpstream, val deadline: Long, val directGroupKey: String?)

/** Only an Android restart of the running foreground service can use this checkpoint. */
internal class SessionRecovery(context: Context) {
    private val preferences = context.getSharedPreferences("interrupted_session", Context.MODE_PRIVATE)
    private var savedClients: List<ProxyClientSnapshot> = emptyList()

    fun read(config: Config): SessionCheckpoint? {
        if (preferences.getString("config", null) != fingerprint(config.toString())) return null
        val name = preferences.getString("route", null)
        val route = ProxyUpstream.entries.firstOrNull { it.name == name } ?: return null
        val deadline = preferences.getLong("deadline", 0L)
        if (deadline <= 0) return null
        return SessionCheckpoint(route, deadline, preferences.getString("directGroup", null))
    }

    fun save(config: Config, checkpoint: SessionCheckpoint) {
        preferences.edit()
            .putString("config", fingerprint(config.toString()))
            .putString("route", checkpoint.upstream.name)
            .putLong("deadline", checkpoint.deadline)
            .putString("directGroup", checkpoint.directGroupKey)
            .apply()
    }

    fun clear() {
        if (preferences.contains("config")) preferences.edit().clear().commit()
        savedClients = emptyList()
    }

    fun readClients(): List<ProxyClientSnapshot> {
        val array = JSONArray(preferences.getString("clients", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val policy = ClientPolicy(
                nickname = item.getString("nickname"), allowed = item.getBoolean("allowed"),
                kibibytesPerSecond = item.getInt("rate").coerceIn(0, ProxyClients.MAX_KIB_PER_SECOND),
                mebibytesPerSession = item.getInt("quota").coerceIn(0, ProxyClients.MAX_MIB_PER_SESSION),
            )
            ProxyClientSnapshot(item.getString("address"), policy, 0, item.getLong("sent"), item.getLong("received"), item.getLong("lastSeen"))
        }.also { savedClients = it }
    }

    fun saveClients(snapshots: List<ProxyClientSnapshot>) {
        val rules = snapshots.filter { it.policy != ClientPolicy() }
            .map { it.copy(activeConnections = 0) }.sortedBy { it.address }
        if (rules == savedClients) return
        val array = JSONArray()
        rules.forEach { snapshot ->
            array.put(JSONObject()
                .put("address", snapshot.address).put("nickname", snapshot.policy.nickname)
                .put("allowed", snapshot.policy.allowed).put("rate", snapshot.policy.kibibytesPerSecond)
                .put("quota", snapshot.policy.mebibytesPerSession).put("sent", snapshot.bytesUp)
                .put("received", snapshot.bytesDown).put("lastSeen", snapshot.lastSeenMillis))
        }
        preferences.edit().putString("clients", array.toString()).apply()
        savedClients = rules
    }
}

internal val Config.canResumeSession: Boolean
    get() = resumeAfterKill && mode != Mode.STEALTH_ROOT

internal fun fingerprint(value: String): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
)
