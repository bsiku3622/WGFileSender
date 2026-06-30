import Foundation

/// Callbacks the listener fires up to the app/UI layer. Implementations hop to the main
/// actor as needed; the listener itself stays off the main actor. All `@Sendable` because
/// the streaming `/pull` handler runs on a background queue.
struct ListenerEvents {
    /// Show the accept prompt and await the user. Return the token THIS device issues for
    /// the peer (stored as the peer's tokenIn), or nil if declined / timed out.
    var onPairRequest: @Sendable (PairRequestBody, String) async -> String?
    /// Peer pushed the token we should use when sending to it (our tokenOut).
    var onPairConfirm: @Sendable (PairConfirmBody) -> Void
    /// Peer offered a batch manifest; we (the receiver) persist it and start pulling.
    var onOffer: @Sendable (OfferBody, PeerDevice, String) -> Void   // manifest, sender peer, senderName
    /// Bytes served so far for an outgoing file via /pull (sender-side progress).
    var onPullProgress: @Sendable (String, Int64, Int64) -> Void     // fileId, sent, total
}

/// Owns the HTTP listener and routes requests per PROTOCOL.md (pull model).
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
        case ("POST", "/offer"):
            return await handleOffer(req, body)
        case ("GET", "/pull"):
            return handlePull(req)
        default:
            return .status(404)
        }
    }

    // MARK: pairing

    private func handlePairRequest(_ body: BodyStream, _ remoteHost: String) async -> HTTPResponse {
        let data = await body.readAll()
        guard let payload = try? JSONDecoder().decode(PairRequestBody.self, from: data) else {
            return .status(400)
        }
        let address = "\(remoteHost):\(payload.port)"
        guard let issuedToken = await events.onPairRequest(payload, address) else {
            return .status(403)
        }
        let id = config.identity
        return .json(PairAcceptResponse(deviceId: id.deviceId, deviceName: id.deviceName,
                                        platform: id.platform, token: issuedToken))
    }

    private func handlePairConfirm(_ req: HTTPRequest, _ body: BodyStream) async -> HTTPResponse {
        guard let token = req.bearerToken, let peer = config.peer(forToken: token) else {
            return .status(401)
        }
        let data = await body.readAll()
        guard let payload = try? JSONDecoder().decode(PairConfirmBody.self, from: data) else {
            return .status(400)
        }
        guard peer.peerId == payload.deviceId else { return .status(403) }
        events.onPairConfirm(payload)
        return HTTPResponse(status: 204)
    }

    // MARK: transfers (pull model)

    /// Receiver side: accept a batch manifest and let the app start pulling.
    private func handleOffer(_ req: HTTPRequest, _ body: BodyStream) async -> HTTPResponse {
        guard let token = req.bearerToken, let peer = config.peer(forToken: token) else {
            return .status(401)
        }
        let data = await body.readAll()
        guard let offer = try? JSONDecoder().decode(OfferBody.self, from: data),
              offer.files.count <= 10_000, !offer.batchId.isEmpty,
              offer.files.allSatisfy({ Self.isSafeId($0.fileId) }) else {
            return .status(400)
        }
        let senderName = req.header("x-wgfs-device-name") ?? peer.peerName
        events.onOffer(offer, peer, senderName)
        return .status(200)
    }

    /// Sender side: stream a file's bytes (resumable via Range) for the peer to pull.
    private func handlePull(_ req: HTTPRequest) -> HTTPResponse {
        guard let token = req.bearerToken, config.peer(forToken: token) != nil else {
            return .status(401)
        }
        guard let fileId = req.query["file"], Self.isSafeId(fileId),
              let src = config.outgoingSource(fileId) else {
            return .status(404)
        }
        guard FileManager.default.fileExists(atPath: src.path) else { return .status(410) }
        let size = src.size
        let start: Int64 = req.header("range").flatMap { Self.rangeBytesStart($0) } ?? 0
        guard start >= 0, start <= size else { return .status(416) }

        let len = size - start
        var headers = ["Content-Length": "\(len)", "Content-Type": "application/octet-stream"]
        let status: Int
        if start > 0 {
            status = 206
            headers["Content-Range"] = "bytes \(start)-\(size - 1)/\(size)"
        } else {
            status = 200
        }
        let path = src.path
        let onProgress = events.onPullProgress
        return HTTPResponse(status: status, headers: headers, stream: { sink in
            guard let handle = try? FileHandle(forReadingFrom: URL(fileURLWithPath: path)) else { return }
            defer { try? handle.close() }
            try? handle.seek(toOffset: UInt64(start))
            var sent = start
            var remaining = len
            while remaining > 0 {
                let chunk = handle.readData(ofLength: Int(min(1 << 20, remaining)))
                if chunk.isEmpty { break }
                await sink.write(chunk)
                sent += Int64(chunk.count)
                remaining -= Int64(chunk.count)
                onProgress(fileId, sent, size)
            }
        })
    }

    // MARK: helpers

    /// UUID-shaped ids only (hex + hyphen) — these index file paths on the receiver.
    static func isSafeId(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64 && id.allSatisfy { $0.isHexDigit || $0 == "-" }
    }

    /// Start byte of a `bytes=<start>-` Range header.
    private static func rangeBytesStart(_ header: String) -> Int64? {
        let s = header.lowercased().replacingOccurrences(of: "bytes=", with: "")
        let part = s.split(separator: "-", maxSplits: 1).first.map(String.init) ?? s
        return Int64(part.trimmingCharacters(in: .whitespaces))
    }
}
