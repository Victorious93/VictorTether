package com.victorious.tether.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.victorious.tether.R
import com.victorious.tether.BuildConfig
import com.victorious.tether.data.Config
import com.victorious.tether.data.Mode
import com.victorious.tether.data.StatsSnapshot
import com.victorious.tether.data.Status
import com.victorious.tether.proxy.ClientPolicy
import com.victorious.tether.proxy.ProxyClientSnapshot
import com.victorious.tether.util.Net
import com.victorious.tether.util.Qr
import com.victorious.tether.util.Severity
import com.victorious.tether.util.Upstream
import com.victorious.tether.util.UpstreamInfo

private enum class Screen(val title: String) {
    CONNECT("Connect"), CLIENTS("Clients"), SETTINGS("Settings"), TOOLS("Tools"),
}

@Composable
fun MainScreen(
    config: Config,
    status: Status,
    stats: StatsSnapshot,
    clients: List<ProxyClientSnapshot>,
    upstream: UpstreamInfo,
    hasWifiPermission: Boolean,
    onNeedWifiPermission: () -> Unit,
    onConfig: (Config) -> Unit,
    onToggle: () -> Unit,
    onOpenSystemHotspot: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenReleases: () -> Unit,
    onClientPolicy: (String, ClientPolicy) -> Unit,
    onCopyText: (String) -> Unit,
    onOpenMesh: (() -> Unit)? = null,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.CONNECT) }
    val screenState = rememberSaveableStateHolder()
    val locked = status is Status.Running || status is Status.Reconnecting || status == Status.Starting || status == Status.Stopping
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            Column(Modifier.background(colors.background).statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryContainer) {
                        Icon(
                            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                            modifier = Modifier.size(42.dp), tint = colors.primary,
                        )
                    }
                    Text("Victorious Tether", style = MaterialTheme.typography.titleLarge)
                }
                TabRow(
                    selectedTabIndex = screen.ordinal,
                    containerColor = colors.background,
                    contentColor = colors.primary,
                    divider = { HorizontalDivider(color = colors.outlineVariant) },
                ) {
                    Screen.entries.forEach { tab ->
                        Tab(
                            selected = screen == tab,
                            onClick = { screen = tab },
                            text = { Text(tab.title) },
                            unselectedContentColor = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { insets ->
        screenState.SaveableStateProvider(screen.name) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                if (screen == Screen.CLIENTS) {
                    ClientsPage(clients, locked && config.proxyRuns, onClientPolicy, Modifier.widthIn(max = 680.dp))
                    return@Box
                }
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (screen) {
                        Screen.CONNECT -> {
                            ConnectionCard(config, status, upstream, onToggle)
                            if (status is Status.Running) {
                                ActiveConnectionDetails(status, stats, onCopyText)
                            } else {
                                Panel("Connection mode") {
                                    Text(config.mode.label, style = MaterialTheme.typography.titleMedium)
                                    SupportingText(config.mode.blurb)
                                    TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Change mode") }
                                }
                            }
                            val assessment = Upstream.assess(upstream, config.mode)
                            val warning = assessment.firstOrNull { it.severity == Severity.BAD }
                                ?: assessment.firstOrNull { it.severity == Severity.WARN }
                            if (warning != null) {
                                Panel("Network status") {
                                    Text(warning.text, color = if (warning.severity == Severity.BAD) colors.error else ConnectionWarning)
                                }
                            }
                        }
                        Screen.CLIENTS -> Unit
                        Screen.SETTINGS -> SettingsPanels(config, locked, onConfig, onOpenMesh, onOpenBatterySettings, onOpenSystemHotspot)
                        Screen.TOOLS -> {
                            Panel("Upstream connection") {
                                if (upstream.validated) InfoLine("Network", upstream.transport.label)
                                Upstream.assess(upstream, config.mode).filterNot { it.severity == Severity.OK }.forEach { advice ->
                                    val color = when (advice.severity) {
                                        Severity.OK -> colors.primary
                                        Severity.INFO -> colors.onSurfaceVariant
                                        Severity.WARN -> ConnectionWarning
                                        Severity.BAD -> colors.error
                                    }
                                    Text(advice.text, color = color, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            WifiDirectToolsPanel(upstream, hasWifiPermission, onNeedWifiPermission, locked)
                            if (config.mode == Mode.PROXY_ONLY || config.mode == Mode.USB_PROXY) {
                                OutlinedButton(onClick = onOpenSystemHotspot, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open Android wireless settings")
                                }
                            }
                            Panel("Client setup") {
                                SupportingText("Join the shared Wi-Fi or USB network. Use the address and port on Connect in the client’s manual HTTP proxy settings.")
                                SupportingText("For UDP, configure an app that supports SOCKS5 UDP ASSOCIATE. Use SOCKS5 or HTTP on sessions that require a login.")
                                TextButton(onClick = {
                                    val state = when (status) {
                                        Status.Idle -> "Idle"
                                        Status.Starting -> "Starting"
                                        Status.Stopping -> "Stopping"
                                        is Status.Reconnecting -> "Reconnecting"
                                        is Status.Running -> "Running"
                                        is Status.Failed -> "Failed"
                                    }
                                    onCopyText("VictorTether ${BuildConfig.VERSION_NAME}\nMode: ${config.mode.label}\nSession: $state\nUpstream: ${upstream.transport.label}\nNetwork validated: ${upstream.validated}\nProxy DNS: ${config.proxyDnsMode.label}")
                                }) { Text("Copy session diagnostics") }
                            }
                            Panel("VictorTether ${BuildConfig.VERSION_NAME}") {
                                TextButton(onClick = onOpenReleases) { Text("View releases") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(config: Config, status: Status, upstream: UpstreamInfo, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (heading, accent) = when (status) {
        Status.Idle -> "Tethering is off" to colors.onSurfaceVariant
        Status.Starting -> "Starting tethering" to ConnectionWarning
        is Status.Reconnecting -> "Reconnecting" to ConnectionWarning
        Status.Stopping -> "Stopping tethering" to ConnectionWarning
        is Status.Running -> "Tethering is active" to colors.primary
        is Status.Failed -> "Connection failed" to colors.error
    }
    val action = when (status) {
        Status.Idle, is Status.Failed -> "Start tethering"
        Status.Starting -> "Cancel start"
        Status.Stopping -> "Stopping…"
        is Status.Running, is Status.Reconnecting -> "Stop tethering"
    }
    val stop = status is Status.Running || status is Status.Reconnecting || status == Status.Starting

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surface,
    ) {
        Column(
            Modifier.background(Brush.verticalGradient(listOf(ConnectionHighlight, colors.surface)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).background(accent, CircleShape))
                Text(heading, style = MaterialTheme.typography.headlineSmall)
            }
            if (status is Status.Failed) Text(status.message, color = colors.error)
            if (status is Status.Reconnecting) SupportingText(status.message)
            InfoLine("Upstream", upstream.transport.label)
            if (config.proxyRuns && config.requireProxyVpn) {
                InfoLine("Proxy route", if (status is Status.Running) "Bound to VPN" else "VPN required")
            } else if (status is Status.Running && status.proxyRoute != null) {
                InfoLine("Proxy route", status.proxyRoute)
            }
            Button(
                onClick = onToggle,
                enabled = status != Status.Stopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stop) colors.errorContainer else colors.primary,
                    contentColor = if (stop) colors.onErrorContainer else colors.onPrimary,
                ),
            ) { Text(action) }
        }
    }
}

@Composable
private fun ActiveConnectionDetails(status: Status.Running, stats: StatsSnapshot, onCopyText: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Panel("Traffic") {
        InfoLine("Connections", "${stats.activeConnections} active / ${stats.totalConnections} total")
        InfoLine("Uploaded", Net.humanBytes(stats.bytesUp))
        InfoLine("Downloaded", Net.humanBytes(stats.bytesDown))
    }
    status.hotspot?.let { hotspot ->
        var showQr by remember(status.startedAtMillis) { mutableStateOf(false) }
        Panel("Join the network") {
            InfoLine("Network name", hotspot.ssid + if (hotspot.hidden) " (hidden)" else "")
            if (hotspot.hidden) SupportingText("Scan the QR code or add the network manually with Hidden network enabled.")
            if (status.config.mode == Mode.WIFI_DIRECT) SupportingText("Scan the QR code or select this network in the other phone’s Wi-Fi settings.")
            OutlinedButton(onClick = { showQr = !showQr }) {
                Text(if (showQr) "Hide QR and password" else "Show QR and password")
            }
            if (showQr) {
                val qr = remember(hotspot) {
                    Qr.render(Net.wifiQrPayload(hotspot.ssid, hotspot.password, hotspot.hidden))
                }
                Image(
                    qr.asImageBitmap(), "Wi-Fi connection QR code",
                    Modifier.size(184.dp).align(Alignment.CenterHorizontally),
                )
                InfoLine("Wi-Fi password", hotspot.password)
            }
        }
    }
    if (status.config.proxyRuns) {
        var showLogin by remember(status.startedAtMillis) { mutableStateOf(false) }
        Panel("Proxy address") {
            SupportingText("Use this endpoint for HTTP or SOCKS5. SOCKS5 clients can also relay UDP.")
            if (status.addresses.isEmpty()) SupportingText("Port ${status.config.port}. No private IPv4 address found yet.")
            status.addresses.forEach { candidate ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${candidate.address}:${status.config.port}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = colors.primary,
                    )
                    SupportingText(candidate.iface + if (candidate.looksLikeAccessPoint) " · likely hotspot" else "")
                }
            }
            status.addresses.firstOrNull()?.let { address ->
                TextButton(onClick = { onCopyText("${address.address}:${status.config.port}") }) { Text("Copy proxy address") }
            }
            if (status.config.authRequired) {
                TextButton(onClick = { showLogin = !showLogin }) {
                    Text(if (showLogin) "Hide proxy login" else "Show proxy login")
                }
                if (showLogin) {
                    InfoLine("Username", status.config.proxyUser)
                    InfoLine("Password", status.config.proxyPass)
                }
            }
        }
    }
}

@Composable
internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun SupportingText(value: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.5f))
    }
}
