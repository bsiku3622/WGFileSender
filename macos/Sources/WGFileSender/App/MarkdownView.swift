import SwiftUI

/// Tiny markdown renderer for release notes — handles `#`/`##` headers, `-`/`*` bullets,
/// and `**bold**` inline. Intentionally minimal; not a full CommonMark implementation.
struct MarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(text.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                lineView(line)
            }
        }
    }

    @ViewBuilder private func lineView(_ line: String) -> some View {
        if line.hasPrefix("## ") {
            Text(inline(String(line.dropFirst(3)))).font(.subheadline).bold()
        } else if line.hasPrefix("# ") {
            Text(inline(String(line.dropFirst(2)))).font(.headline)
        } else if line.hasPrefix("- ") || line.hasPrefix("* ") {
            HStack(alignment: .top, spacing: 6) {
                Text("•").foregroundStyle(.secondary)
                Text(inline(String(line.dropFirst(2))))
            }
            .font(.caption)
        } else if line.trimmingCharacters(in: .whitespaces).isEmpty {
            Color.clear.frame(height: 1)
        } else {
            Text(inline(line)).font(.caption)
        }
    }

    private func inline(_ s: String) -> AttributedString {
        (try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(s)
    }
}
