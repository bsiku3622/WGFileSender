import Foundation
import SwiftUI
import AppKit
import CryptoKit

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
        restoreTransfers()   // re-arm outgoing sources + resume incoming pulls
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

    /// Installs the downloaded update: unzips it, swaps the new build over the running app
    /// via a detached helper, and relaunches. No manual drag-to-Applications.
    func installUpdate() {
        guard case .downloaded(let fileURL) = updateState else { return }
        guard fileURL.pathExtension.lowercased() == "zip" else {
            // Non-zip asset (e.g. a .dmg) — fall back to opening it for the user.
            NSWorkspace.shared.activateFileViewerSelecting([fileURL])
            NSWorkspace.shared.open(fileURL)
            return
        }
        do {
            let fm = FileManager.default
            let work = fm.temporaryDirectory.appendingPathComponent("wgfs-update-\(UUID().uuidString)")
            try fm.createDirectory(at: work, withIntermediateDirectories: true)
            try runProcess("/usr/bin/ditto", ["-x", "-k", fileURL.path, work.path])
            guard let newApp = (try? fm.contentsOfDirectory(at: work, includingPropertiesForKeys: nil))?
                .first(where: { $0.pathExtension == "app" }) else {
                updateState = .failed("No app found in the downloaded archive"); return
            }
            // Helper waits for us to quit, replaces the bundle in place, then relaunches it.
            let currentApp = Bundle.main.bundleURL
            let pid = ProcessInfo.processInfo.processIdentifier
            let script = """
            #!/bin/bash
            while /bin/kill -0 \(pid) 2>/dev/null; do sleep 0.2; done
            /bin/rm -rf "\(currentApp.path)"
            /bin/cp -R "\(newApp.path)" "\(currentApp.path)"
            /usr/bin/xattr -dr com.apple.quarantine "\(currentApp.path)" 2>/dev/null
            /usr/bin/open "\(currentApp.path)"
            """
            let scriptURL = work.appendingPathComponent("swap.sh")
            try script.write(to: scriptURL, atomically: true, encoding: .utf8)
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/bin/bash")
            proc.arguments = [scriptURL.path]
            try proc.run()
            NSApp.terminate(nil)   // quit so the helper can replace us
        } catch {
            updateState = .failed(error.localizedDescription)
        }
    }

    private func runProcess(_ path: String, _ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw WGFSError.badResponse }
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
            onOffer: { [weak self] offer, peer, senderName in
                Task { @MainActor in self?.handleOffer(offer, sender: peer, senderName: senderName) }
            },
            onPullProgress: { [weak self] fileId, sent, total in
                Task { @MainActor in self?.updateOutgoingProgress(fileId, sent: sent, total: total) }
            }
        )
        listener = ListenerService(config: config, events: events)
        do { try listener.start(); listenerError = nil }
        catch { listenerError = String(format: L(.cantBindPort, .current), Int(settings.port), error.localizedDescription) }
    }

    // MARK: incoming pairing

    private func awaitIncomingPair(body: PairRequestBody, address: String) async -> String? {
        // One prompt at a time: reject overlaps instead of clobbering the pending
        // continuation (which would leave the earlier request hung forever).
        if pendingPairing != nil { return nil }
        let sessionId = body.sessionId
        return await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            pairContinuation = cont
            pendingPairing = PendingPairing(body: body, address: address)
            // PROTOCOL.md: decline/timeout after 60s.
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard let self, self.pendingPairing?.body.sessionId == sessionId,
                      let c = self.pairContinuation else { return }
                self.pendingPairing = nil
                self.pairContinuation = nil
                c.resume(returning: nil)
            }
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
                // Confirm first; only persist the peer once the symmetric exchange succeeds,
                // so a confirm failure doesn't leave a "failed" prompt over a working peer.
                try await sendClient.confirmPair(address: address, tokenOut: resp.token,
                                                 ourTokenForPeer: ourToken)
                let peer = PeerDevice(peerId: resp.deviceId, peerName: resp.deviceName, localName: nil,
                                      peerAddress: address, tokenOut: resp.token, tokenIn: ourToken,
                                      lastSeen: Date())
                upsertPeer(peer)
                outgoingPairing = nil
            } catch {
                outgoingPairing?.status = String(format: L(.pairingFailed, .current), error.localizedDescription)
                outgoingPairing?.failed = true
            }
        }
    }

    func dismissOutgoingPair() { outgoingPairing = nil }

    // MARK: sending (announce a manifest, then serve /pull)

    func sendFiles(_ urls: [URL], to peer: PeerDevice) {
        guard !urls.isEmpty else { return }
        selectedTab = 1
        let batchId = UUID().uuidString
        var entries: [(String, URL)] = []
        for url in urls {
            let fileId = UUID().uuidString
            upsertTransfer(Transfer(id: fileId, batchId: batchId, direction: .outgoing,
                peerName: peer.displayName, peerId: peer.peerId, peerAddress: peer.peerAddress,
                fileName: url.lastPathComponent, totalBytes: fileSize(url), transferredBytes: 0,
                sha256: "", state: .pending, startedAt: Date(), localPath: url.path))
            entries.append((fileId, url))
        }
        persistTransfers()
        Task { await prepareAndOffer(batchId: batchId, entries: entries, peer: peer) }
    }

    /// Hashes each file off the main actor, registers it as pullable, then offers the manifest.
    private func prepareAndOffer(batchId: String, entries: [(String, URL)], peer: PeerDevice) async {
        var offerFiles: [OfferFile] = []
        for (fileId, url) in entries {
            let size = fileSize(url)
            let hash = await Task.detached(priority: .utility) { computeSHA256(url) ?? "" }.value
            config.addOutgoing(fileId, OutgoingSource(path: url.path, size: size, sha256: hash))
            if let i = transfers.firstIndex(where: { $0.id == fileId }) { transfers[i].sha256 = hash }
            offerFiles.append(OfferFile(fileId: fileId, name: url.lastPathComponent, size: size, sha256: hash))
        }
        persistTransfers()
        do {
            try await sendClient.offer(to: peer, batchId: batchId, files: offerFiles)
            // Now waiting for the receiver to pull; rows stay pending until bytes flow.
        } catch {
            for (fileId, _) in entries {
                finishTransfer(id: fileId, state: .interrupted, error: error.localizedDescription)
            }
        }
    }

    /// Sender-side progress, driven by the peer pulling bytes via /pull.
    private func updateOutgoingProgress(_ fileId: String, sent: Int64, total: Int64) {
        guard let i = transfers.firstIndex(where: { $0.id == fileId }) else { return }
        if transfers[i].state == .pending || transfers[i].state == .interrupted {
            transfers[i].state = .active; transfers[i].error = nil
        }
        updateProgress(id: fileId, bytes: sent)
        if sent >= total {
            finishTransfer(id: fileId, state: .completed, error: nil)
            config.removeOutgoing(fileId)
        }
    }

    // MARK: receiving (pull worker)

    private let smallPull = ConcurrencyLimiter(4)
    private let largePull = ConcurrencyLimiter(1)    // large files pulled one-at-a-time
    private let largeThreshold: Int64 = 64 * 1024 * 1024
    private var pullTasks: [String: Task<Void, Never>] = [:]

    /// Receiver: persist the offered manifest and kick the pull worker.
    private func handleOffer(_ offer: OfferBody, sender peer: PeerDevice, senderName: String) {
        for f in offer.files where !transfers.contains(where: { $0.id == f.fileId }) {
            upsertTransfer(Transfer(id: f.fileId, batchId: offer.batchId, direction: .incoming,
                peerName: peer.displayName, peerId: peer.peerId, peerAddress: peer.peerAddress,
                fileName: f.name, totalBytes: f.size, transferredBytes: 0, sha256: f.sha256,
                state: .pending, startedAt: Date()))
        }
        selectedTab = 1
        persistTransfers()
        schedulePulls()
    }

    /// Starts a pull task for every incomplete incoming file not already running.
    func schedulePulls() {
        for t in transfers where t.direction == .incoming && Self.incomplete(t.state) && pullTasks[t.id] == nil {
            let id = t.id
            if let i = transfers.firstIndex(where: { $0.id == id }), transfers[i].state != .active {
                transfers[i].state = .queued
            }
            pullTasks[id] = Task { [weak self] in await self?.pullWithRetry(id) }
        }
    }

    private static func incomplete(_ s: TransferState) -> Bool {
        s == .pending || s == .queued || s == .active || s == .interrupted || s == .failed
    }

    /// Pulls one file, retrying indefinitely with backoff (resuming from the .part) until it
    /// completes or is cancelled — the receiver owns "done", so this never gives up silently.
    private func pullWithRetry(_ id: String) async {
        guard let t0 = transfers.first(where: { $0.id == id }) else { pullTasks[id] = nil; return }
        let limiter = t0.totalBytes >= largeThreshold ? largePull : smallPull
        await limiter.acquire()
        defer { Task { await limiter.release() }; pullTasks[id] = nil }

        var backoff: UInt64 = 1_000_000_000
        while !Task.isCancelled {
            guard let t = transfers.first(where: { $0.id == id }) else { return }
            if t.state == .completed { return }
            guard let peer = peers.first(where: { $0.peerId == t.peerId }) else {
                markInterrupted(id, error: L(.connectionLost, .current)); return
            }
            markActive(id)
            do {
                let folderName = peer.localName?.isEmpty == false ? peer.localName! : t.peerName
                let path = try await sendClient.pullFile(
                    from: peer, batchId: t.batchId, fileId: id, fileName: t.fileName,
                    expectedSize: t.totalBytes, expectedHash: t.sha256, senderFolder: folderName,
                    downloadRoot: settings.downloadRoot,
                    progress: { sent, _ in Task { @MainActor in self.updateProgress(id: id, bytes: sent) } })
                finishTransfer(id: id, state: .completed, error: nil, savedPath: path)
                return
            } catch is CancellationError {
                markInterrupted(id, error: L(.canceled, .current)); return
            } catch {
                setRetrying(id)
                try? await Task.sleep(nanoseconds: backoff)
                if Task.isCancelled { markInterrupted(id, error: L(.canceled, .current)); return }
                backoff = min(backoff * 2, 30_000_000_000)   // cap 30s
            }
        }
        markInterrupted(id, error: L(.canceled, .current))
    }

    private func markInterrupted(_ id: String, error: String) {
        finishTransfer(id: id, state: .interrupted, error: error)
    }

    private func markActive(_ id: String) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        if transfers[i].state != .active && transfers[i].state != .completed {
            transfers[i].state = .active; transfers[i].error = nil
        }
    }

    private func setRetrying(_ id: String) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[i].error = L(.retrying, .current)
        clearRate(id)
    }

    /// Re-registers outgoing sources and resumes incoming pulls after a restart.
    private func restoreTransfers() {
        for t in transfers where t.direction == .outgoing && t.state != .completed && t.state != .failed {
            if let p = t.localPath, !t.sha256.isEmpty {
                config.addOutgoing(t.id, OutgoingSource(path: p, size: t.totalBytes, sha256: t.sha256))
            }
        }
        schedulePulls()
    }

    /// Cancels a transfer. Incoming: stops the pull (resumable). Outgoing: stops serving it.
    func cancelTransfer(_ transfer: Transfer) {
        if transfer.direction == .incoming {
            pullTasks[transfer.id]?.cancel()
        } else {
            config.removeOutgoing(transfer.id)
            markInterrupted(transfer.id, error: L(.canceled, .current))
        }
    }

    /// Resumes a transfer. Incoming: re-queue the pull. Outgoing: re-arm serving.
    func resumeTransfer(_ transfer: Transfer) {
        if transfer.direction == .incoming {
            if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
                transfers[i].state = .pending; transfers[i].error = nil
            }
            schedulePulls()
        } else if let path = transfer.localPath, !transfer.sha256.isEmpty {
            config.addOutgoing(transfer.id, OutgoingSource(path: path, size: transfer.totalBytes, sha256: transfer.sha256))
            if let i = transfers.firstIndex(where: { $0.id == transfer.id }) {
                transfers[i].state = .pending; transfers[i].error = nil
            }
        }
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

    /// Persist the whole list — incoming pulls and outgoing offers must survive a restart so
    /// the worker can resume them (the receiver owns durable transfer state).
    private func persistTransfers() {
        store.save(transfers: transfers)
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

    /// Removes a row from the list without touching any file (used for sent transfers,
    /// whose "file" is the untouched local source).
    func removeTransfer(_ transfer: Transfer) {
        transfers.removeAll { $0.id == transfer.id }
        persistTransfers()
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

/// SHA-256 hex of a file, safe to call off the main actor (used while hashing a batch).
func computeSHA256(_ url: URL) -> String? {
    guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
    defer { try? handle.close() }
    var hasher = SHA256()
    while case let chunk = handle.readData(ofLength: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
}
