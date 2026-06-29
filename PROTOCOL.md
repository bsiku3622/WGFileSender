# WGFileSender Wire Protocol v1

The single source of truth for every client implementation (macOS, Android, later Windows).
All clients speak plain **HTTP/1.1** over the WireGuard tunnel. WireGuard already provides
encryption and peer authentication at the network layer, so the application layer only needs
to (a) verify a human-confirmed pairing and (b) authorize each request with a shared token.

## Transport

- Each client runs an HTTP listener bound to its WireGuard interface address.
- Default port: **51900** (configurable per device).
- A client reaches a peer at `http://<peer-wg-ip>:<peer-port>`.
- Peer WG IPs are entered manually once during pairing (WG addresses are static).

## Identity

Every device has, generated on first launch and stored locally:

| Field        | Description                                               |
|--------------|-----------------------------------------------------------|
| `deviceId`   | UUID, stable for the lifetime of the install              |
| `deviceName` | Human name the device advertises (sender-default)         |
| `platform`   | `"macos"` \| `"android"` \| `"windows"`                   |

A pairing produces, on **each** side, a record about the peer:

| Field          | Description                                                  |
|----------------|-------------------------------------------------------------|
| `peerId`       | Peer's `deviceId`                                            |
| `peerName`     | Name peer advertised (receiver may override locally)        |
| `localName`    | Optional local override; falls back to `peerName`           |
| `peerAddress`  | `host:port` used to reach the peer                          |
| `tokenOut`     | Token THIS device sends to the peer (peer issued it)        |
| `tokenIn`      | Token THIS device expects FROM the peer (this device issued)|

Authorization: every authenticated request carries `Authorization: Bearer <tokenOut>`.
The receiver validates the bearer token against the `tokenIn` it issued for that peer.

## Endpoints

### `GET /ping`

Liveness + identity probe. No auth required (used before/while pairing).

```json
200 OK
{ "deviceId": "…", "deviceName": "Jaewon's Mac", "platform": "macos", "protocol": 1 }
```

### `POST /pair/request`

Initiator → responder. Opens a pairing session; responder shows an accept prompt.
The initiator generates a **6-digit PIN** and includes it in the request; the responder
displays it so the user can confirm it matches the digits shown on the initiator. This is a
human "is this the request I just started?" check — actual MITM resistance comes from the
WireGuard tunnel, not from the PIN (the PIN travels inside the request, so it is not a
zero-knowledge proof). `sessionId` is carried for future use and is not currently validated.

Request:
```json
{ "deviceId": "…", "deviceName": "Jaewon's Mac", "platform": "macos",
  "sessionId": "…", "pin": "428917", "port": 51900 }
```

`port` is the initiator's own listener port. The responder combines it with the source
IP of the TCP connection to form the address it will use to reach the initiator back
(the initiator already knows the responder's address — the user typed it).

Responder behavior:
- Display prompt: "<deviceName> wants to pair — PIN 428 917".
- On user **accept**: issue `tokenIn` for the initiator, respond below.
- On user **decline** or timeout (60s): `403`.

Response on accept:
```json
200 OK
{ "deviceId": "…", "deviceName": "Jaewon's Android", "platform": "android",
  "token": "<token the initiator should use as tokenOut>" }
```

The initiator, on `200`, stores the peer record and issues its **own** `tokenIn` for the
peer, then pushes it via `POST /pair/confirm` so the link is symmetric.

### `POST /pair/confirm`  (auth: Bearer tokenOut)

Initiator → responder, completes the symmetric exchange.
```json
{ "deviceId": "…", "token": "<token the responder should use as tokenOut>" }
```
Response: `204 No Content`.

After this, both sides hold `{tokenOut, tokenIn}` for each other and pairing is complete.

### `POST /send`  (auth: Bearer tokenOut)

Streams one file. Metadata in headers, raw bytes in the body.

| Header                  | Meaning                                              |
|-------------------------|------------------------------------------------------|
| `X-WGFS-Device-Id`      | Sender `deviceId` (must match a paired peer)         |
| `X-WGFS-Device-Name`    | Sender's advertised name (used for the subfolder)    |
| `X-WGFS-File-Name`      | Original filename (percent-encoded UTF-8)            |
| `X-WGFS-File-Size`      | Total bytes (decimal)                                |
| `X-WGFS-Sha256`         | Lowercase hex digest of the full file                |
| `X-WGFS-Transfer-Id`    | UUID identifying this transfer (for resume)          |
| `Content-Length`        | Bytes in THIS request (may be < file size on resume) |
| `Content-Range`         | Optional `bytes start-end/total` for resume          |

The receiver streams the body to a `<transferId>.part` file, hashing as it writes, then
moves it to `<downloadRoot>/<resolved-sender-name>/<file-name>` on success.
`resolved-sender-name` is the receiver's `localName` override or the sender's
`X-WGFS-Device-Name`. Filename collisions are resolved by appending ` (2)`, ` (3)`, …

**Resume.** If `Content-Range`'s start byte equals the size of the existing `.part`, the
receiver appends (priming its hash with the bytes already on disk); otherwise it starts the
`.part` fresh. If the connection drops mid-body, the receiver **keeps the `.part`** so the
sender can resume later (it does not treat a short read as a hash failure). The sender
discovers the resume point with `GET /send/status` and re-`POST`s with a `Content-Range`.

Responses: `200 OK` on complete body + hash match · `409 Conflict` (full file received but
hash mismatched — the `.part` is discarded as corrupt) · `400` (incomplete body; `.part`
kept for resume) · `401` (bad token) · `403` (sender not paired).

### `GET /send/status?transferId=…`  (auth: Bearer tokenOut)

Lets a sender resume: returns the number of bytes already on the receiver's `.part`
(0 if none). The sender then re-sends from that offset with a `Content-Range` header.
```json
200 OK
{ "transferId": "…", "received": 1048576 }
```

## Versioning

`protocol` integer in `/ping` is bumped on breaking changes. v1 clients refuse peers
advertising a higher major they don't understand.
