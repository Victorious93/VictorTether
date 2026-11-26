package com.victorious.tether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.victorious.tether.data.Config
import com.victorious.tether.data.ConfigStore
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.ProxyDnsMode

@Composable
internal fun SettingsPanels(config: Config, locked: Boolean, onConfig: (Config) -> Unit, onOpenMesh: (() -> Unit)?) {
    if (locked) SupportingText("Stop tethering to change settings.")
    Panel("Connection mode") {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Mode.entries.forEach { mode ->
                Choice(mode.label, config.mode == mode, !locked) { onConfig(config.copy(mode = mode)) }
            }
        }
        SupportingText(config.mode.blurb)
    }
    if (config.mode != Mode.PROXY_ONLY) HotspotSettings(config, locked, onConfig)
    ProxySettings(config, locked, onConfig, onOpenMesh)
    Panel("Session") {
        Field("Auto-stop (minutes)", config.autoStopMinutes.toString(), !locked, KeyboardType.Number) {
            onConfig(config.copy(autoStopMinutes = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0))
        }
        SupportingText("Set to 0 to keep sharing until you stop the session.")
    }
    val problems = config.validate()
    if (problems.isNotEmpty()) {
        Panel("Check your settings") {
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun HotspotSettings(config: Config, locked: Boolean, onConfig: (Config) -> Unit) {
    Panel("Hotspot") {
        when (config.mode) {
            Mode.PROXY_LOCAL -> SupportingText("Android shows the network name and Wi-Fi password after the hotspot starts.")
            Mode.WIFI_DIRECT -> SupportingText("Wi-Fi Direct names start with DIRECT-. Check Connect for the name and password chosen by your phone.")
            Mode.STEALTH_ROOT -> {
                Text("Hotspot access", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Privilege.entries.forEach { privilege ->
                        Choice(privilege.label, config.privilege == privilege, !locked) {
                            onConfig(config.copy(privilege = privilege))
                        }
                    }
                }
                SupportingText(
                    if (config.privilege == Privilege.SHIZUKU) "Start Shizuku and allow Victorious Tether to use it."
                    else "Grant access when your root manager prompts you."
                )
            }
            Mode.PROXY_ONLY -> Unit
        }
        if (config.mode == Mode.WIFI_DIRECT || config.mode == Mode.STEALTH_ROOT) {
            Field("Network name (SSID)", config.ssid, !locked) { onConfig(config.copy(ssid = it.take(32))) }
            Field("Wi-Fi password", config.password, !locked, KeyboardType.Password) {
                onConfig(config.copy(password = it.take(63)))
            }
            OutlinedButton(
                onClick = { onConfig(config.copy(password = ConfigStore.randomSecret(14))) },
                enabled = !locked,
            ) { Text("Generate password") }
        }
        if (config.mode == Mode.STEALTH_ROOT) {
            SettingSwitch("Hide network name", config.hiddenSsid, !locked) { onConfig(config.copy(hiddenSsid = it)) }
        }
    }
}

@Composable
private fun ProxySettings(config: Config, locked: Boolean, onConfig: (Config) -> Unit, onOpenMesh: (() -> Unit)?) {
    Panel("Proxy") {
        if (config.mode == Mode.STEALTH_ROOT) {
            SettingSwitch("Run the proxy with tethering", config.proxyEnabled, !locked) {
                onConfig(config.copy(proxyEnabled = it))
            }
        }
        if (config.proxyRuns) {
            Field("Port", config.port.toString(), !locked, KeyboardType.Number) {
                onConfig(config.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))
            }
            SettingSwitch("Share VPN with proxy clients", config.requireProxyVpn, !locked) {
                onConfig(config.copy(requireProxyVpn = it))
            }
            if (config.requireProxyVpn) SupportingText("Connect your VPN before starting. Sharing stops if the VPN disconnects.")
            if (onOpenMesh != null) OutlinedButton(onClick = onOpenMesh) { Text("Open Victorious Mesh") }
            SettingSwitch(
                if (config.proxyAuthForced) "Client login required" else "Require client login",
                config.authRequired, !locked && !config.proxyAuthForced,
            ) { onConfig(config.copy(requireProxyAuth = it)) }
            if (config.authRequired) {
                Field("Proxy username", config.proxyUser, !locked) { onConfig(config.copy(proxyUser = it)) }
                Field("Proxy password", config.proxyPass, !locked, KeyboardType.Password) {
                    onConfig(config.copy(proxyPass = it))
                }
            }
        }
    }
    if (config.proxyRuns) {
        Panel("Proxy DNS") {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProxyDnsMode.entries.forEach { mode ->
                    Choice(mode.label, config.proxyDnsMode == mode, !locked) {
                        onConfig(config.copy(proxyDnsMode = mode))
                    }
                }
            }
            when (config.proxyDnsMode) {
                ProxyDnsMode.SYSTEM -> SupportingText("Resolve proxy hostnames through the phone’s current network.")
                ProxyDnsMode.ADGUARD -> SupportingText("AdGuard receives hostname lookups and filters supported ad and tracker domains.")
                ProxyDnsMode.CUSTOM_HTTPS -> Field("HTTPS DNS URL", config.proxyDnsUrl, !locked, KeyboardType.Uri) {
                    onConfig(config.copy(proxyDnsUrl = it.take(2048)))
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected, enabled, Role.RadioButton, onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val password = keyboard == KeyboardType.Password
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                val action = if (revealed) "Hide" else "Show"
                TextButton(
                    modifier = Modifier.semantics { contentDescription = "$action ${label.lowercase()}" },
                    onClick = { revealed = !revealed },
                ) { Text(action) }
            }
        } else null,
    )
}
