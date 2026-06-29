import Foundation
import CryptoKit

enum WGFSError: LocalizedError {
    case http(Int)
    case declined
    case badResponse
    case unreachable(String)

    var errorDescription: String? {
        switch self {
        case .http(let c): return "Server returned \(c)"
        case .declined: return "The other device declined"
        case .badResponse: return "Unexpected response"
        case .unreachable(let s): return "Can't reach device: \(s)"
        }
    }
}

/// Outbound side: probes, pairing handshake, and file uploads.
final class SendClient {
    private let config: SharedConfig
    init(config: SharedConfig) { self.config = config }

    func ping(address: String) async throws -> PingResponse {
        let (data, resp) = try await dataTask(url: "http://\(address)/ping")
        try check(resp, expect: 200)
        guard let r = try? JSONDecoder().decode(PingResponse.self, from: data) else {
            throw WGFSError.badResponse
        }
        return r
    }

    /// Sends the pairing request; returns the accept response (peer identity + our tokenOut).
    func requestPair(address: String, sessionId: String, pin: String) async throws -> PairAcceptResponse {
        let id = config.identity
        let body = PairRequestBody(deviceId: id.deviceId, deviceName: id.deviceName,
                                   platform: id.platform, sessionId: sessionId, pin: pin,
                                   port: config.settings.port)
        var req = URLRequest(url: URL(string: "http://\(address)/pair/request")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(body)
        req.timeoutInterval = 90   // waits for the human to accept
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode == 403 { throw WGFSError.declined }
        try check(resp, expect: 200)
        guard let r = try? JSONDecoder().decode(PairAcceptResponse.self, from: data) else {
            throw WGFSError.badResponse
        }
        return r
    }

    /// Pushes our issued token to the peer so the link is symmetric.
    func confirmPair(address: String, tokenOut: String, ourTokenForPeer: String) async throws {
        let id = config.identity
        let body = PairConfirmBody(deviceId: id.deviceId, token: ourTokenForPeer)
        var req = URLRequest(url: URL(string: "http://\(address)/pair/confirm")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(tokenOut)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONEncoder().encode(body)
        let (_, resp) = try await URLSession.shared.data(for: req)
        try check(resp, expect: 204)
    }

    func sendFile(to peer: PeerDevice, fileURL: URL, transferId: String, tryResume: Bool = false,
                  progress: @escaping (Int64, Int64) -> Void) async throws {
        let size = fileSize(fileURL)
        let hash = try sha256(fileURL)
        let id = config.identity
        let encodedName = fileURL.lastPathComponent
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? fileURL.lastPathComponent

        // On a resume attempt, ask the receiver how many bytes it already holds and send
        // only the rest. First-time sends skip the round-trip and stream the whole file.
        var offset: Int64 = 0
        if tryResume, let have = try? await sendStatus(peer: peer, transferId: transferId),
           have > 0, have < size {
            offset = have
        }

        var req = URLRequest(url: URL(string: "http://\(peer.peerAddress)/send")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(peer.tokenOut)", forHTTPHeaderField: "Authorization")
        req.setValue(id.deviceId, forHTTPHeaderField: "X-WGFS-Device-Id")
        req.setValue(id.deviceName, forHTTPHeaderField: "X-WGFS-Device-Name")
        req.setValue(encodedName, forHTTPHeaderField: "X-WGFS-File-Name")
        req.setValue(String(size), forHTTPHeaderField: "X-WGFS-File-Size")
        req.setValue(hash, forHTTPHeaderField: "X-WGFS-Sha256")
        req.setValue(transferId, forHTTPHeaderField: "X-WGFS-Transfer-Id")

        var uploadURL = fileURL
        var tempSlice: URL?
        if offset > 0 {
            req.setValue("bytes \(offset)-\(size - 1)/\(size)", forHTTPHeaderField: "Content-Range")
            let slice = try sliceFile(fileURL, from: offset)
            tempSlice = slice
            uploadURL = slice
        }
        defer { if let t = tempSlice { try? FileManager.default.removeItem(at: t) } }

        // Pass the progress delegate per-call (not as a session-level delegate, which
        // deadlocks the async upload variant).
        let delegate = UploadProgressDelegate(total: size, baseline: offset, onProgress: progress)
        let (_, resp) = try await URLSession.shared.upload(for: req, fromFile: uploadURL, delegate: delegate)
        try check(resp, expect: 200)
    }

    /// Bytes the receiver already has for this transfer (0 if none / unreachable).
    private func sendStatus(peer: PeerDevice, transferId: String) async throws -> Int64 {
        var req = URLRequest(url: URL(string: "http://\(peer.peerAddress)/send/status?transferId=\(transferId)")!)
        req.setValue("Bearer \(peer.tokenOut)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 15
        let (data, resp) = try await URLSession.shared.data(for: req)
        try check(resp, expect: 200)
        return (try? JSONDecoder().decode(SendStatusResponse.self, from: data))?.received ?? 0
    }

    /// Writes bytes `[offset, end)` of `url` to a temp file for a ranged upload.
    private func sliceFile(_ url: URL, from offset: Int64) throws -> URL {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".part")
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let out = try FileHandle(forWritingTo: tmp)
        defer { try? out.close() }
        while case let d = handle.readData(ofLength: 1 << 20), !d.isEmpty {
            try out.write(contentsOf: d)
        }
        return tmp
    }

    // MARK: helpers

    private func dataTask(url: String) async throws -> (Data, URLResponse) {
        guard let u = URL(string: url) else { throw WGFSError.unreachable(url) }
        do { return try await URLSession.shared.data(from: u) }
        catch { throw WGFSError.unreachable(error.localizedDescription) }
    }

    private func check(_ resp: URLResponse, expect: Int) throws {
        guard let http = resp as? HTTPURLResponse else { throw WGFSError.badResponse }
        guard http.statusCode == expect else { throw WGFSError.http(http.statusCode) }
    }

    private func fileSize(_ url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    private func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = handle.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Reports upload progress via the task delegate's didSendBodyData.
final class UploadProgressDelegate: NSObject, URLSessionTaskDelegate {
    private let total: Int64
    private let baseline: Int64   // bytes already on the receiver before this (ranged) upload
    private let onProgress: (Int64, Int64) -> Void
    init(total: Int64, baseline: Int64 = 0, onProgress: @escaping (Int64, Int64) -> Void) {
        self.total = total
        self.baseline = baseline
        self.onProgress = onProgress
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        onProgress(baseline + totalBytesSent, total)
    }
}
