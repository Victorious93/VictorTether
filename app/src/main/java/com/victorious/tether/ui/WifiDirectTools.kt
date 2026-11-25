package com.victorious.tether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.victorious.tether.hotspot.DirectPeer
import com.victorious.tether.hotspot.WifiDirect
import com.victorious.tether.util.UpstreamInfo
import kotlinx.coroutines.launch

/**
 * Diagnostics for Wi-Fi Direct: does this phone support it, who is nearby, who is in our group.
 * Every action first makes sure the Wi-Fi permission is held via [onNeedPermission].
 */
@Composable
fun WifiDirectToolsPanel(
    upstream: UpstreamInfo,
    hasPermission: Boolean,
    onNeedPermission: () -> Unit,
) {
    val context = LocalContext.current
    val direct = remember { WifiDirect(context) }
    DisposableEffect(direct) { onDispose { direct.close() } }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var peers by remember { mutableStateOf<List<DirectPeer>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (!hasPermission) { onNeedPermission(); return }
        scope.launch {
            busy = true
            try { block() } catch (e: Exception) { report = listOf("error: ${e.message}") } finally { busy = false }
        }
    }

    Panel("WI-FI DIRECT TOOLS") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                run {
                    val s = direct.support()
                    report = listOf(
                        "Wi-Fi Direct hardware: ${if (s.hasFeature) "yes" else "NO"}",
                        "Wi-Fi Direct switched on: ${s.enabled?.let { if (it) "yes" else "no (turn Wi-Fi on)" } ?: "unknown"}",
                        "Hotspot + Wi-Fi together: ${upstream.staApConcurrent?.let { if (it) "yes" else "no" } ?: "unknown"}",
                        "Current source: ${upstream.transport.label}",
                    )
                    peers = null
                }
            }) { Text("Check support") }

            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                run { peers = direct.discoverPeers(); report = emptyList() }
            }) { Text(if (busy) "Working…" else "Scan nearby (5 s)") }

            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                run {
                    val g = direct.groupInfo()
                    report = if (g == null) listOf("No Wi-Fi Direct group is active.") else buildList {
                        add("Group: ${g.networkName}  (${if (g.isOwner) "we are the owner" else "we are a member"})")
                        add("Members: ${g.clients.size}")
                        g.clients.forEach { add("  ${it.name}  ${it.address}  ${it.status}") }
                    }
                    peers = null
                }
            }) { Text("Show group & members") }

            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                run { direct.removeGroup(); report = listOf("Group removal requested."); peers = null }
            }) { Text("Remove group") }
        }

        report.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        peers?.let { list ->
            if (list.isEmpty()) Text("No devices found. They must have Wi-Fi Direct open (e.g. Settings → Wi-Fi → Wi-Fi Direct).", style = MaterialTheme.typography.bodyMedium)
            list.forEach { Row { Text("${it.name}  ${it.address}  [${it.status}]", style = MaterialTheme.typography.bodyMedium) } }
        }
        Text(
            "Addresses may show as placeholders: Android hides real hardware addresses from apps.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
