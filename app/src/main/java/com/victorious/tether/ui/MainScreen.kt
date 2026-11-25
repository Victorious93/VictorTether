package com.victorious.tether.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.Status
import com.victorious.tether.data.StatsSnapshot
import com.victorious.tether.util.Net
import com.victorious.tether.util.Qr

@Composable
fun MainScreen(
    config: Config,
    status: Status,
    stats: StatsSnapshot,
    onConfig: (Config) -> Unit,
    onToggle: () -> Unit,
    onOpenSystemHotspot: () -> Unit,
) {
    val busy = status is Status.Running || status is Status.Starting
    Column(
        Modifier.background(Term.bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("> VICTORIOUS TETHER_", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Victorious Framework · proxy tether + stealth AP", style = MaterialTheme.typography.labelMedium)

        StatusPanel(status, stats)

        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (busy) Term.red else Term.green,
                contentColor = Term.bg,
            ),
        ) { Text(if (busy) "[ STOP ]" else "[ START ]", fontWeight = FontWeight.Bold) }

        ModePanel(config, busy, onConfig)
        if (config.mode != Mode.PROXY_ONLY) HotspotPanel(config, busy, onConfig)
        ProxyPanel(config, busy, onConfig)
        if (config.mode == Mode.PROXY_ONLY) {
            OutlinedButton(onClick = onOpenSystemHotspot, modifier = Modifier.fillMaxWidth()) {
                Text("Open system wireless settings")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------------------ panels

@Composable
private fun Panel(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, Term.line), RoundedCornerShape(6.dp))
            .background(Term.panel, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("// $title", style = MaterialTheme.typography.labelMedium)
        content()
    }
}

@Composable
private fun StatusPanel(status: Status, stats: StatsSnapshot) {
    Panel("STATUS") {
        when (status) {
            Status.Idle -> Text("OFFLINE", color = Term.dim)
            Status.Starting -> Text("STARTING…", color = Term.amber)
            is Status.Failed -> Text("FAILED: ${status.message}", color = Term.red)
            is Status.Running -> {
                Text("ONLINE", fontWeight = FontWeight.Bold)
                status.hotspot?.let { hs ->
                    Line("SSID", hs.ssid + if (hs.hidden) "  (hidden)" else "")
                    Line("PASS", hs.password)
                    val qr = remember(hs) { Qr.render(Net.wifiQrPayload(hs.ssid, hs.password, hs.hidden)) }
                    Image(
                        qr.asImageBitmap(), contentDescription = "Wi-Fi QR code",
                        modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
                    )
                    if (hs.hidden) {
                        Text(
                            "Hidden: the network will not appear in scans. Join by scanning this QR " +
                                "or add the SSID manually (WPA2, 'hidden network' on).",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (status.config.proxyRuns) {
                    val ips = status.addresses
                    if (ips.isEmpty()) Line("PROXY", "port ${status.config.port} (no private IPv4 found)")
                    ips.forEach {
                        Line(if (it.looksLikeAccessPoint) "PROXY*" else "PROXY", "${it.address}:${status.config.port}  [${it.iface}]")
                    }
                    Text(
                        "HTTP + SOCKS5 on one port. * = interface that looks like the hotspot (heuristic).",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Line("CONN", "${stats.activeConnections} active / ${stats.totalConnections} total")
                Line("UP", Net.humanBytes(stats.bytesUp))
                Line("DOWN", Net.humanBytes(stats.bytesDown))
            }
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row {
        Text(k.padEnd(7), style = MaterialTheme.typography.bodyMedium, color = Term.dim)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModePanel(config: Config, locked: Boolean, onConfig: (Config) -> Unit) {
    Panel("MODE") {
        Mode.entries.forEach { m ->
            FilterChip(
                selected = config.mode == m,
                enabled = !locked,
                onClick = { onConfig(config.copy(mode = m)) },
                label = { Text(m.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Term.line, selectedLabelColor = Term.green,
                    labelColor = Term.dim, disabledLabelColor = Term.dim,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(config.mode.blurb, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HotspotPanel(config: Config, locked: Boolean, onConfig: (Config) -> Unit) {
    val stealth = config.mode == Mode.STEALTH_ROOT
    Panel("HOTSPOT") {
        if (!stealth) {
            Text(
                "Android assigns the SSID and password in this mode, and the SSID cannot be hidden. " +
                    "Switch to Stealth (root) to control both.",
                style = MaterialTheme.typography.labelMedium, color = Term.amber,
            )
        }
        if (stealth) {
            Text("Privilege source", style = MaterialTheme.typography.labelMedium)
            Privilege.entries.forEach { p ->
                FilterChip(
                    selected = config.privilege == p,
                    enabled = !locked,
                    onClick = { onConfig(config.copy(privilege = p)) },
                    label = { Text(p.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Term.line, selectedLabelColor = Term.green,
                        labelColor = Term.dim, disabledLabelColor = Term.dim,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                if (config.privilege == Privilege.SHIZUKU)
                    "Needs the Shizuku app installed and running. No root required; you grant this app access once."
                else "Needs a rooted device; you will get a root prompt.",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Field("SSID", config.ssid, enabled = stealth && !locked) { onConfig(config.copy(ssid = it.take(32))) }
        Field("Password (8–63)", config.password, enabled = stealth && !locked) { onConfig(config.copy(password = it.take(63))) }
        OutlinedButton(
            onClick = { onConfig(config.copy(password = ConfigStore.randomSecret(14))) },
            enabled = stealth && !locked,
        ) { Text("Generate password") }
        Toggle("Hide SSID (don't broadcast)", config.hiddenSsid && stealth, enabled = stealth && !locked) {
            onConfig(config.copy(hiddenSsid = it))
        }
        if (stealth) {
            Text(
                "Hidden SSIDs stop casual discovery only: the name is still sent by your " +
                    "own devices when they connect. Not a substitute for a strong password.",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ProxyPanel(config: Config, locked: Boolean, onConfig: (Config) -> Unit) {
    val stealth = config.mode == Mode.STEALTH_ROOT
    Panel("PROXY") {
        if (stealth) {
            Toggle("Also run proxy", config.proxyEnabled, enabled = !locked) { onConfig(config.copy(proxyEnabled = it)) }
        }
        if (config.proxyRuns) {
            Field("Port", config.port.toString(), enabled = !locked, numeric = true) {
                onConfig(config.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))
            }
            val forced = config.mode == Mode.PROXY_ONLY
            Toggle(
                if (forced) "Require login (forced in this mode)" else "Require login",
                config.authRequired, enabled = !locked && !forced,
            ) { onConfig(config.copy(requireProxyAuth = it)) }
            if (config.authRequired) {
                Field("Username", config.proxyUser, enabled = !locked) { onConfig(config.copy(proxyUser = it)) }
                Field("Password", config.proxyPass, enabled = !locked) { onConfig(config.copy(proxyPass = it)) }
            }
        }
        Field("Auto-stop after (minutes, 0 = never)", config.autoStopMinutes.toString(), enabled = !locked, numeric = true) {
            onConfig(config.copy(autoStopMinutes = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0))
        }
        val problems = config.validate()
        problems.forEach { Text("! $it", color = Term.red, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Term.green, unfocusedBorderColor = Term.line,
            focusedTextColor = Term.green, unfocusedTextColor = Term.green,
            disabledTextColor = Term.dim, disabledBorderColor = Term.line,
            focusedLabelColor = Term.green, unfocusedLabelColor = Term.dim, disabledLabelColor = Term.dim,
        ),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Term.bg, checkedTrackColor = Term.green,
                uncheckedThumbColor = Term.dim, uncheckedTrackColor = Term.panel,
            ),
        )
    }
}
