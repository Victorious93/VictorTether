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
![Tests](https://img.shields.io/badge/JVM%20tests-13%20passing-39FF14)
![Device tested](https://img.shields.io/badge/on--device-not%20yet%20verified-FFB000)

</div>

---

## What it does

| | |
|---|---|
| **Proxy tether, no root** | Starts Android's LocalOnlyHotspot and serves an HTTP / CONNECT / SOCKS5 proxy on one port. |
| **Stealth mode (root)** | Brings up a real WPA2 hotspot where *you* choose the SSID and password, and can **hide the SSID**. |
| **Proxy only** | Runs just the proxy on a hotspot, Wi-Fi or USB link you already have. Login is forced. |
| **Join by QR** | The Wi-Fi QR code carries the hidden-network flag, so a phone can scan its way onto a hidden SSID. |
| **Quality of life** | Foreground service, Quick Settings tile, live traffic counters, auto-stop timer, generated passwords. |
| **Clean** | No ads, no billing, no analytics. `allowBackup` off. |

> **Honest status:** the proxy is covered by 13 passing JVM tests that use real loopback sockets, and CI builds the APK. Hotspot start-up, the root helper, hidden-SSID behaviour, the tile and the notification have **not** been run on a physical device yet. Details in [GUIDE.md](GUIDE.md) §1 and §4.

## Pick a mode

| Mode | Root | SSID | You set SSID / password | How clients get internet |
|---|:---:|---|:---:|---|
| **Proxy · no root** | no | visible (Android's limit) | no | set an HTTP or SOCKS5 proxy to `ip:port` |
| **Stealth · root** | yes | **hidden** | yes | normal tethering (NAT), proxy optional |
| **Proxy only** | no | n/a | n/a | you supply the link; proxy login required |

### Why hiding the SSID needs root

Android only gives ordinary apps `startLocalOnlyHotspot`, which accepts no configuration. Setting a hidden SSID uses `SoftApConfiguration` and `WifiManager#setSoftApConfiguration`, both system-only. Stealth mode therefore runs a small helper as root (via `su` and `app_process`) that calls them.

> A hidden SSID is obscurity, not security. Beacons still go out with an empty name, and the name is revealed when a client associates. Keep a strong WPA2 password.

## Quick start

1. Install `Victorious-Tether-0.1.0-debug.apk` (from the CI run's artifacts, or build it below).
2. Open the app, pick a mode, and tap **[ START ]**. Grant the Wi-Fi / Nearby-devices and notification prompts.
3. **Proxy · no root:** join the hotspot shown, then set your device's HTTP or SOCKS5 proxy to the `PROXY*` address and port.
4. **Stealth · root:** set SSID and password, turn on **Hide SSID**, start, and join by scanning the QR code, or add the network manually with *hidden network* enabled.

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
   │                  └─ RootBackend ─su─►  SoftApMain (uid 0, reflection)
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
- [ ] Shizuku backend, so Stealth works without root
- [ ] Pin upstream traffic to cellular or Wi-Fi
- [ ] Connected-client list and per-connection log
- [ ] SOCKS5 UDP ASSOCIATE
- [ ] Release signing and reproducible-build notes

## Documentation

[GUIDE.md](GUIDE.md) is the living guide: status, architecture, decisions, maintenance and remaining work. It is updated in the same commit as any behaviour change.

## Credits and licence

Written from scratch. It shares no code with the TetherFi app that inspired it. **No licence file has been added yet**, so by default all rights are reserved. Tell the maintainer which licence you want and it will be added.
