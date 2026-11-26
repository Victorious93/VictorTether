# Victorious Tether — Victorious Framework living guide

_Last synchronised with the Wi-Fi Direct and proxy feature preview. Update this file in the same commit as any behaviour change._

## 1. Status

| State | Item |
|---|---|
| Finished | HTTP, SOCKS4a CONNECT, SOCKS5 CONNECT and SOCKS5 UDP relay on a single proxy entry point |
| Finished | Proxy DNS selection with system DNS, AdGuard ad blocking and custom DNS over HTTPS |
| Finished | Optional VPN-bound proxy sessions with forced login, plus prompt closure of both sides of active tunnels on Stop |
| Finished | Config validation, Wi-Fi QR payload, foreground service, Quick Settings tile, Compose UI |
| Finished | Debug APK builds, `lintDebug` passes |
| Finished | Per-IP client access controls, traffic totals, nicknames, bandwidth and transfer limits |
| Finished (unverified on device) | Shizuku backend: Stealth without root |
| Finished (logic tested, UI unverified) | Upstream check shows what you are sharing and warns when starting the hotspot may interrupt the connection |
| Finished (unverified on device) | Wi-Fi Direct mode with Wi-Fi-bound proxy connections and group recovery, and a tools panel |
| Finished (unverified on device) | Android USB tethering proxy mode, Wi-Fi or mobile upstream selection and opt-in service recovery |
| Deferred | Release signing |
| Abandoned | Copying/decompiling the reference app (see §6) |

An Android emulator verified **Proxy only** start, stop and restart. Physical-device verification is pending for LocalOnlyHotspot, Wi-Fi Direct, Android USB tethering, service recovery, the root and Shizuku helpers, the tile, and VPN sharing with a live Mesh network.

## 2. Modes

| Mode | Root | SSID visible | You choose SSID/password | Internet path for clients |
|---|---|---|---|---|
| Proxy · no root | no | **yes** (platform limit) | no (Android picks) | client must set HTTP/SOCKS proxy to `ip:port` |
| Wi-Fi Direct · no root | no | Android advertises the `DIRECT-…` name | requested, device dependent | client must set proxy to `ip:port` |
| Stealth · root **or Shizuku** | root _or_ Shizuku | **hidden** | yes | native tethering (NAT) — proxy optional |
| Proxy only | no | n/a | n/a | you bring the link (system hotspot, USB…); login forced |
| USB tethering | no | n/a | n/a | Android USB tethering supplies the link; proxy login required |

Why hidden SSID needs root: `SoftApConfiguration.Builder#setHiddenSsid` and `WifiManager#setSoftApConfiguration` are `@SystemApi`. Unprivileged apps can only call `startLocalOnlyHotspot`, which accepts no configuration. _[Certain]_ for the public-API side; the root route is _[Likely]_ (see §4).

## 3. Architecture

```
MainActivity (Compose UI) ──► ConfigStore (SharedPreferences)
        │ start/stop                       ▲
        ▼                                  │ load()
TetherService (foreground, connectedDevice)│
   ├─ HotspotBackend ── LocalOnlyBackend   (WifiManager.startLocalOnlyHotspot)
   │                 ├─ RootBackend ──su──► SoftApMain (app_process, uid 0) ─┐
   │                 └─ ShizukuBackend ─► SoftApUserService (shell uid) ────┴► SoftApControl (reflection)
   ├─ ProxyServer  (HTTP, SOCKS4a, SOCKS5 TCP and UDP)
   └─ TetherState  (StateFlow: Status + StatsSnapshot) ──► UI, TetherTile
```

Key files: `proxy/ProxyServer.kt`, `hotspot/*Backend.kt`, `root/SoftApMain.kt`, `service/TetherService.kt`, `data/Config.kt`.

**Connect** shows the session controls, traffic counters and client connection details. **Clients** shows live proxy access and IP-based rules. **Settings** holds the connection mode, proxy, DNS and session settings. **Tools** holds network diagnostics and Wi-Fi Direct actions. Passwords are masked in settings, and the Wi-Fi QR and session credentials are revealed explicitly from **Connect**.

### Proxy DNS

HTTP and SOCKS hostname requests use the resolver selected when the proxy starts. System DNS is the default. The AdGuard preset sends DNS requests to AdGuard’s filtering endpoint over HTTPS. Custom mode takes a full DNS-over-HTTPS URL for a user-selected provider. Android resolves the HTTPS endpoint’s own hostname during bootstrap.

When the chosen HTTPS resolver fails, the proxy reports a connection error. Choose **System** and restart the proxy to use the phone’s resolver. Native tethering uses Android’s tethering DNS for clients that skip the proxy.

### VPN sharing

**Share VPN with proxy clients** requires Android to report a VPN as VictorTether’s active network. The service uses that network’s socket factory for outbound proxy connections and for HTTPS DNS requests. System lookups and HTTPS resolver bootstrap also use that network. When the active VPN is lost or changes, the proxy closes both sides of active tunnels. It resumes on an active VPN, subject to the original auto-stop timer.

**Open Victorious Mesh** launches the installed Mesh app. Connect and manage your Headscale profile in Mesh. The proxy uses the VPN routes that Android exposes to VictorTether. A Mesh exit node provides internet egress through that node. The proxy login gives connected clients the phone’s access to reachable services, so share it with the devices you intend to give that access.

## 4. Root helper (the unverified part)

`RootBackend` runs `CLASSPATH=<apk> app_process /system/bin com.victorious.tether.root.SoftApMain start <ssid-b64> <pass-b64> <hidden>` through `su -c`. The helper, as uid 0:

1. obtains a system `Context` via `ActivityThread.systemMain()`;
2. builds a `SoftApConfiguration` (WPA2-PSK, 2.4 GHz, hidden flag) by reflection;
3. stops any running AP, calls `WifiManager.setSoftApConfiguration`, then `TetheringManager.startTethering(TETHERING_WIFI, …)`;
4. prints `OK` or `ERR <reason>` as its last line, which the app surfaces in the UI.

Known risks: OEM ROMs may rename or restrict these APIs (Samsung especially) _[Likely]_; Android 15+ tethering-module changes could break the `startTethering(int, Executor, callback)` overload _[Guessing]_. If it fails, the UI shows the exact `ERR` line — start debugging there. Manual test: `adb shell su -c "CLASSPATH=$(pm path com.victorious.tether | cut -d: -f2) app_process /system/bin com.victorious.tether.root.SoftApMain stop"`.

**Hidden SSID is obscurity, not security.** Beacons still go out with an empty SSID, and the name is revealed when a client associates. Keep a strong WPA2 password. Clients join by scanning the in-app QR (it carries `H:true`) or by adding the network manually with "hidden network" enabled.

## 4b. Shizuku backend (also unverified)

`ShizukuBackend` binds `SoftApUserService` through `Shizuku.bindUserService`. Shizuku starts that class in its own process as the **shell** user, loaded from this APK, and the app talks to it over a bare Binder (codes `TX_START`/`TX_STOP`, one-string reply `OK` or `ERR …`). Both helpers call the same `SoftApControl`, so behaviour matches the root path.

The open question _[Guessing]_: whether the shell user holds the permissions `setSoftApConfiguration` and `startTethering` need (`NETWORK_SETTINGS` / `TETHER_PRIVILEGED`). `SoftApControl` acts as package `com.android.shell` when running as shell, which is the usual way to pass the package/uid check, but I could not confirm either point without a device. If it fails you will see `SecurityException: …` in the app. That message tells us exactly which permission is missing; if it is a hard block, Shizuku cannot do this on that device and root is the only route.

Flow: Shizuku app running (Wireless debugging or root) → choose **Shizuku** under Stealth → first start asks Shizuku for permission → hotspot starts.

## 4c. Upstream check

`util/Upstream.kt` reads the phone's default network (`ConnectivityManager`: transport and whether Android has validated internet) and asks the platform `WifiManager.isStaApConcurrencySupported()` whether Wi-Fi client + hotspot can run together. `Upstream.assess()` is pure logic (unit-tested) that turns that into OK/INFO/WARN/FAIL lines shown in the UPSTREAM panel, live while the app is open.

- Sharing Wi-Fi in a hotspot mode on a phone that reports **no** concurrency → FAIL (starting will probably drop the Wi-Fi being shared).
- Concurrency unknown → WARN. No connection → FAIL. Unconfirmed internet (login page, weak signal) → WARN.
- Proxy only is exempt from the concurrency rule because you supply the link.
- It **warns but never blocks** the start button: the platform's answer can be wrong on some ROMs _[Likely]_, so the user decides.
- If the connection vanishes mid-session the panel says so.

Not covered: the app cannot tell which Wi-Fi network (SSID) is the source without a location permission, so it reports only the transport.

## 4d. Wi-Fi Direct

`hotspot/WifiDirect.kt` wraps `WifiP2pManager` in coroutines. `WifiDirectBackend` requests a non-persistent group with a `DIRECT-vt-<ssid>` name and passphrase. The app can request 2.4, 5 or, from API 36, 6 GHz. With Automatic selected, Android chooses the band and can supply a default group if it rejects the custom configuration. **Connect** shows the actual name, password and group owner IPv4 address. The proxy listens at that address and forwards traffic from clients that use it.

Simultaneous Wi-Fi and Wi-Fi Direct depends on the phone. The public Wi-Fi Direct group configuration has no hidden-SSID setting. The hidden-SSID setting in Stealth applies to Android's native hotspot.

If the session starts with Wi-Fi as its upstream, outbound proxy connections and DNS stay bound to Wi-Fi. The proxy pauses when Android stops reporting Wi-Fi as the active route and resumes when it returns. When Android drops the Direct group, the foreground service tries to recreate it up to three times. Stop and the original auto-stop deadline still apply. Clients may need to reconnect to the recreated group, especially if Android assigns a new name or password.

**Tools** can check Wi-Fi Direct support, scan for peers, show the group and members, or remove a group while the app has no sharing session. Android can report placeholder hardware addresses to apps.

## 4e. Proxy behavior and uptime

- **Upstream network** can follow the active network or request a specific Wi-Fi or cellular route. Bound connections, UDP sockets and DNS use the same network. When the selected network goes away, clients wait for it to return.
- **USB tethering** requires Android’s USB tethering switch. VictorTether looks for a private IPv4 address on `rndis`, `usb` or `ncm` interfaces. Device-specific interfaces with other names may require **Proxy only** and manual endpoint selection.
- **Clients** shows successful proxy admissions by source IP. Each IP shares a rate and byte quota across connections. Rules affect the proxy only. SOCKS4a is offered when proxy authentication is off.
- **Restore session after Android restarts the service** is opt-in for proxy and Wi-Fi Direct modes. The persisted checkpoint keeps the selected route, timer and an identity hash for the Direct group. When Android restarts the service, VictorTether can resume its own group or create a new one. Stop discards the checkpoint. Client rules and their latest sampled counters are stored locally only for recoverable sessions.

## 5. Security decisions

- Proxy serves only private/link-local/loopback **sources** — never an open internet proxy.
- Proxy refuses destinations that resolve to loopback, wildcard, multicast, or a local interface address.
- A UDP relay accepts packets from its TCP client’s IP and pins the UDP sender port. Only contacted upstream endpoints can send a reply through it.
- Proxy-only mode forces login (it listens on whatever network you're on). Comparison is constant-time.
- Generated passwords use `SecureRandom`; helper arguments are base64 so no user text reaches a shell.
- No analytics, ads, or billing code. `allowBackup=false`.
- Not implemented on purpose: TTL/packet-header rewriting or other carrier-detection evasion. Check your plan's tethering terms yourself.

## 6. Relationship to the reference APK

TetherFuseNet `20260427-1` is published by pyamsoft under `com.pyamsoft.tetherfi`. The supplied APK and published guide were used as references for Wi-Fi Direct, USB tethering, upstream selection, client controls, SOCKS UDP and power settings. VictorTether implements those features in its own code and interface.

## 7. Maintenance

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug   # must stay green
# APK: app/build/outputs/apk/debug/Victorious-Tether-<version>-debug.apk
```
CI (`.github/workflows/build.yml`) does the same and uploads the APK. Bump `versionCode/versionName` in `app/build.gradle.kts`. For a release build you must create your own keystore; none is committed.

## 8. Remaining work

1. Device-test every path in §1's "not verified" list; fix what breaks.
2. Confirm on a device that the shell user can run SoftApControl (see §4b); add a fallback message if not.
3. Release signing and reproducible build notes.
