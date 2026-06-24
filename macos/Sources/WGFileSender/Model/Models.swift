import Foundation

/// Wire protocol version this build implements. See PROTOCOL.md.
let kProtocolVersion = 1
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
enum TransferState: String, Codable { case active, completed, failed }

/// One in-flight or finished file transfer, shown in the Transfers tab.
struct Transfer: Codable, Identifiable, Equatable {
    var id: String
    var direction: TransferDirection
    var peerName: String
    var fileName: String
    var totalBytes: Int64
    var transferredBytes: Int64
    var state: TransferState
    var startedAt: Date
    var error: String?
    var localPath: String? = nil   // sent file's source path, or received file's saved path

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

struct SendStatusResponse: Codable {
    let transferId: String
    let received: Int64
}
