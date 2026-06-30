# WGFileSender Wire Protocol v2

The single source of truth for every client (macOS, Android, later Windows). All clients
speak plain **HTTP/1.1** over the WireGuard tunnel. WireGuard provides encryption and peer
authentication at the network layer, so the application layer only (a) verifies a
human-confirmed pairing and (b) authorizes each request with a shared token.

**Transfer model: pull.** The sender *offers* a manifest; the receiver *pulls* the bytes.
The receiver owns the durable state (what's left to fetch) and decides ordering, concurrency
and retries. A file is "done" only when the receiver has it complete and hash-verified on
disk — there is no ambiguity about whether a transfer half-happened.

## Transport

- Each client runs an HTTP listener bound to its WireGuard interface address.
- Default port: **51900** (configurable per device).
- A client reaches a peer at `http://<peer-wg-ip>:<peer-port>`.
- Peer WG IPs are entered manually once during pairing (WG addresses are static).

## Identity

Generated on first launch, stored locally:

| Field        | Description                                               |
|--------------|-----------------------------------------------------------|
| `deviceId`   | UUID, stable for the lifetime of the install              |
| `deviceName` | Human name the device advertises                          |
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
The responder validates it against the `tokenIn` it issued for that peer. This holds for
**both** directions — the sender authorizes `/offer`, the receiver authorizes `/pull` —
because each side presents its own `tokenOut` and the other validates its own `tokenIn`.

## Pairing (unchanged from v1)

### `GET /ping`
Liveness + identity probe. No auth (used before/while pairing).
```json
200 OK
{ "deviceId": "…", "deviceName": "Jaewon's Mac", "platform": "macos", "protocol": 2 }
```

### `POST /pair/request`
Initiator → responder. The initiator generates a **6-digit PIN** and includes it; the
responder displays it for the user to confirm it matches. MITM resistance comes from the
WireGuard tunnel, not the PIN. `sessionId` is reserved.
```json
{ "deviceId":"…","deviceName":"…","platform":"…","sessionId":"…","pin":"428917","port":51900 }
```
Responder: prompt, then on accept issue `tokenIn` for the initiator and reply; on decline or
**60s timeout**, `403`. Only one pairing prompt at a time.
```json
200 OK
{ "deviceId":"…","deviceName":"…","platform":"…","token":"<initiator's tokenOut>" }
```

### `POST /pair/confirm`  (auth: Bearer tokenOut)
Initiator → responder, completes the symmetric exchange. The caller may only confirm its own
pairing (`deviceId` must match the authenticated peer).
```json
{ "deviceId":"…","token":"<responder's tokenOut>" }
```
`204 No Content`.

## Transfers (pull model)

### `POST /offer`  (auth: Bearer tokenOut)

Sender → receiver. Announces a batch of files. The receiver stores the manifest durably,
creates a `pending` entry per file, and starts pulling in the background. The sender keeps
each file's local source reachable (path / persisted content-URI) so it can serve `/pull`.

Request:
```json
{
  "batchId": "uuid",
  "files": [
    { "fileId": "uuid", "name": "20260625_141234.mp4", "size": 734003200, "sha256": "<hex>" }
  ]
}
```
`X-WGFS-Device-Id` / `X-WGFS-Device-Name` headers identify the sender (for the subfolder).
Response: `200 OK` (manifest accepted) · `401` (bad token) · `403` (not paired).

### `GET /pull?batch=<batchId>&file=<fileId>`  (auth: Bearer tokenOut)

Receiver → sender. Streams the file's bytes. The receiver controls ordering, concurrency
(large files one-at-a-time, small files batched) and retries.

| Request header | Meaning                                          |
|----------------|--------------------------------------------------|
| `Range`        | `bytes=<start>-` to resume from a byte offset    |

Responses:
- `200 OK` — full file; `Content-Length` = size.
- `206 Partial Content` — ranged; `Content-Range: bytes <start>-<end>/<size>`.
- `401` bad token · `403` not paired · `404` unknown batch/file · `410` source no longer available.

The receiver writes to `<downloadRoot>/<resolved-sender-name>/<file-name>` via a
`<fileId>.part`, hashing as it writes; on full receipt + `sha256` match it moves the part to
the final name (collisions → ` (2)`, ` (3)`, …) and marks the file complete. A mismatch or
short read keeps the `.part` and the file is retried (resumed from `Range`).

There is no separate status endpoint: the receiver already knows how many bytes it holds
(its own `.part`) and simply pulls the rest.

## Versioning

`protocol` integer in `/ping` is `2`. v2 is **not** compatible with v1 (the push `/send`
endpoints are gone); clients refuse peers advertising a different major.
