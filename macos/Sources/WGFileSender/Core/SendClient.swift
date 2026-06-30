import Foundation
import CryptoKit

enum WGFSError: LocalizedError {
    case http(Int)
    case declined
    case badResponse
    case unreachable(String)
    case incomplete        // server closed before the whole file arrived — resumable
    case checksum          // fully received but hash mismatched — corrupt

    var errorDescription: String? {
        switch self {
        case .http(let c): return "Server returned \(c)"
        case .declined: return "The other device declined"
        case .badResponse: return "Unexpected response"
        case .unreachable(let s): return "Can't reach device: \(s)"
        case .incomplete: return "Connection ended early"
        case .checksum: return "Checksum mismatch"
        }
    }
}

/// Outbound side: probes, pairing, sending a manifest (`/offer`), and — as the receiver —
/// pulling file bytes (`/pull`) with resume + hash verification.
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

    func requestPair(address: String, sessionId: String, pin: String) async throws -> PairAcceptResponse {
        let id = config.identity
        let body = PairRequestBody(deviceId: id.deviceId, deviceName: id.deviceName,
                                   platform: id.platform, sessionId: sessionId, pin: pin,
                                   port: config.settings.port)
        var req = URLRequest(url: URL(string: "http://\(address)/pair/request")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(body)
        req.timeoutInterval = 90
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode == 403 { throw WGFSError.declined }
        try check(resp, expect: 200)
        guard let r = try? JSONDecoder().decode(PairAcceptResponse.self, from: data) else {
            throw WGFSError.badResponse
        }
        return r
    }

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

    // MARK: sender — announce a batch manifest

    func offer(to peer: PeerDevice, batchId: String, files: [OfferFile]) async throws {
        let id = config.identity
        var req = URLRequest(url: URL(string: "http://\(peer.peerAddress)/offer")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(peer.tokenOut)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(id.deviceId, forHTTPHeaderField: "X-WGFS-Device-Id")
        req.setValue(id.deviceName, forHTTPHeaderField: "X-WGFS-Device-Name")
        req.httpBody = try JSONEncoder().encode(OfferBody(batchId: batchId, files: files))
        req.timeoutInterval = 30
        let (_, resp) = try await URLSession.shared.data(for: req)
        try check(resp, expect: 200)
    }

    // MARK: receiver — pull a file's bytes, resuming and verifying

    /// Pulls (or resumes) one file into `downloadRoot/<senderFolder>/<fileName>` and returns
    /// the saved path. Throws `.incomplete` (keep .part, retry) or `.checksum` (corrupt).
    func pullFile(from peer: PeerDevice, batchId: String, fileId: String, fileName: String,
                  expectedSize: Int64, expectedHash: String, senderFolder: String,
                  downloadRoot: String, progress: @escaping (Int64, Int64) -> Void) async throws -> String {
        let fm = FileManager.default
        let partURL = partURL(fileId)
        try? fm.createDirectory(at: partURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let start = partFileSize(fileId)   // resume from whatever we already hold

        if start >= expectedSize, start > 0 {
            // Already have all the bytes; just verify + finalize.
            return try finalize(fileId: fileId, fileName: fileName, expectedSize: expectedSize,
                                expectedHash: expectedHash, senderFolder: senderFolder, downloadRoot: downloadRoot)
        }

        var req = URLRequest(url: URL(string: "http://\(peer.peerAddress)/pull?batch=\(batchId)&file=\(fileId)")!)
        req.setValue("Bearer \(peer.tokenOut)", forHTTPHeaderField: "Authorization")
        if start > 0 { req.setValue("bytes=\(start)-", forHTTPHeaderField: "Range") }
        req.timeoutInterval = 60   // stall timeout; resets while bytes flow

        let delegate = PullProgressDelegate(baseline: start, total: expectedSize, onProgress: progress)
        let (tempURL, resp) = try await URLSession.shared.download(for: req, delegate: delegate)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 || http.statusCode == 206 else {
            throw WGFSError.http((resp as? HTTPURLResponse)?.statusCode ?? 0)
        }

        // Append the freshly downloaded range onto the .part.
        if start > 0 {
            guard let wh = try? FileHandle(forWritingTo: partURL) else { throw WGFSError.badResponse }
            try? wh.seekToEnd()
            if let th = try? FileHandle(forReadingFrom: tempURL) {
                while case let d = th.readData(ofLength: 1 << 20), !d.isEmpty { try? wh.write(contentsOf: d) }
                try? th.close()
            }
            try? wh.close()
            try? fm.removeItem(at: tempURL)
        } else {
            try? fm.removeItem(at: partURL)
            try fm.moveItem(at: tempURL, to: partURL)
        }

        guard partFileSize(fileId) >= expectedSize else { throw WGFSError.incomplete }
        return try finalize(fileId: fileId, fileName: fileName, expectedSize: expectedSize,
                            expectedHash: expectedHash, senderFolder: senderFolder, downloadRoot: downloadRoot)
    }

    /// Verifies the completed `.part` against the expected hash and moves it into place.
    private func finalize(fileId: String, fileName: String, expectedSize: Int64,
                          expectedHash: String, senderFolder: String, downloadRoot: String) throws -> String {
        let fm = FileManager.default
        let partURL = partURL(fileId)
        let digest = try sha256(partURL)
        guard digest == expectedHash.lowercased() else {
            try? fm.removeItem(at: partURL)   // corrupt — start over next round
            throw WGFSError.checksum
        }
        let folder = URL(fileURLWithPath: downloadRoot)
            .appendingPathComponent(safeComponent(senderFolder), isDirectory: true)
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let finalURL = uniqueURL(in: folder, fileName: fileName)
        try fm.moveItem(at: partURL, to: finalURL)
        return finalURL.path
    }

    // MARK: helpers

    func fileSize(_ url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    func sha256(_ url: URL) throws -> String {
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

    private func partURL(_ fileId: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WGFileSender/parts", isDirectory: true)
            .appendingPathComponent("\(fileId).part")
    }

    private func partFileSize(_ fileId: String) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: partURL(fileId).path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    private func safeComponent(_ name: String) -> String {
        let cleaned = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "..", with: "_")
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

    private func dataTask(url: String) async throws -> (Data, URLResponse) {
        guard let u = URL(string: url) else { throw WGFSError.unreachable(url) }
        do { return try await URLSession.shared.data(from: u) }
        catch { throw WGFSError.unreachable(error.localizedDescription) }
    }

    private func check(_ resp: URLResponse, expect: Int) throws {
        guard let http = resp as? HTTPURLResponse else { throw WGFSError.badResponse }
        guard http.statusCode == expect else { throw WGFSError.http(http.statusCode) }
    }
}

/// Reports pull progress (bytes already held + bytes written this request).
final class PullProgressDelegate: NSObject, URLSessionDownloadDelegate {
    private let baseline: Int64
    private let total: Int64
    private let onProgress: (Int64, Int64) -> Void
    init(baseline: Int64, total: Int64, onProgress: @escaping (Int64, Int64) -> Void) {
        self.baseline = baseline
        self.total = total
        self.onProgress = onProgress
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress(baseline + totalBytesWritten, total)
    }
}
