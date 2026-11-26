package com.victorious.tether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.victorious.tether.proxy.ClientPolicy
import com.victorious.tether.proxy.ProxyClientSnapshot
import com.victorious.tether.proxy.ProxyClients
import com.victorious.tether.util.Net
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ClientsPage(
    clients: List<ProxyClientSnapshot>,
    proxyActive: Boolean,
    onPolicyChange: (String, ClientPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (clients.isEmpty()) item {
            Panel("No proxy clients yet") {
                SupportingText(if (proxyActive) "The first connection through VictorTether’s proxy will appear here."
                else "Start a sharing session with the proxy enabled to manage clients.")
            }
        } else {
            item { SupportingText("Clients are identified by IP. Rules and totals apply to this proxy session.") }
            items(clients, key = { it.address }) { client ->
                Panel(client.policy.nickname.ifBlank { client.address }) {
                    if (client.policy.nickname.isNotBlank()) SupportingText(client.address)
                    val state = when {
                        !client.policy.allowed -> "Blocked"
                        client.quotaReached -> "Transfer limit reached"
                        client.activeConnections > 0 -> "${client.activeConnections} open connections"
                        else -> "Last active ${formatter.format(Date(client.lastSeenMillis))}"
                    }
                    Text(state, style = MaterialTheme.typography.labelLarge)
                    SupportingText("↑ ${Net.humanBytes(client.bytesUp)}   ↓ ${Net.humanBytes(client.bytesDown)}")
                    val rate = client.policy.kibibytesPerSecond
                    val quota = client.policy.mebibytesPerSession
                    if (rate > 0) SupportingText("Rate limit $rate KiB/s")
                    if (quota > 0) SupportingText("Transfer limit $quota MiB")
                    TextButton(onClick = { editing = client.address }, enabled = proxyActive) { Text("Manage client") }
                }
            }
        }
    }
    val selected = if (proxyActive) clients.firstOrNull { it.address == editing } else null
    if (selected != null) ClientEditor(selected, onDismiss = { editing = null }) { policy ->
        onPolicyChange(selected.address, policy)
        editing = null
    }
}

@Composable
private fun ClientEditor(client: ProxyClientSnapshot, onDismiss: () -> Unit, onSave: (ClientPolicy) -> Unit) {
    var nickname by rememberSaveable(client.address) { mutableStateOf(client.policy.nickname) }
    var allowed by rememberSaveable(client.address) { mutableStateOf(client.policy.allowed) }
    var rateText by rememberSaveable(client.address) { mutableStateOf(client.policy.kibibytesPerSecond.toString()) }
    var transferText by rememberSaveable(client.address) { mutableStateOf(client.policy.mebibytesPerSession.toString()) }
    val rate = rateText.ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..ProxyClients.MAX_KIB_PER_SECOND }
    val transfer = transferText.ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..ProxyClients.MAX_MIB_PER_SESSION }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(client.address) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Nickname", nickname, true) { nickname = it.take(40) }
                SettingSwitch("Allow proxy access", allowed, true) { allowed = it }
                Field("Rate limit (KiB/s)", rateText, true, KeyboardType.Number) { rateText = it.filter { c -> c in '0'..'9' }.take(7) }
                Field("Transfer limit (MiB)", transferText, true, KeyboardType.Number) { transferText = it.filter { c -> c in '0'..'9' }.take(7) }
                SupportingText("Limits count downloads and uploads together. Use 0 for unlimited.")
                if (rate == null || transfer == null) Text("Enter a value from 0 to 1,048,576.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = rate != null && transfer != null, onClick = {
                onSave(ClientPolicy(nickname.trim(), allowed, checkNotNull(rate), checkNotNull(transfer)))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
