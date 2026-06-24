import Foundation

/// Simple JSON-file persistence under Application Support.
/// Tokens live here too for the MVP; a later pass can move them to the Keychain.
struct Store {
    private let dir: URL
    private let identityURL: URL
    private let peersURL: URL
    private let settingsURL: URL
    private let transfersURL: URL

    struct Settings: Codable {
        var downloadRoot: String
        var port: UInt16
    }

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("WGFileSender", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        identityURL = dir.appendingPathComponent("identity.json")
        peersURL = dir.appendingPathComponent("peers.json")
        settingsURL = dir.appendingPathComponent("settings.json")
        transfersURL = dir.appendingPathComponent("transfers.json")
    }

    // MARK: Identity

    func loadIdentity() -> Identity {
        if let data = try? Data(contentsOf: identityURL),
           let id = try? JSONDecoder().decode(Identity.self, from: data) {
            return id
        }
        let host = Host.current().localizedName ?? "Mac"
        let id = Identity.fresh(name: host)
        save(identity: id)
        return id
    }

    func save(identity: Identity) { write(identity, to: identityURL) }

    // MARK: Peers

    func loadPeers() -> [PeerDevice] {
        guard let data = try? Data(contentsOf: peersURL),
              let peers = try? JSONDecoder().decode([PeerDevice].self, from: data)
        else { return [] }
        return peers
    }

    func save(peers: [PeerDevice]) { write(peers, to: peersURL) }

    // MARK: Settings

    func loadSettings() -> Settings {
        if let data = try? Data(contentsOf: settingsURL),
           let s = try? JSONDecoder().decode(Settings.self, from: data) {
            return s
        }
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WGFileSender", isDirectory: true)
        let s = Settings(downloadRoot: downloads.path, port: kDefaultPort)
        save(settings: s)
        return s
    }

    func save(settings: Settings) { write(settings, to: settingsURL) }

    // MARK: Transfers (finished history only)

    func loadTransfers() -> [Transfer] {
        guard let data = try? Data(contentsOf: transfersURL),
              let items = try? JSONDecoder().decode([Transfer].self, from: data)
        else { return [] }
        return items
    }

    func save(transfers: [Transfer]) { write(transfers, to: transfersURL) }

    // MARK: helper

    private func write<T: Encodable>(_ value: T, to url: URL) {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? enc.encode(value) {
            try? data.write(to: url, options: .atomic)
        }
    }
}
