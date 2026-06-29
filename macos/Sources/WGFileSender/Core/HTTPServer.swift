import Foundation
import Network

struct HTTPRequest {
    let method: String
    let path: String
    let query: [String: String]
    let headers: [String: String]   // lowercased keys
    func header(_ name: String) -> String? { headers[name.lowercased()] }
    var bearerToken: String? {
        guard let a = header("authorization"), a.lowercased().hasPrefix("bearer ") else { return nil }
        return String(a.dropFirst(7))
    }
}

struct HTTPResponse {
    var status: Int
    var headers: [String: String] = [:]
    var body: Data = Data()

    static func status(_ code: Int) -> HTTPResponse { HTTPResponse(status: code) }

    static func json<T: Encodable>(_ value: T, status: Int = 200) -> HTTPResponse {
        let data = (try? JSONEncoder().encode(value)) ?? Data()
        return HTTPResponse(status: status, headers: ["Content-Type": "application/json"], body: data)
    }
}

/// Pulls the request body off the connection in chunks (streaming, never fully buffered).
/// Used serially by a single connection's `serve` loop, so unchecked Sendable is safe.
final class BodyStream: @unchecked Sendable {
    private let connection: NWConnection
    private var leftover: Data
    private var remaining: Int

    init(connection: NWConnection, leftover: Data, contentLength: Int) {
        self.connection = connection
        self.leftover = leftover
        self.remaining = contentLength
    }

    /// True once every byte declared by Content-Length has been delivered. When `read()`
    /// returns nil with this still false, the peer hung up mid-body (incomplete transfer).
    var isComplete: Bool { remaining <= 0 }

    func read() async -> Data? {
        if remaining <= 0 { return nil }
        if !leftover.isEmpty {
            let take = min(leftover.count, remaining)
            let chunk = leftover.prefix(take)
            leftover.removeFirst(take)
            remaining -= take
            return Data(chunk)
        }
        let want = min(65536, remaining)
        return await withCheckedContinuation { cont in
            connection.receive(minimumIncompleteLength: 1, maximumLength: want) { data, _, _, _ in
                if let data, !data.isEmpty {
                    // Guard against over-read: never hand back more than Content-Length.
                    let take = min(data.count, self.remaining)
                    self.remaining -= take
                    cont.resume(returning: take == data.count ? data : data.prefix(take))
                } else {
                    cont.resume(returning: nil)
                }
            }
        }
    }

    func readAll() async -> Data {
        var out = Data()
        while let chunk = await read() { out.append(chunk) }
        return out
    }
}

/// Minimal HTTP/1.1 server. One request per connection (Connection: close).
final class HTTPServer {
    typealias Handler = (HTTPRequest, BodyStream, String) async -> HTTPResponse

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "wgfs.http", attributes: .concurrent)
    private let handler: Handler

    init(handler: @escaping Handler) { self.handler = handler }

    func start(port: UInt16) throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        listener.newConnectionHandler = { [weak self] conn in
            conn.start(queue: self?.queue ?? .global())
            Task { await self?.serve(conn) }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func serve(_ conn: NWConnection) async {
        var buffer = Data()
        let terminator = Data("\r\n\r\n".utf8)
        while true {
            guard let chunk = await receive(conn) else { conn.cancel(); return }
            buffer.append(chunk)
            if let range = buffer.range(of: terminator) {
                let headData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                let leftover = buffer.subdata(in: range.upperBound..<buffer.endIndex)
                guard let request = Self.parseHead(headData) else {
                    await send(conn, .status(400)); conn.cancel(); return
                }
                let len = Int(request.header("content-length") ?? "") ?? 0
                let body = BodyStream(connection: conn, leftover: leftover, contentLength: len)
                let response = await handler(request, body, Self.remoteHost(of: conn))
                await send(conn, response)
                conn.cancel()
                return
            }
            if buffer.count > 64 * 1024 { await send(conn, .status(431)); conn.cancel(); return }
        }
    }

    private func receive(_ conn: NWConnection) async -> Data? {
        await withCheckedContinuation { cont in
            conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, _, _ in
                cont.resume(returning: (data?.isEmpty == false) ? data : nil)
            }
        }
    }

    private func send(_ conn: NWConnection, _ resp: HTTPResponse) async {
        var headers = resp.headers
        headers["Content-Length"] = String(resp.body.count)
        headers["Connection"] = "close"
        var head = "HTTP/1.1 \(resp.status) \(Self.reason(resp.status))\r\n"
        for (k, v) in headers { head += "\(k): \(v)\r\n" }
        head += "\r\n"
        var out = Data(head.utf8)
        out.append(resp.body)
        await withCheckedContinuation { cont in
            conn.send(content: out, completion: .contentProcessed { _ in cont.resume() })
        }
    }

    // MARK: parsing

    private static func parseHead(_ data: Data) -> HTTPRequest? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0])
        let target = String(parts[1])

        var path = target
        var query: [String: String] = [:]
        if let q = target.firstIndex(of: "?") {
            path = String(target[target.startIndex..<q])
            let qs = String(target[target.index(after: q)...])
            for pair in qs.split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                if kv.count == 2 {
                    query[String(kv[0])] = String(kv[1]).removingPercentEncoding ?? String(kv[1])
                }
            }
        }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
        return HTTPRequest(method: method, path: path, query: query, headers: headers)
    }

    private static func remoteHost(of conn: NWConnection) -> String {
        if case let .hostPort(host, _) = conn.endpoint {
            switch host {
            case .ipv4(let a): return "\(a)"
            case .ipv6(let a): return "\(a)".components(separatedBy: "%").first ?? "\(a)"
            case .name(let n, _): return n
            @unknown default: return ""
            }
        }
        return ""
    }

    private static func reason(_ code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 204: return "No Content"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 409: return "Conflict"
        case 431: return "Request Header Fields Too Large"
        default: return "Error"
        }
    }
}
