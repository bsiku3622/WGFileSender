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

    func sendFile(to peer: PeerDevice, fileURL: URL, transferId: String,
                  progress: @escaping (Int64, Int64) -> Void) async throws {
        let size = fileSize(fileURL)
        let hash = try sha256(fileURL)
        let id = config.identity
        let encodedName = fileURL.lastPathComponent
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? fileURL.lastPathComponent

        var req = URLRequest(url: URL(string: "http://\(peer.peerAddress)/send")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(peer.tokenOut)", forHTTPHeaderField: "Authorization")
        req.setValue(id.deviceId, forHTTPHeaderField: "X-WGFS-Device-Id")
        req.setValue(id.deviceName, forHTTPHeaderField: "X-WGFS-Device-Name")
        req.setValue(encodedName, forHTTPHeaderField: "X-WGFS-File-Name")
        req.setValue(String(size), forHTTPHeaderField: "X-WGFS-File-Size")
        req.setValue(hash, forHTTPHeaderField: "X-WGFS-Sha256")
        req.setValue(transferId, forHTTPHeaderField: "X-WGFS-Transfer-Id")

        let delegate = UploadProgressDelegate(total: size, onProgress: progress)
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (_, resp) = try await session.upload(for: req, fromFile: fileURL)
        try check(resp, expect: 200)
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
    private let onProgress: (Int64, Int64) -> Void
    init(total: Int64, onProgress: @escaping (Int64, Int64) -> Void) {
        self.total = total
        self.onProgress = onProgress
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        onProgress(totalBytesSent, total)
    }
}
