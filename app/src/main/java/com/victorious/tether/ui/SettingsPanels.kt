package com.victorious.tether.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.victorious.tether.data.DirectBand
import com.victorious.tether.data.Mode
import com.victorious.tether.data.Privilege
import com.victorious.tether.data.ProxyDnsMode
import com.victorious.tether.data.ProxyIdleTimeout
import com.victorious.tether.data.ThemeMode
import com.victorious.tether.data.UpstreamPreference

@Composable
internal fun SettingsPanels(
    config: Config,
    locked: Boolean,
    onConfig: (Config) -> Unit,
    onOpenMesh: (() -> Unit)?,
    onOpenBatterySettings: () -> Unit,
    onOpenSystemHotspot: () -> Unit,
) {
    if (locked) SupportingText("Stop tethering to change settings.")
    Panel("Connection mode") {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Mode.entries.forEach { mode ->
                Choice(mode.label, config.mode == mode, !locked) { onConfig(config.copy(mode = mode)) }
            }
        }
        SupportingText(config.mode.blurb)
    }
    when (config.mode) {
        Mode.PROXY_ONLY -> Unit
        Mode.USB_PROXY -> Panel("USB connection") {
            SupportingText("Connect a USB data cable and enable USB tethering in Android settings. VictorTether will wait for the USB link.")
            OutlinedButton(onClick = onOpenSystemHotspot) { Text("Open Android network settings") }
        }
        else -> HotspotSettings(config, locked, onConfig)
    }
    ProxySettings(config, locked, onConfig, onOpenMesh)
    Panel("Session") {
        Field("Auto-stop (minutes)", config.autoStopMinutes.toString(), !locked, KeyboardType.Number) {
            onConfig(config.copy(autoStopMinutes = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0))
        }
        SupportingText("Set to 0 to keep sharing until you stop the session.")
        SettingSwitch("Keep CPU awake while sharing", config.holdCpuAwake, !locked) { onConfig(config.copy(holdCpuAwake = it)) }
        SettingSwitch("Keep screen on in VictorTether", config.keepScreenOn, !locked) { onConfig(config.copy(keepScreenOn = it)) }
        if (config.mode != Mode.STEALTH_ROOT) {
            SettingSwitch("Restore session after Android restarts the service", config.resumeAfterKill, !locked) {
                onConfig(config.copy(resumeAfterKill = it))
            }
        }
        OutlinedButton(onClick = onOpenBatterySettings) { Text("Android battery settings") }
    }
    val problems = config.validate()
    if (problems.isNotEmpty()) {
        Panel("Check your settings") {
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    Panel("Appearance") {
        Selection("Theme", config.themeMode, ThemeMode.entries, !locked, ThemeMode::label) { onConfig(config.copy(themeMode = it)) }
        if (Build.VERSION.SDK_INT >= 31) {
            SettingSwitch("Use Android dynamic colors", config.dynamicColors, !locked) { onConfig(config.copy(dynamicColors = it)) }
        }
        SettingSwitch("Vibrate on start and stop", config.haptics, !locked) { onConfig(config.copy(haptics = it)) }
    }
    var showReset by remember { mutableStateOf(false) }
    TextButton(onClick = { showReset = true }, enabled = !locked) { Text("Restore default settings") }
    if (showReset && !locked) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Restore default settings?") },
            text = { Text("Your connection settings will reset, and VictorTether will generate new Wi-Fi and proxy passwords.") },
            confirmButton = {
                TextButton(onClick = {
                    onConfig(Config(password = ConfigStore.randomSecret(12), proxyPass = ConfigStore.randomSecret(10)))
                    showReset = false
                }) { Text("Restore defaults") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HotspotSettings(config: Config, locked: Boolean, onConfig: (Config) -> Unit) {
    Panel(if (config.mode == Mode.WIFI_DIRECT) "Wi-Fi Direct group" else "Hotspot") {
        when (config.mode) {
            Mode.PROXY_LOCAL -> SupportingText("Android shows the network name and Wi-Fi password after the hotspot starts.")
            Mode.WIFI_DIRECT -> SupportingText("Android advertises the DIRECT- group name. Your client needs the password shown on Connect.")
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
            Mode.PROXY_ONLY, Mode.USB_PROXY -> Unit
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
        if (config.mode == Mode.WIFI_DIRECT) {
            Selection("Wi-Fi Direct band", config.directBand, DirectBand.entries.filter { Build.VERSION.SDK_INT >= it.minimumSdk }, !locked, DirectBand::label) {
                onConfig(config.copy(directBand = it))
            }
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
            if (config.requireProxyVpn) SupportingText("Connect your VPN before starting. Client traffic pauses until the VPN reconnects.")
            if (!config.requireProxyVpn) {
                Selection("Upstream network", config.upstreamPreference, UpstreamPreference.entries, !locked, UpstreamPreference::label) {
                    onConfig(config.copy(upstreamPreference = it))
                }
                when (config.upstreamPreference) {
                    UpstreamPreference.AUTOMATIC -> SupportingText("Wi-Fi Direct stays on Wi-Fi when the session starts there. Other sessions use Android’s active network.")
                    UpstreamPreference.ACTIVE -> SupportingText("Follow Android’s active network when it changes.")
                    else -> SupportingText("Client traffic waits when this network is unavailable. Enable VPN sharing to send client traffic through a VPN.")
                }
            }
            Selection("Proxy idle timeout", config.proxyIdleTimeout, ProxyIdleTimeout.entries, !locked, ProxyIdleTimeout::label) {
                onConfig(config.copy(proxyIdleTimeout = it))
            }
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
internal fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun Field(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
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

@Composable
private fun <T> Selection(title: String, selected: T, choices: List<T>, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "$title, ${label(selected)}" },
        ) { Text(label(selected)) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(label(choice)) }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}
