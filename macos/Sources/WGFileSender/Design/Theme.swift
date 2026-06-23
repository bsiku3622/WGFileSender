import SwiftUI

enum Theme {
    static func color(for state: TransferState) -> Color {
        switch state {
        case .active: return .accentColor
        case .completed: return .green
        case .failed: return .red
        }
    }

    static func icon(for platform: String) -> String {
        switch platform {
        case "android": return "smartphone"
        case "windows": return "pc"
        default: return "laptopcomputer"
        }
    }
}

extension Int64 {
    /// Human-readable byte count, e.g. "12.4 MB".
    var humanBytes: String {
        ByteCountFormatter.string(fromByteCount: self, countStyle: .file)
    }
}
