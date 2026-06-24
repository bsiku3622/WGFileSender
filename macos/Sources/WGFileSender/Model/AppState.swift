import Foundation
import SwiftUI
import AppKit

@MainActor
final class AppState: ObservableObject {
    @Published var identity: Identity
    @Published var peers: [PeerDevice]
    @Published var settings: Store.Settings
    @Published var transfers: [Transfer] = []
    @Published var listenerError: String?
    @Published var pendingPairing: PendingPairing?     // incoming request awaiting accept
    @Published var outgoingPairing: OutgoingPairing?   // our initiated pairing, in progress
    @Published var selectedTab = 0   // 0 Devices · 1 Transfers · 2 Settings

    private let store = Store()
    private let config: SharedConfig
    private let sendClient: SendClient
    private var listener: ListenerService!
    private var pairContinuation: CheckedContinuation<String?, Never>?

    /// Shared instance so the AppKit status-item delegate and SwiftUI scenes
    /// observe the same state.
    static let shared = AppState()

    struct PendingPairing: Identifiable {
        let id = UUID()
        let body: PairRequestBody
        let address: String
    }
    struct OutgoingPairing: Identifiable {
        let id = UUID()
        var address: String
        var pin: String
        var status: String
        var failed: Bool = false
    }

    private init() {
        let id = store.loadIdentity()
        let loadedPeers = store.loadPeers()
        let loadedSettings = store.loadSettings()
        identity = id
        peers = loadedPeers
        settings = loadedSettings
        transfers = store.loadTransfers()
        config = SharedConfig(identity: id, peers: loadedPeers, settings: loadedSettings)
        sendClient = SendClient(config: config)
        try? FileManager.default.createDirectory(atPath: loadedSettings.downloadRoot,
                                                 withIntermediateDirectories: true)
        startListener()
    }

    // MARK: listener

    private func startListener() {
        let events = ListenerEvents(
            onPairRequest: { [weak self] body, address in
                await self?.awaitIncomingPair(body: body, address: address) ?? nil
            },
            onPairConfirm: { [weak self] payload in
                Task { @MainActor in self?.applyPairConfirm(payload) }
            },
            onTransferStart: { [weak self] t in
                Task { @MainActor in self?.upsertTransfer(t) }
            },
            onTransferProgress: { [weak self] id, bytes in
                Task { @MainActor in self?.updateProgress(id: id, bytes: bytes) }
            },
            onTransferFinish: { [weak self] id, state, err, path in
                Task { @MainActor in self?.finishTransfer(id: id, state: state, error: err, savedPath: path) }
            }
        )
        listener = ListenerService(config: config, events: events)
        do { try listener.start(); listenerError = nil }
        catch { listenerError = String(format: L(.cantBindPort, .current), Int(settings.port), error.localizedDescription) }
    }

    // MARK: incoming pairing

    private func awaitIncomingPair(body: PairRequestBody, address: String) async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            pairContinuation = cont
            pendingPairing = PendingPairing(body: body, address: address)
        }
    }

    func acceptIncomingPair() {
        guard let pending = pendingPairing else { return }
        let token = Self.randomToken()   // tokenIn we issue; peer sends it back to us
        let peer = PeerDevice(peerId: pending.body.deviceId, peerName: pending.body.deviceName,
                              localName: nil, peerAddress: pending.address,
                              tokenOut: "", tokenIn: token, lastSeen: Date())
        upsertPeer(peer)
        pendingPairing = nil
        pairContinuation?.resume(returning: token)
        pairContinuation = nil
    }

    func declineIncomingPair() {
        pendingPairing = nil
        pairContinuation?.resume(returning: nil)
        pairContinuation = nil
    }

    private func applyPairConfirm(_ payload: PairConfirmBody) {
        guard var peer = peers.first(where: { $0.peerId == payload.deviceId }) else { return }
        peer.tokenOut = payload.token
        upsertPeer(peer)
    }

    // MARK: outgoing pairing

    func startOutgoingPair(address: String) {
        let pin = Self.randomPIN()
        let sessionId = UUID().uuidString
        outgoingPairing = OutgoingPairing(
            address: address, pin: pin,
            status: String(format: L(.confirmPinOnOther, .current), Self.pretty(pin)))
        Task {
            do {
                let resp = try await sendClient.requestPair(address: address, sessionId: sessionId, pin: pin)
                let ourToken = Self.randomToken()   // tokenIn we issue for the peer
                let peer = PeerDevice(peerId: resp.deviceId, peerName: resp.deviceName, localName: nil,
                                      peerAddress: address, tokenOut: resp.token, tokenIn: ourToken,
                                      lastSeen: Date())
                upsertPeer(peer)
                try await sendClient.confirmPair(address: address, tokenOut: resp.token,
                                                 ourTokenForPeer: ourToken)
                outgoingPairing = nil
            } catch {
                outgoingPairing?.status = String(format: L(.pairingFailed, .current), error.localizedDescription)
                outgoingPairing?.failed = true
            }
        }
    }

    func dismissOutgoingPair() { outgoingPairing = nil }

    // MARK: sending

    /// Caps concurrent uploads (URLSession per-host limit / receiver load).
    private let sendLimiter = ConcurrencyLimiter(4)
    private var activeSendTasks: [String: Task<Void, Never>] = [:]

    func sendFiles(_ urls: [URL], to peer: PeerDevice) {
        guard !urls.isEmpty else { return }
        selectedTab = 1   // jump to Transfers
        for url in urls {
            let id = UUID().uuidString
            upsertTransfer(Transfer(id: id, direction: .outgoing, peerName: peer.displayName,
                                    fileName: url.lastPathComponent, totalBytes: fileSize(url),
                                    transferredBytes: 0, state: .active, startedAt: Date(),
                                    localPath: url.path, peerId: peer.peerId))
            startSend(id: id, url: url, peer: peer)
        }
    }

    private func startSend(id: String, url: URL, peer: PeerDevice) {
        let task = Task { [weak self] in
            guard let self else { return }
            await self.sendLimiter.acquire()
            defer { Task { await self.sendLimiter.release() } }
            if Task.isCancelled { await self.markCanceled(id); return }
            await self.performSend(id: id, url: url, peer: peer)
        }
        activeSendTasks[id] = task
    }

    private func performSend(id: String, url: URL, peer: PeerDevice) async {
        do {
            try await sendClient.sendFile(to: peer, fileURL: url, transferId: id) { sent, _ in
                Task { @MainActor in self.updateProgress(id: id, bytes: sent) }
            }
            finishTransfer(id: id, state: .completed, error: nil)
        } catch is CancellationError {
            markCanceled(id)
        } catch {
            if (error as? URLError)?.code == .cancelled { markCanceled(id) }
            else { finishTransfer(id: id, state: .failed, error: error.localizedDescription) }
        }
        activeSendTasks[id] = nil
    }

    private func markCanceled(_ id: String) {
        finishTransfer(id: id, state: .failed, error: L(.canceled, .current))
    }

    /// Cancels an in-flight outgoing transfer.
    func cancelTransfer(_ transfer: Transfer) {
        activeSendTasks[transfer.id]?.cancel()
    }

    /// Re-sends a failed/canceled outgoing transfer using its stored source path.
    func resendTransfer(_ transfer: Transfer) {
        guard transfer.direction == .outgoing, let path = transfer.localPath,
              let peer = peers.first(where: { $0.peerId == transfer.peerId }) else { return }
        if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
            transfers[i].state = .active
            transfers[i].error = nil
            transfers[i].transferredBytes = 0
        }
        startSend(id: transfer.id, url: URL(fileURLWithPath: path), peer: peer)
    }

    // MARK: peers

    func renamePeer(_ peer: PeerDevice, to name: String) {
        var p = peer
        p.localName = name.trimmingCharacters(in: .whitespaces)
        upsertPeer(p)
    }

    func removePeer(_ peer: PeerDevice) {
        peers.removeAll { $0.peerId == peer.peerId }
        persistPeers()
    }

    private func upsertPeer(_ peer: PeerDevice) {
        if let i = peers.firstIndex(where: { $0.peerId == peer.peerId }) { peers[i] = peer }
        else { peers.append(peer) }
        persistPeers()
    }

    private func persistPeers() {
        store.save(peers: peers)
        config.setPeers(peers)
    }

    // MARK: settings

    func updateDownloadRoot(_ path: String) {
        settings.downloadRoot = path
        persistSettings()
        try? FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: true)
    }

    func updateDeviceName(_ name: String) {
        identity.deviceName = name.trimmingCharacters(in: .whitespaces)
        store.save(identity: identity)
        config.setIdentity(identity)
    }

    func updatePort(_ port: UInt16) {
        settings.port = port
        persistSettings()
        do { try listener.restart(); listenerError = nil }
        catch { listenerError = String(format: L(.cantBindPort, .current), Int(port), error.localizedDescription) }
    }

    private func persistSettings() {
        store.save(settings: settings)
        config.setSettings(settings)
    }

    // MARK: transfers

    private func upsertTransfer(_ t: Transfer) {
        if let i = transfers.firstIndex(where: { $0.id == t.id }) { transfers[i] = t }
        else { transfers.insert(t, at: 0) }
    }

    private func updateProgress(id: String, bytes: Int64) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[i].transferredBytes = bytes
    }

    private func finishTransfer(id: String, state: TransferState, error: String?, savedPath: String? = nil) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[i].state = state
        transfers[i].error = error
        if let savedPath { transfers[i].localPath = savedPath }
        if state == .completed { transfers[i].transferredBytes = transfers[i].totalBytes }
        persistTransfers()
    }

    func clearFinishedTransfers() {
        transfers.removeAll { $0.state != .active }
        persistTransfers()
    }

    /// Persist finished transfers only; in-flight ones are gone after a restart anyway.
    private func persistTransfers() {
        store.save(transfers: transfers.filter { $0.state != .active })
    }

    func openDownloadFolder() {
        let url = URL(fileURLWithPath: settings.downloadRoot)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        NSWorkspace.shared.open(url)
    }

    // MARK: transfer file actions

    func transferFileExists(_ transfer: Transfer) -> Bool {
        guard let p = transfer.localPath else { return false }
        return FileManager.default.fileExists(atPath: p)
    }

    func openTransferFile(_ transfer: Transfer) {
        guard transferFileExists(transfer), let p = transfer.localPath else { return }
        NSWorkspace.shared.open(URL(fileURLWithPath: p))
    }

    func revealTransferFile(_ transfer: Transfer) {
        guard transferFileExists(transfer), let p = transfer.localPath else { return }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)])
    }

    func renameTransferFile(_ transfer: Transfer, to newName: String) {
        guard let p = transfer.localPath else { return }
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let oldURL = URL(fileURLWithPath: p)
        let newURL = oldURL.deletingLastPathComponent().appendingPathComponent(trimmed)
        do {
            try FileManager.default.moveItem(at: oldURL, to: newURL)
            if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
                transfers[i].fileName = trimmed
                transfers[i].localPath = newURL.path
                persistTransfers()
            }
        } catch { /* keep old entry on failure */ }
    }

    /// Moves the received file to the Trash and removes the entry.
    func deleteTransferFile(_ transfer: Transfer) {
        if let p = transfer.localPath {
            try? FileManager.default.trashItem(at: URL(fileURLWithPath: p), resultingItemURL: nil)
        }
        transfers.removeAll { $0.id == transfer.id }
        persistTransfers()
    }

    // MARK: helpers

    var activeTransferCount: Int { transfers.filter { $0.state == .active }.count }

    private func fileSize(_ url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    static func randomPIN() -> String { String(format: "%06d", Int.random(in: 0..<1_000_000)) }

    static func pretty(_ pin: String) -> String {
        guard pin.count == 6 else { return pin }
        let i = pin.index(pin.startIndex, offsetBy: 3)
        return "\(pin[pin.startIndex..<i]) \(pin[i...])"
    }

    static func randomToken() -> String {
        let bytes = (0..<24).map { _ in UInt8.random(in: 0...255) }
        return Data(bytes).base64EncodedString()
    }
}
