import Foundation
import SwiftUI
import AppKit

@MainActor
final class AppState: ObservableObject {
    @Published var identity: Identity
    @Published var peers: [PeerDevice]
    @Published var settings: Store.Settings
    @Published var transfers: [Transfer] = []
    /// Smoothed transfer rate (bytes/sec) per active transfer id, for the speed/ETA readout.
    @Published var transferRates: [String: Double] = [:]
    private var rateSamples: [String: (bytes: Int64, time: Date)] = [:]
    @Published var listenerError: String?
    @Published var pendingPairing: PendingPairing?     // incoming request awaiting accept
    @Published var outgoingPairing: OutgoingPairing?   // our initiated pairing, in progress
    @Published var selectedTab = 0   // 0 Devices · 1 Transfers · 2 Settings
    @Published var updateState: UpdateState = .idle

    private let store = Store()
    private let config: SharedConfig
    private let sendClient: SendClient
    private var listener: ListenerService!
    private var pairContinuation: CheckedContinuation<String?, Never>?
    private let updateService = UpdateService()

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
        checkForUpdates(manual: false)   // quiet check once per launch
    }

    // MARK: updates

    var appVersion: String { updateService.currentVersion }

    /// Checks GitHub for a newer release. `manual` surfaces "up to date" / errors in the UI;
    /// an automatic launch check stays quiet unless an update is actually found.
    func checkForUpdates(manual: Bool) {
        switch updateState {
        case .checking, .downloading: return   // already busy
        default: break
        }
        updateState = .checking
        Task {
            do {
                if let info = try await updateService.checkForUpdate() {
                    updateState = .available(info)
                } else {
                    updateState = manual ? .upToDate : .idle
                }
            } catch {
                updateState = manual ? .failed(error.localizedDescription) : .idle
            }
        }
    }

    func downloadUpdate() {
        guard case .available(let info) = updateState else { return }
        guard info.assetUrl != nil else {
            // No platform asset attached — fall back to the release page.
            if let url = URL(string: info.pageUrl) { NSWorkspace.shared.open(url) }
            return
        }
        updateState = .downloading(0)
        Task {
            do {
                let fileURL = try await updateService.download(info) { p in
                    Task { @MainActor in
                        if case .downloading = self.updateState { self.updateState = .downloading(p) }
                    }
                }
                updateState = .downloaded(fileURL)
            } catch {
                updateState = .failed(error.localizedDescription)
            }
        }
    }

    /// Reveals the downloaded file in Finder and opens it (mounts a .dmg / unzips a .zip)
    /// so the user can replace the app — we don't swap it out from under them.
    func revealUpdate() {
        guard case .downloaded(let url) = updateState else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
        NSWorkspace.shared.open(url)
    }

    func dismissUpdate() { updateState = .idle }

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
                                    transferredBytes: 0, state: .queued, startedAt: Date(),
                                    localPath: url.path, peerId: peer.peerId))
            startSend(id: id, url: url, peer: peer)
        }
    }

    /// Number of auto-retry attempts (each picks up from the receiver's last byte) before
    /// a transfer is parked as `.interrupted` for a manual resume.
    private let maxSendRetries = 2

    private func startSend(id: String, url: URL, peer: PeerDevice, resumeFirst: Bool = false) {
        let task = Task { [weak self] in
            guard let self else { return }
            await self.sendLimiter.acquire()
            defer { Task { await self.sendLimiter.release() } }
            if Task.isCancelled { self.markInterrupted(id, error: L(.canceled, .current)); return }
            self.markActive(id)   // queued → active once a slot frees up
            await self.performSend(id: id, url: url, peer: peer, resumeFirst: resumeFirst)
        }
        activeSendTasks[id] = task
    }

    private func performSend(id: String, url: URL, peer: PeerDevice, resumeFirst: Bool) async {
        var attempt = 0
        var tryResume = resumeFirst
        while true {
            do {
                try await sendClient.sendFile(to: peer, fileURL: url, transferId: id, tryResume: tryResume) { sent, _ in
                    Task { @MainActor in self.updateProgress(id: id, bytes: sent) }
                }
                finishTransfer(id: id, state: .completed, error: nil)
                activeSendTasks[id] = nil
                return
            } catch is CancellationError {
                markInterrupted(id, error: L(.canceled, .current)); activeSendTasks[id] = nil; return
            } catch {
                if (error as? URLError)?.code == .cancelled {
                    markInterrupted(id, error: L(.canceled, .current)); activeSendTasks[id] = nil; return
                }
                attempt += 1
                guard attempt <= maxSendRetries else {
                    markInterrupted(id, error: error.localizedDescription); activeSendTasks[id] = nil; return
                }
                // Auto-resume: pause briefly, then continue from the receiver's last byte.
                setRetrying(id)
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if Task.isCancelled {
                    markInterrupted(id, error: L(.canceled, .current)); activeSendTasks[id] = nil; return
                }
                tryResume = true
            }
        }
    }

    private func markInterrupted(_ id: String, error: String) {
        finishTransfer(id: id, state: .interrupted, error: error)
    }

    /// queued → active once a concurrency slot opens.
    private func markActive(_ id: String) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        if transfers[i].state == .queued { transfers[i].state = .active }
    }

    /// Keeps the row active but flags that we're reconnecting between auto-retry attempts.
    private func setRetrying(_ id: String) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[i].error = L(.retrying, .current)
        clearRate(id)   // rate restarts when bytes flow again
    }

    /// Cancels an in-flight outgoing transfer. The partial bytes are kept on the receiver
    /// so it can be resumed later.
    func cancelTransfer(_ transfer: Transfer) {
        activeSendTasks[transfer.id]?.cancel()
    }

    /// Resumes an interrupted/failed outgoing transfer from where the receiver left off
    /// (a fresh receiver with no partial bytes simply re-sends the whole file).
    func resumeTransfer(_ transfer: Transfer) {
        guard transfer.direction == .outgoing, let path = transfer.localPath,
              let peer = peers.first(where: { $0.peerId == transfer.peerId }) else { return }
        if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
            transfers[i].state = .active
            transfers[i].error = nil   // keep transferredBytes: we continue, not restart
        }
        startSend(id: transfer.id, url: URL(fileURLWithPath: path), peer: peer, resumeFirst: true)
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
        // Instantaneous rate, smoothed with an EMA and sampled at most ~3x/sec.
        let now = Date()
        if let prev = rateSamples[id] {
            let dt = now.timeIntervalSince(prev.time)
            if dt >= 0.3 {
                let inst = Double(bytes - prev.bytes) / dt
                let smoothed = (transferRates[id].map { $0 * 0.6 + inst * 0.4 }) ?? inst
                transferRates[id] = max(0, smoothed)
                rateSamples[id] = (bytes, now)
            }
        } else {
            rateSamples[id] = (bytes, now)
        }
    }

    private func clearRate(_ id: String) {
        transferRates[id] = nil
        rateSamples[id] = nil
    }

    private func finishTransfer(id: String, state: TransferState, error: String?, savedPath: String? = nil) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[i].state = state
        transfers[i].error = error
        if let savedPath { transfers[i].localPath = savedPath }
        if state == .completed { transfers[i].transferredBytes = transfers[i].totalBytes }
        clearRate(id)
        persistTransfers()
    }

    func clearFinishedTransfers() {
        // Keep active and interrupted (resumable) rows; clear only completed/failed.
        transfers.removeAll { $0.state == .completed || $0.state == .failed }
        persistTransfers()
    }

    /// Persist finished transfers only; in-flight ones are gone after a restart anyway.
    private func persistTransfers() {
        store.save(transfers: transfers.filter { $0.state != .active && $0.state != .queued })
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
