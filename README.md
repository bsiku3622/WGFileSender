<div align="center">

<img src="docs/icon.png" width="112" alt="WGFileSender icon" />

# WGFileSender

**Peer-to-peer file transfer between your own devices over a WireGuard tunnel.**
No relay server, no cloud — each device talks directly to the others on the VPN.

</div>

---

## How it works

Every device runs a small HTTP listener bound to its WireGuard address and reaches
peers at their static WG IPs. Two devices are linked once through a mutual pairing —
both sides accept and a 6-digit PIN confirms it's the right device — after which files
are sent directly. Received files land in `downloadRoot/<sender-name>/`.

Security leans on the WireGuard tunnel for encryption and peer authentication; the app
layer adds a per-peer bearer token established during pairing. The full wire format is
documented in [`PROTOCOL.md`](./PROTOCOL.md) — both clients implement the same spec.

## Features

- **Direct P2P** over WireGuard — no server in the middle
- **Mutual PIN pairing** — confirm it's your device on both ends
- **Per-device folders** — files arrive under the sender's name (renamable locally)
- **Integrity checked** — every transfer is SHA-256 verified, collisions auto-renamed
- **Background receive** — macOS menu-bar app · Android foreground service
- **Native UI** — SwiftUI on macOS, Jetpack Compose (shadcn-style) on Android

## Platforms

| Platform | Stack | Status |
|----------|-------|--------|
| macOS    | SwiftUI / Network.framework | ✅ Working |
| Android  | Jetpack Compose / Ktor      | ✅ Working |
| Windows  | —                           | Planned |

## Build

### macOS

Requires Xcode command-line tools (Swift 5.9+).

```sh
cd macos
./build.sh            # compiles with SwiftPM and assembles WGFileSender.app
open WGFileSender.app
```

### Android

Open `android/` in Android Studio and Run on a device — or build from the CLI with
JDK 17 + the Android SDK:

```sh
cd android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

First-run setup on the phone:
1. Grant the notification permission (keeps the receive service alive).
2. **Settings → Choose folder** to pick the download root (required to receive).
3. **Devices → Add** and enter a peer's WireGuard IP to pair.

Send files via the Devices tab (**Send** on a peer) or by sharing from any app to
WGFileSender.

## Protocol

See [`PROTOCOL.md`](./PROTOCOL.md) for the HTTP wire format (ping, pairing handshake,
streaming send with resume) that every client implements.

## License

[MIT](./LICENSE) © Jaewon Baek
