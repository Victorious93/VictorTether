# Victorious Tether — Victorious Framework living guide

_Last synchronised with code: v0.1.0 + Shizuku backend + upstream check + Wi-Fi Direct. Update this file in the same commit as any behaviour change._

## 1. Status

| State | Item |
|---|---|
| Finished | Proxy server (HTTP, CONNECT, SOCKS5, auth, source/destination guards) — **13 JVM tests pass** |
| Finished | Config validation, Wi-Fi QR payload, foreground service, Quick Settings tile, Compose UI |
| Finished | Debug APK builds, `lintDebug` clean |
| In Progress | Nothing |
| Finished (unverified on device) | Shizuku backend: Stealth without root |
| Finished (logic tested, UI unverified) | Upstream check: shows what you are sharing and warns before it fails — **21 JVM tests pass** |
| Finished (unverified on device) | Wi-Fi Direct mode + tools panel — **24 JVM tests pass** (naming, validation, advice logic only) |
| Deferred | Per-client list for proxy modes, upstream-network pinning, release signing |
| Abandoned | Copying/decompiling the reference app (see §6) |

**NOT verified on a real phone:** LocalOnlyHotspot start, the root helper, hidden-SSID behaviour, the tile, the notification. No device or emulator was available. Treat §4 as _[Likely]_, not _[Certain]_, until you have run it.

## 2. Modes

| Mode | Root | SSID visible | You choose SSID/password | Internet path for clients |
|---|---|---|---|---|
| Proxy · no root | no | **yes** (platform limit) | no (Android picks) | client must set HTTP/SOCKS proxy to `ip:port` |
| Wi-Fi Direct · no root | no | visible, starts with `DIRECT-`, **cannot be hidden** | partly (many phones ignore it) | client must set proxy to `ip:port` |
| Stealth · root **or Shizuku** | root _or_ Shizuku | **hidden** | yes | native tethering (NAT) — proxy optional |
| Proxy only | no | n/a | n/a | you bring the link (system hotspot, USB…); login forced |

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
   ├─ ProxyServer  (one port, sniffs 0x05 → SOCKS5 else HTTP)
   └─ TetherState  (StateFlow: Status + StatsSnapshot) ──► UI, TetherTile
```

Key files: `proxy/ProxyServer.kt`, `hotspot/*Backend.kt`, `root/SoftApMain.kt`, `service/TetherService.kt`, `data/Config.kt`.

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

`hotspot/WifiDirect.kt` wraps `WifiP2pManager` in coroutines. `WifiDirectBackend` creates a group (custom `DIRECT-vt-<ssid>` name and passphrase, 2.4 GHz, non-persistent), falls back to a system-chosen group if the phone rejects the custom one, then reads back the **real** name and passphrase and shows those. Like LocalOnlyHotspot, a P2P group has no internet routing, so the proxy runs on top of it.

Why it exists: `LocalOnlyHotspot` and tethering often cannot run while the phone is on Wi-Fi; Wi-Fi Direct usually can _[Likely]_, so it is the better route for sharing an existing Wi-Fi connection. The cost is that the name always starts with `DIRECT-` and cannot be hidden by any public API _[Likely]_.

Tools panel: **Check support** (hardware feature, P2P switched on, hotspot+Wi-Fi concurrency, current source), **Scan nearby** (5 s peer discovery), **Show group & members** (group name, owner/member, member list), **Remove group**. Android hides real hardware addresses from apps, so addresses may be placeholders.

Not done: the app is not told if the group is removed by the system or the user while running (no P2P broadcast receiver yet), so the session can keep showing ONLINE after the group is gone. Use **Show group & members** to check.

## 5. Security decisions

- Proxy serves only private/link-local/loopback **sources** — never an open internet proxy.
- Proxy refuses **destinations** that resolve to loopback/wildcard/multicast, so clients can't reach services on the phone.
- Proxy-only mode forces login (it listens on whatever network you're on). Comparison is constant-time.
- Generated passwords use `SecureRandom`; helper arguments are base64 so no user text reaches a shell.
- No analytics, ads, or billing code. `allowBackup=false`.
- Not implemented on purpose: TTL/packet-header rewriting or other carrier-detection evasion. Check your plan's tethering terms yourself.

## 6. Relationship to the reference APK

The APK you supplied (`TetherFuseNet_v20260427-1`) is, by its package and strings, pyamsoft's open-source TetherFi (`com.pyamsoft.tetherfi`) _[Likely]_. It uses Netty/Ktor, Play Billing and Firebase, and has no hidden-SSID setting. Victorious Tether shares no code with it; the gaps it fills are the stealth backend, SOCKS5 + auth, a smaller dependency footprint, and no billing/telemetry.

## 7. Maintenance

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug   # must stay green
# APK: app/build/outputs/apk/debug/Victorious-Tether-<version>-debug.apk
```
CI (`.github/workflows/build.yml`) does the same and uploads the APK. Bump `versionCode/versionName` in `app/build.gradle.kts`. For a release build you must create your own keystore; none is committed.

## 8. Remaining work

1. Device-test every path in §1's "not verified" list; fix what breaks.
2. Confirm on a device that the shell user can run SoftApControl (see §4b); add a fallback message if not.
3. Pin upstream traffic to a chosen network (cellular vs Wi-Fi) via `ConnectivityManager.bindProcessToNetwork`.
4. Connected-client list and per-connection log.
5. Listen for `WIFI_P2P_CONNECTION_CHANGED_ACTION` so the session stops when the group disappears.
6. SOCKS5 UDP ASSOCIATE (currently refused with reply 0x07).
7. Release signing + reproducible build notes.
