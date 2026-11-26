<div align="center">

```text
 __     ___      _             _                    _____     _   _
 \ \   / (_) ___| |_ ___  _ __(_) ___  _   _ ___  |_   _|__ | |_| |__   ___ _ __
  \ \ / /| |/ __| __/ _ \| '__| |/ _ \| | | / __|   | |/ _ \| __| '_ \ / _ \ '__|
   \ V / | | (__| || (_) | |  | | (_) | |_| \__ \   | |  __/| |_| | | |  __/ |
    \_/  |_|\___|\__\___/|_|  |_|\___/ \__,_|___/   |_|\___| \__|_| |_|\___|_|
```

**Victorious Tether** — share your phone's connection through a built-in proxy, or run a real hotspot with a **hidden SSID**.
*A Victorious Framework project.*

[![Build](https://github.com/Victorious93/VictorTether/actions/workflows/build.yml/badge.svg)](https://github.com/Victorious93/VictorTether/actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/Android-11%2B-39FF14?logo=android&logoColor=black)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-0A0F0A?logo=kotlin&logoColor=39FF14)
![Tests](https://img.shields.io/badge/JVM%20tests-proxy%2C%20config%20and%20Wi--Fi-39FF14)
![Device tested](https://img.shields.io/badge/on--device-not%20yet%20verified-FFB000)

</div>

---

## What it does

| | |
|---|---|
| **Proxy tether, no root** | Starts Android's LocalOnlyHotspot and serves an HTTP / CONNECT / SOCKS5 proxy on one port. |
| **Stealth mode (root or Shizuku)** | Brings up a real WPA2 hotspot where *you* choose the SSID and password, and can **hide the SSID**. Uses root, or Shizuku so no root is needed. |
| **Wi-Fi Direct** | A Wi-Fi Direct group plus the proxy. Usually works while your phone stays on Wi-Fi, so it is the best route for sharing existing Wi-Fi. Name starts with `DIRECT-` and cannot be hidden. Includes tools to check support, scan nearby devices and list group members. |
| **Proxy only** | Runs just the proxy on a hotspot, Wi-Fi or USB link you already have. Login is forced. |
| **Upstream check** | Shows whether you are sharing Wi-Fi, mobile data or Ethernet, and warns *before* you start if your phone cannot keep Wi-Fi on while running a hotspot. |
| **Proxy DNS** | Choose Android’s system resolver, AdGuard DNS ad blocking, or a custom DNS-over-HTTPS endpoint. |
| **VPN sharing** | Share the phone’s active VPN with authenticated HTTP and SOCKS5 clients. Open Victorious Mesh from the proxy panel when it is installed. |
| **Join by QR** | The Wi-Fi QR code carries the hidden-network flag, so a phone can scan its way onto a hidden SSID. |
| **Quality of life** | Foreground service, Quick Settings tile, live traffic counters, auto-stop timer, generated passwords. |
| **Clean** | No ads, no billing, no analytics. `allowBackup` off. |

> JVM tests exercise the proxy over loopback sockets and check DNS configuration. CI runs the tests and builds the debug APK. Physical-device verification is pending for hotspot start-up, the root and Shizuku helpers, hidden-SSID behaviour, the tile, and the notification. See [GUIDE.md](GUIDE.md) §1 and §4.

## Pick a mode

| Mode | Root | SSID | You set SSID / password | How clients get internet |
|---|:---:|---|:---:|---|
| **Proxy · no root** | no | visible (Android's limit) | no | set an HTTP or SOCKS5 proxy to `ip:port` |
| **Wi-Fi Direct** | no | `DIRECT-…`, cannot hide | partly | set an HTTP or SOCKS5 proxy to `ip:port` |
| **Stealth · root / Shizuku** | root _or_ Shizuku | **hidden** | yes | normal tethering (NAT), proxy optional |
| **Proxy only** | no | n/a | n/a | you supply the link; proxy login required |

### Why hiding the SSID needs root or Shizuku

Android only gives ordinary apps `startLocalOnlyHotspot`, which accepts no configuration. Setting a hidden SSID uses `SoftApConfiguration` and `WifiManager#setSoftApConfiguration`, both system-only. Stealth mode therefore runs a small helper with higher privileges that calls them: as root (via `su` and `app_process`), or as the shell user through [Shizuku](https://shizuku.rikka.app/), which needs no root. The Shizuku route is new and unverified on hardware.

> A hidden SSID is obscurity, not security. Beacons still go out with an empty name, and the name is revealed when a client associates. Keep a strong WPA2 password.

## Quick start

1. Install `Victorious-Tether-0.1.0-debug.apk` (from the CI run's artifacts, or build it below).
2. Pick a connection mode in **Settings**. Open **Connect** and tap **Start tethering**. Grant the requested Wi-Fi, Nearby Devices, and notification permissions.
3. **Proxy · no root** and **Wi-Fi Direct** clients join the network shown on **Connect**, then use the HTTP or SOCKS5 endpoint under **Proxy address**.
4. For **Stealth**, select **Root** or **Shizuku**, enter the network name and password, and enable **Hide network name**. Start tethering, then choose **Show QR and password** or add the hidden network manually on the client.

Open **Tools** for upstream checks and Wi-Fi Direct discovery.

### Custom DNS and ad blocking

In **Settings** under **Proxy DNS**, choose **System**, **AdGuard ad blocking**, or **Custom DNS over HTTPS**. For a custom resolver, enter the provider’s full HTTPS DNS URL. Start the proxy to apply your selection.

The selected resolver handles hostnames sent to the HTTP or SOCKS5 proxy. Enable remote hostname resolution in your SOCKS5 client. Clients using native tethering directly use Android’s tethering DNS. AdGuard receives the proxy’s hostname lookups when you select its preset.

### Share your VPN with connected devices

1. Connect Victorious Mesh or another VPN that includes VictorTether in its app routing. Select an exit node in Mesh if you want to share the exit node’s public internet IP.
2. In **Settings**, enable **Share VPN with proxy clients**. Choose **System** DNS to resolve Mesh hostnames through MagicDNS. Check the proxy login and start the session from **Connect**.
3. Join from the other device and use the local endpoint under **Proxy address**. Open **Show proxy login** if you need the credentials. For SOCKS5 hostnames, enable DNS resolution through the proxy.

Proxy clients reach Mesh services through the phone’s Mesh identity. Each device needs its own VPN enrollment for a separate Mesh IP. Native Android tethering uses its own routing. VictorTether pins the proxy session to the active VPN and ends the session if that connection is lost or replaced.

## Build from source

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug lintDebug
# APK -> app/build/outputs/apk/debug/Victorious-Tether-0.1.0-debug.apk
```

The debug APK is debug-signed and installable. For a release build, create your own keystore; none is committed.

## How it works

```text
MainActivity (Compose) ──► ConfigStore
        │ start / stop
        ▼
TetherService (foreground)
   ├─ HotspotBackend ─┬─ LocalOnlyBackend   public WifiManager API
   │                  ├─ RootBackend ─su─►  SoftApMain (uid 0) ──┐
   │                  └─ ShizukuBackend ─►  SoftApUserService ───┴─► SoftApControl
   ├─ ProxyServer     one port: 0x05 → SOCKS5, otherwise HTTP
   └─ TetherState     StateFlow ──► UI and Quick Settings tile
```

## Safety by design

- Serves only private, link-local and loopback **sources**. It is never an open internet proxy.
- Refuses **destinations** that resolve to loopback, wildcard or multicast, so clients cannot reach services on the phone.
- Proxy login is compared in constant time and is forced in Proxy-only mode.
- Passwords come from `SecureRandom`. Root-helper arguments are base64-encoded, so no user text reaches a shell.
- Deliberately **not** included: TTL or packet rewriting and other carrier-detection evasion. Check your plan's tethering terms yourself.

## Roadmap

- [ ] On-device verification of every path (see GUIDE.md §1)
- [ ] Verify the Shizuku backend on a device (code is in; shell-user permissions unconfirmed)
- [ ] Pin upstream traffic to cellular or Wi-Fi
- [ ] Connected-client list and per-connection log
- [ ] SOCKS5 UDP ASSOCIATE
- [ ] Release signing and reproducible-build notes

## Documentation

[GUIDE.md](GUIDE.md) is the living guide: status, architecture, decisions, maintenance and remaining work. It is updated in the same commit as any behaviour change.

## Credits and licence

Written from scratch. It shares no code with the TetherFi app that inspired it. **No licence file has been added yet**, so by default all rights are reserved. Tell the maintainer which licence you want and it will be added.
