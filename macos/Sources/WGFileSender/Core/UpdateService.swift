import Foundation

/// Minimal subset of a GitHub release we care about.
struct GitHubRelease: Codable {
    let tagName: String
    let htmlUrl: String
    let body: String?
    let prerelease: Bool
    let assets: [Asset]

    struct Asset: Codable {
        let name: String
        let browserDownloadUrl: String
        let size: Int64
    }
}

/// A newer release resolved for this platform.
struct UpdateInfo: Equatable {
    let version: String         // normalized, e.g. "0.2.0"
    let releaseNotes: String
    let pageUrl: String
    let assetUrl: String?       // direct download for this platform; nil → open the page
    let assetName: String?
    let assetSize: Int64
}

enum UpdateState: Equatable {
    case idle
    case checking
    case upToDate
    case available(UpdateInfo)
    case downloading(Double)    // progress 0…1
    case downloaded(URL)        // local file ready to open
    case failed(String)
}

enum UpdateError: LocalizedError {
    case http(Int)
    case noAsset
    case badResponse

    var errorDescription: String? {
        switch self {
        case .http(let c): return "GitHub returned \(c)"
        case .noAsset: return "No downloadable asset for this platform"
        case .badResponse: return "Unexpected response"
        }
    }
}

/// Checks GitHub Releases for a newer build and downloads the macOS asset.
final class UpdateService {
    static let repo = "bsiku3622/WGFileSender"

    /// This build's version, read from the bundle (falls back to 0.0.0 in dev runs).
    let currentVersion: String

    init() {
        currentVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0.0.0"
    }

    /// Returns update details if the latest release is newer than the running build, else nil.
    func checkForUpdate() async throws -> UpdateInfo? {
        let release = try await latestRelease()
        let latest = Self.normalize(release.tagName)
        guard Self.isNewer(latest, than: currentVersion) else { return nil }
        let asset = Self.macAsset(release.assets)
        return UpdateInfo(version: latest, releaseNotes: (release.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                          pageUrl: release.htmlUrl, assetUrl: asset?.browserDownloadUrl,
                          assetName: asset?.name, assetSize: asset?.size ?? 0)
    }

    /// Downloads the asset into ~/Downloads and returns its local URL.
    func download(_ info: UpdateInfo, progress: @escaping (Double) -> Void) async throws -> URL {
        guard let urlStr = info.assetUrl, let url = URL(string: urlStr) else { throw UpdateError.noAsset }
        let delegate = DownloadProgressDelegate(onProgress: progress)
        let (tempURL, resp) = try await URLSession.shared.download(from: url, delegate: delegate)
        if let http = resp as? HTTPURLResponse, http.statusCode != 200 { throw UpdateError.http(http.statusCode) }
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let dest = downloads.appendingPathComponent(info.assetName ?? url.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tempURL, to: dest)
        return dest
    }

    // MARK: networking

    private func latestRelease() async throws -> GitHubRelease {
        var req = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repo)/releases/latest")!)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 20
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UpdateError.badResponse }
        guard http.statusCode == 200 else { throw UpdateError.http(http.statusCode) }
        let dec = JSONDecoder()
        dec.keyDecodingStrategy = .convertFromSnakeCase
        return try dec.decode(GitHubRelease.self, from: data)
    }

    // MARK: asset selection + version compare

    /// Prefers a .dmg, then a mac-tagged .zip, then any .zip.
    static func macAsset(_ assets: [GitHubRelease.Asset]) -> GitHubRelease.Asset? {
        assets.first { $0.name.lowercased().hasSuffix(".dmg") }
            ?? assets.first { let n = $0.name.lowercased(); return n.contains("mac") && n.hasSuffix(".zip") }
            ?? assets.first { $0.name.lowercased().hasSuffix(".zip") }
    }

    static func normalize(_ tag: String) -> String {
        tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
    }

    static func isNewer(_ a: String, than b: String) -> Bool { compare(a, b) > 0 }

    /// semver-ish: numeric major.minor.patch; a pre-release (`-…`) ranks below its release.
    static func compare(_ a: String, _ b: String) -> Int {
        let (av, ap) = splitPre(a)
        let (bv, bp) = splitPre(b)
        for i in 0..<3 where av[i] != bv[i] { return av[i] < bv[i] ? -1 : 1 }
        if ap.isEmpty != bp.isEmpty { return ap.isEmpty ? 1 : -1 }
        if ap != bp { return ap < bp ? -1 : 1 }
        return 0
    }

    private static func splitPre(_ s: String) -> ([Int], String) {
        let parts = s.split(separator: "-", maxSplits: 1)
        var nums = (parts.first.map(String.init) ?? "0").split(separator: ".").map { Int($0) ?? 0 }
        while nums.count < 3 { nums.append(0) }
        return (Array(nums.prefix(3)), parts.count > 1 ? String(parts[1]) : "")
    }
}

/// Reports download progress via the session delegate's didWriteData.
final class DownloadProgressDelegate: NSObject, URLSessionDownloadDelegate {
    private let onProgress: (Double) -> Void
    init(onProgress: @escaping (Double) -> Void) { self.onProgress = onProgress }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        onProgress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }
}
