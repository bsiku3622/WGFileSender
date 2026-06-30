import Foundation

/// Wire protocol version this build implements. See PROTOCOL.md.
let kProtocolVersion = 2
let kDefaultPort: UInt16 = 51900

/// This device's stable identity, advertised to peers.
struct Identity: Codable {
    var deviceId: String
    var deviceName: String
    let platform: String

    static func fresh(name: String) -> Identity {
        Identity(deviceId: UUID().uuidString, deviceName: name, platform: "macos")
    }
}

/// A paired peer, with the symmetric tokens established during pairing.
struct PeerDevice: Codable, Identifiable, Equatable {
    var peerId: String
    var peerName: String          // name the peer advertises
    var localName: String?        // local override (receiver-side rename)
    var peerAddress: String       // host:port
    var tokenOut: String          // sent to the peer (peer issued it)
    var tokenIn: String           // expected from the peer (we issued it)
    var lastSeen: Date?

    var id: String { peerId }
    var displayName: String { localName?.isEmpty == false ? localName! : peerName }
}

enum TransferDirection: String, Codable { case incoming, outgoing }
/// `pending` = known from the manifest but not started (receiver hasn't pulled yet / sender
/// is waiting to serve). `queued` = waiting for a concurrency slot. `interrupted` = stopped
/// but resumable. `failed` = terminal (e.g. hash mismatch on a fully-received file).
enum TransferState: String, Codable { case pending, queued, active, completed, failed, interrupted }

/// One file within a batch — the unit shown in the Transfers tab. The receiver owns the
/// durable copy of incoming transfers and pulls the bytes; the sender keeps outgoing ones
/// so it can serve `/pull`.
struct Transfer: Codable, Identifiable, Equatable {
    var id: String                 // fileId
    var batchId: String = ""
    var direction: TransferDirection
    var peerName: String
    var peerId: String = ""
    var peerAddress: String = ""   // receiver pulls the bytes from here
    var fileName: String
    var totalBytes: Int64
    var transferredBytes: Int64
    var sha256: String = ""
    var state: TransferState
    var startedAt: Date
    var error: String?
    var localPath: String? = nil   // sender: source path; receiver: saved path

    var progress: Double {
        totalBytes > 0 ? min(1, Double(transferredBytes) / Double(totalBytes)) : 0
    }
}

// MARK: - JSON payloads (mirror PROTOCOL.md)

struct PingResponse: Codable {
    let deviceId: String
    let deviceName: String
    let platform: String
    let `protocol`: Int
}

struct PairRequestBody: Codable {
    let deviceId: String
    let deviceName: String
    let platform: String
    let sessionId: String
    let pin: String
    let port: UInt16        // initiator's listen port; combined with the connection's
                            // source IP so the responder can reach back.
}

struct PairAcceptResponse: Codable {
    let deviceId: String
    let deviceName: String
    let platform: String
    let token: String   // token the initiator should use as tokenOut
}

struct PairConfirmBody: Codable {
    let deviceId: String
    let token: String   // token the responder should use as tokenOut
}

/// One file in a batch manifest (sender → receiver via POST /offer).
struct OfferFile: Codable {
    let fileId: String
    let name: String
    let size: Int64
    let sha256: String
}

struct OfferBody: Codable {
    let batchId: String
    let files: [OfferFile]
}
