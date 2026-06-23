import Foundation

/// Thread-safe snapshot of config the networking layer reads from background queues.
/// AppState (main actor) writes; the listener reads. Kept separate so the network
/// path never has to hop to the main actor just to look up a peer or the download root.
final class SharedConfig: @unchecked Sendable {
    private let lock = NSLock()
    private var _identity: Identity
    private var _peers: [PeerDevice]
    private var _settings: Store.Settings

    init(identity: Identity, peers: [PeerDevice], settings: Store.Settings) {
        _identity = identity
        _peers = peers
        _settings = settings
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    var identity: Identity { sync { _identity } }
    func setIdentity(_ value: Identity) { sync { _identity = value } }

    var peers: [PeerDevice] { sync { _peers } }
    func setPeers(_ value: [PeerDevice]) { sync { _peers = value } }

    var settings: Store.Settings { sync { _settings } }
    func setSettings(_ value: Store.Settings) { sync { _settings = value } }

    /// Find a peer by the token it presents (matches the token THIS device issued).
    func peer(forToken token: String) -> PeerDevice? {
        sync { _peers.first { $0.tokenIn == token } }
    }

    func peer(forId id: String) -> PeerDevice? {
        sync { _peers.first { $0.peerId == id } }
    }
}
