import Foundation
import CryptoKit

/// Callbacks the listener fires up to the app/UI layer. Implementations hop to the
/// main actor as needed; the listener itself stays off the main actor.
struct ListenerEvents {
    /// Show the accept prompt and await the user. Return the token THIS device issues
    /// for the peer (stored as the peer's tokenIn), or nil if declined / timed out.
    /// `address` is the resolved host:port to reach the initiator back.
    var onPairRequest: (PairRequestBody, String) async -> String?
    /// Peer pushed the token we should use when sending to it (our tokenOut).
    var onPairConfirm: (PairConfirmBody) -> Void
    var onTransferStart: (Transfer) -> Void
    var onTransferProgress: (String, Int64) -> Void
    var onTransferFinish: (String, TransferState, String?, String?) -> Void   // id, state, error, savedPath
}

/// Owns the HTTP listener and routes requests per PROTOCOL.md.
final class ListenerService {
    private let config: SharedConfig
    private let events: ListenerEvents
    private var server: HTTPServer?

    init(config: SharedConfig, events: ListenerEvents) {
        self.config = config
        self.events = events
    }

    func start() throws {
        let server = HTTPServer { [weak self] req, body, remoteHost in
            guard let self else { return .status(404) }
            return await self.route(req, body, remoteHost)
        }
        try server.start(port: config.settings.port)
        self.server = server
    }

    func stop() { server?.stop(); server = nil }

    func restart() throws { stop(); try start() }

    // MARK: routing

    private func route(_ req: HTTPRequest, _ body: BodyStream, _ remoteHost: String) async -> HTTPResponse {
        switch (req.method, req.path) {
        case ("GET", "/ping"):
            let id = config.identity
            return .json(PingResponse(deviceId: id.deviceId, deviceName: id.deviceName,
                                      platform: id.platform, protocol: kProtocolVersion))

        case ("POST", "/pair/request"):
            return await handlePairRequest(body, remoteHost)

        case ("POST", "/pair/confirm"):
            return await handlePairConfirm(req, body)

        case ("POST", "/send"):
            return await handleSend(req, body)

        case ("GET", "/send/status"):
            return handleSendStatus(req)

        default:
            return .status(404)
        }
    }

    private func handlePairRequest(_ body: BodyStream, _ remoteHost: String) async -> HTTPResponse {
        let data = await body.readAll()
        guard let payload = try? JSONDecoder().decode(PairRequestBody.self, from: data) else {
            return .status(400)
        }
        let address = "\(remoteHost):\(payload.port)"
        guard let issuedToken = await events.onPairRequest(payload, address) else {
            return .status(403)   // declined or timed out
        }
        let id = config.identity
        return .json(PairAcceptResponse(deviceId: id.deviceId, deviceName: id.deviceName,
                                        platform: id.platform, token: issuedToken))
    }

    private func handlePairConfirm(_ req: HTTPRequest, _ body: BodyStream) async -> HTTPResponse {
        guard let token = req.bearerToken, config.peer(forToken: token) != nil else {
            return .status(401)
        }
        let data = await body.readAll()
        guard let payload = try? JSONDecoder().decode(PairConfirmBody.self, from: data) else {
            return .status(400)
        }
        events.onPairConfirm(payload)
        return HTTPResponse(status: 204)
    }

    private func handleSendStatus(_ req: HTTPRequest) -> HTTPResponse {
        guard let token = req.bearerToken, config.peer(forToken: token) != nil,
              let transferId = req.query["transferId"] else {
            return .status(401)
        }
        let size = partFileSize(transferId: transferId)
        return .json(SendStatusResponse(transferId: transferId, received: size))
    }

    private func handleSend(_ req: HTTPRequest, _ body: BodyStream) async -> HTTPResponse {
        guard let token = req.bearerToken, let peer = config.peer(forToken: token) else {
            return .status(401)
        }
        guard let rawName = req.header("x-wgfs-file-name"),
              let fileName = rawName.removingPercentEncoding,
              let transferId = req.header("x-wgfs-transfer-id"),
              let expectedHash = req.header("x-wgfs-sha256") else {
            return .status(400)
        }
        let totalBytes = Int64(req.header("x-wgfs-file-size") ?? "") ?? 0
        let senderName = req.header("x-wgfs-device-name") ?? peer.peerName
        let folderName = peer.localName?.isEmpty == false ? peer.localName! : senderName

        let fm = FileManager.default
        let folder = URL(fileURLWithPath: config.settings.downloadRoot)
            .appendingPathComponent(safeComponent(folderName), isDirectory: true)
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)

        let partURL = partURL(transferId: transferId)
        try? fm.createDirectory(at: partURL.deletingLastPathComponent(),
                                withIntermediateDirectories: true)
        fm.createFile(atPath: partURL.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: partURL) else { return .status(500) }

        events.onTransferStart(Transfer(
            id: transferId, direction: .incoming, peerName: peer.displayName,
            fileName: fileName, totalBytes: totalBytes, transferredBytes: 0,
            state: .active, startedAt: Date()))

        var hasher = SHA256()
        var received: Int64 = 0
        while let chunk = await body.read() {
            try? handle.write(contentsOf: chunk)
            hasher.update(data: chunk)
            received += Int64(chunk.count)
            events.onTransferProgress(transferId, received)
        }
        try? handle.close()

        let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard digest == expectedHash.lowercased() else {
            try? fm.removeItem(at: partURL)
            events.onTransferFinish(transferId, .failed, L(.checksumMismatch, .current), nil)
            return .status(409)
        }

        let finalURL = uniqueURL(in: folder, fileName: fileName)
        try? fm.moveItem(at: partURL, to: finalURL)
        events.onTransferFinish(transferId, .completed, nil, finalURL.path)
        return .status(200)
    }

    // MARK: file helpers

    private func partURL(transferId: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WGFileSender/parts", isDirectory: true)
        return base.appendingPathComponent("\(transferId).part")
    }

    private func partFileSize(transferId: String) -> Int64 {
        let url = partURL(transferId: transferId)
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    private func safeComponent(_ name: String) -> String {
        let cleaned = name.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "..", with: "_")
        return cleaned.isEmpty ? "Unknown" : cleaned
    }

    private func uniqueURL(in folder: URL, fileName: String) -> URL {
        let fm = FileManager.default
        let safe = safeComponent(fileName)
        var candidate = folder.appendingPathComponent(safe)
        guard fm.fileExists(atPath: candidate.path) else { return candidate }
        let ext = (safe as NSString).pathExtension
        let stem = (safe as NSString).deletingPathExtension
        var n = 1
        repeat {
            let name = ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)"
            candidate = folder.appendingPathComponent(name)
            n += 1
        } while fm.fileExists(atPath: candidate.path)
        return candidate
    }
}
