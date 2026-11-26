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
| **Proxy tether, no root** | Starts Android’s LocalOnlyHotspot and serves HTTP, SOCKS4a and SOCKS5 on one port, with UDP relay for SOCKS5 clients. |
| **Stealth mode (root or Shizuku)** | Brings up a real WPA2 hotspot where *you* choose the SSID and password, and can **hide the SSID**. Uses root, or Shizuku so no root is needed. |
| **Wi-Fi Direct** | Share your existing Wi-Fi through a password-protected Wi-Fi Direct group and proxy. Requires a phone that supports both Wi-Fi links at once. Android advertises the `DIRECT-…` group name. |
| **Proxy only** | Runs just the proxy on a hotspot, Wi-Fi or USB link you already have. Login is forced. |
| **USB tethering** | Listens on the Android USB tethering interface and resumes the proxy when the USB link returns. Login is required. |
| **Client controls** | View per-IP usage and last activity, assign a nickname, block access, or set bandwidth and transfer limits. |
| **Upstream check** | Shows whether you are sharing Wi-Fi, mobile data or Ethernet, and warns *before* you start if your phone cannot keep Wi-Fi on while running a hotspot. |
| **Proxy DNS** | Choose Android’s system resolver, AdGuard DNS ad blocking, or a custom DNS-over-HTTPS endpoint. |
| **VPN sharing** | Share the phone’s active VPN with authenticated HTTP and SOCKS5 clients. Open Victorious Mesh from the proxy panel when it is installed. |
| **Join by QR** | The Wi-Fi QR code carries the hidden-network flag, so a phone can scan its way onto a hidden SSID. |
| **Session controls** | Foreground service, Quick Settings tile, proxy idle timeout, auto-stop timer, CPU wake lock and optional screen-on and session recovery settings. |
| **Clean** | No ads, no billing, no analytics. `allowBackup` off. |

> JVM tests cover TCP and UDP proxying, client quotas, DNS settings and upstream selection. CI runs the tests and builds the debug APK. Physical-device verification is pending for Wi-Fi Direct, USB tethering, live VPN routing, and the root and Shizuku helpers. See [GUIDE.md](GUIDE.md).

## Pick a mode

| Mode | Root | SSID | You set SSID / password | How clients get internet |
|---|:---:|---|:---:|---|
| **Proxy · no root** | no | visible (Android's limit) | no | set an HTTP or SOCKS5 proxy to `ip:port` |
| **Wi-Fi Direct** | no | `DIRECT-…`, cannot hide | partly | set an HTTP or SOCKS5 proxy to `ip:port` |
| **Stealth · root / Shizuku** | root _or_ Shizuku | **hidden** | yes | normal tethering (NAT), proxy optional |
| **Proxy only** | no | n/a | n/a | you supply the link; proxy login required |
| **USB tethering** | no | n/a | n/a | enable Android USB tethering and configure an authenticated proxy on the client |

### Hidden SSIDs in Stealth mode

Android only gives ordinary apps `startLocalOnlyHotspot`, which accepts no configuration. Setting a hidden SSID uses `SoftApConfiguration` and `WifiManager#setSoftApConfiguration`, both system-only. Stealth mode therefore runs a small helper with higher privileges that calls them: as root (via `su` and `app_process`), or as the shell user through [Shizuku](https://shizuku.rikka.app/), which needs no root. The Shizuku route is new and unverified on hardware.

> A hidden SSID is obscurity, not security. Beacons still go out with an empty name, and the name is revealed when a client associates. Keep a strong WPA2 password.

## Quick start

1. Install `Victorious-Tether-0.1.0-debug.apk` (from the CI run's artifacts, or build it below).
2. Pick a connection mode in **Settings**. Open **Connect** and tap **Start tethering**. Grant the requested Wi-Fi, Nearby Devices, and notification permissions.
3. **Proxy · no root** and **Wi-Fi Direct** clients join the network shown on **Connect**, then use the HTTP or SOCKS5 endpoint under **Proxy address**.
4. For **Stealth**, select **Root** or **Shizuku**, enter the network name and password, and enable **Hide network name**. Start tethering, then choose **Show QR and password** or add the hidden network manually on the client.

Open **Tools** for upstream checks and Wi-Fi Direct discovery.

### Share your current Wi-Fi through Wi-Fi Direct

1. Keep the host phone connected to Wi-Fi. Select **Wi-Fi Direct · no root** in **Settings**, then tap **Start tethering** in **Connect**.
2. On the other phone, scan **Show QR and password** or join the indicated `DIRECT-…` network with that password. Use the address shown under **Proxy address** in the client's Wi-Fi HTTP proxy settings or in an HTTP or SOCKS5 client. Only traffic using the proxy is forwarded.
3. Leave **Auto-stop** at `0` for a session without a timer. If upstream Wi-Fi drops, the proxy pauses and resumes when Wi-Fi returns. If Android removes the Direct group, VictorTether makes up to three attempts to recreate it. Check **Connect** for new credentials if Android assigns a different group.

Android's public Wi-Fi Direct configuration has no hidden-SSID setting. The Shizuku helper controls the separate Stealth hotspot mode.

### Choose a route and manage clients

- Under **Settings → Proxy**, choose **Automatic**, **Active network**, **Wi-Fi**, or **Mobile data**. With Wi-Fi or Mobile data selected, proxy traffic waits for that network when it’s unavailable. **Share VPN with proxy clients** takes priority.
- In **Clients**, tap **Manage client** after a device uses the proxy. A block closes its live TCP tunnels and UDP associations. The rate limit and transfer limit cover uploads and downloads together. The values apply to the client's IP for this session.
- Use the same **Proxy address** for HTTP, SOCKS4a, or SOCKS5. SOCKS4a is available for sessions with proxy login off. For UDP, use a client with SOCKS5 UDP ASSOCIATE support. Android’s manual Wi-Fi HTTP proxy setting handles HTTP and HTTPS traffic.
- For **USB tethering**, connect a USB data cable, enable Android USB tethering, then start VictorTether in USB mode. Configure the displayed proxy endpoint on the client to use VictorTether’s routing, DNS and client controls.

### Keep a session running

**Settings → Session** includes the timer, CPU wake lock, the optional screen-on setting, and a link to Android battery settings. **Restore session after Android restarts the service** lets Android resume an interrupted proxy or Wi-Fi Direct session with its original route and auto-stop deadline. Client rules resume with their latest saved counters. Android handles restart timing. Stop, force-stop and reboot end the session. For Wi-Fi Direct, select 2.4 GHz or 5 GHz if supported. An option for 6 GHz is available on Android 16 or newer and requires compatible hardware.

### Custom DNS and ad blocking

In **Settings** under **Proxy DNS**, choose **System**, **AdGuard ad blocking**, or **Custom DNS over HTTPS**. For a custom resolver, enter the provider’s full HTTPS DNS URL. Start the proxy to apply your selection.

The selected resolver handles hostnames sent to the HTTP or SOCKS5 proxy. Enable remote hostname resolution in your SOCKS5 client. Clients using native tethering directly use Android’s tethering DNS. AdGuard receives the proxy’s hostname lookups when you select its preset.

### Share your VPN with connected devices

1. Connect Victorious Mesh or another VPN that includes VictorTether in its app routing. Select an exit node in Mesh if you want to share the exit node’s public internet IP.
2. In **Settings**, enable **Share VPN with proxy clients**. Choose **System** DNS to resolve Mesh hostnames through MagicDNS. Check the proxy login and start the session from **Connect**.
3. Join from the other device and use the local endpoint under **Proxy address**. Open **Show proxy login** if you need the credentials. For SOCKS5 hostnames, enable DNS resolution through the proxy.

Proxy clients reach Mesh services through the phone’s Mesh identity. Each device needs its own VPN enrollment for a separate Mesh IP. Native Android tethering uses its own routing. If the active VPN disconnects, the proxy closes open connections and pauses until Android reports an active VPN for VictorTether again.

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
   ├─ ProxyServer     HTTP, SOCKS4a, SOCKS5 TCP and UDP on one proxy entry point
   └─ TetherState     StateFlow ──► UI and Quick Settings tile
```

## Safety by design

- Serves only private, link-local and loopback **sources**. It is never an open internet proxy.
- Refuses **destinations** that resolve to loopback, wildcard or multicast, so clients cannot reach services on the phone.
- Proxy login is compared in constant time and is required for Proxy only, USB tethering and shared VPN sessions.
- Passwords come from `SecureRandom`. Root-helper arguments are base64-encoded, so no user text reaches a shell.
- Deliberately **not** included: TTL or packet rewriting and other carrier-detection evasion. Check your plan's tethering terms yourself.

## Roadmap

- [ ] On-device verification of every path (see GUIDE.md §1)
- [ ] Verify the Shizuku backend on a device (code is in; shell-user permissions unconfirmed)
- [x] Pin upstream traffic to cellular or Wi-Fi
- [x] Connected proxy client list and access controls
- [x] SOCKS5 UDP ASSOCIATE
- [ ] Release signing and reproducible-build notes

## Documentation

[GUIDE.md](GUIDE.md) is the living guide: status, architecture, decisions, maintenance and remaining work. It is updated in the same commit as any behaviour change.

## Credits and licence

Written from scratch. It shares no code with the TetherFi app that inspired it. **No licence file has been added yet**, so by default all rights are reserved. Tell the maintainer which licence you want and it will be added.
