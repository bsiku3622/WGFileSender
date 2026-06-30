import SwiftUI

/// Compact remaining-time label: "15s", "2m 10s", "1h 4m". Empty when not meaningful.
func etaString(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds > 0, seconds < 86_400 else { return "" }
    let s = Int(seconds.rounded())
    if s < 60 { return "\(s)s" }
    if s < 3600 { return "\(s / 60)m \(s % 60)s" }
    return "\(s / 3600)h \(s / 60 % 60)m"
}

struct TransfersView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L(.transfers, lang)).font(.title2).bold()
                Spacer()
                Button { state.openDownloadFolder() } label: { Label(L(.openFolder, lang), systemImage: "folder") }
                Button(L(.clearFinished, lang)) { state.clearFinishedTransfers() }
                    .disabled(!state.transfers.contains { $0.state == .completed || $0.state == .failed })
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            Divider()

            if !state.transfers.isEmpty {
                summaryBar.padding(.horizontal, 16).padding(.vertical, 8)
                Divider()
            }

            if state.transfers.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Image(systemName: "tray").font(.system(size: 40)).foregroundStyle(.secondary)
                    Text(L(.noTransfers, lang)).font(.headline)
                    Text(L(.noTransfersHint, lang)).font(.callout).foregroundStyle(.secondary)
                    Spacer()
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(state.transfers) { TransferRow(transfer: $0) }
                    .listStyle(.inset)
            }
        }
    }

    /// At-a-glance counts (and completed volume) across the transfer list.
    @ViewBuilder private var summaryBar: some View {
        let t = state.transfers
        let total = t.count
        let sending = t.filter { $0.state == .active && $0.direction == .outgoing }.count
        let receiving = t.filter { $0.state == .active && $0.direction == .incoming }.count
        let waiting = t.filter { $0.state == .pending || $0.state == .queued }.count
        let done = t.filter { $0.state == .completed }
        let failed = t.filter { $0.state == .failed }.count
        let stopped = t.filter { $0.state == .interrupted }.count
        let doneBytes = done.reduce(Int64(0)) { $0 + $1.totalBytes }
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L(.transferProgress, lang)).font(.caption).fontWeight(.semibold)
                Spacer()
                Text("\(done.count) / \(total)").font(.caption).monospacedDigit().foregroundStyle(.secondary)
            }
            ProgressView(value: total > 0 ? Double(done.count) / Double(total) : 0).tint(.green)
            HStack(spacing: 16) {
                if sending > 0 { stat(L(.sending, lang), sending, .orange) }
                if receiving > 0 { stat(L(.receivingActive, lang), receiving, .green) }
                if waiting > 0 { stat(L(.queued, lang), waiting, .secondary) }
                if !done.isEmpty { stat(L(.statDone, lang), done.count, .green, detail: doneBytes.humanBytes) }
                if failed > 0 { stat(L(.failed, lang), failed, .red) }
                if stopped > 0 { stat(L(.interrupted, lang), stopped, .yellow) }
                Spacer()
            }
        }
    }

    private func stat(_ label: String, _ count: Int, _ color: Color, detail: String? = nil) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text("\(count)").font(.callout).fontWeight(.semibold).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
            if let detail { Text("· \(detail)").font(.caption).foregroundStyle(.secondary) }
        }
    }
}

struct TransferRow: View {
    let transfer: Transfer
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: transfer.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
                .font(.title2)
                .foregroundStyle(iconColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(transfer.fileName).font(.body).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
                if transfer.state == .active || transfer.state == .interrupted {
                    ProgressView(value: transfer.progress)
                        .progressViewStyle(.linear)
                        .tint(transfer.state == .interrupted ? .yellow : nil)
                }
            }
            Spacer()
            trailing
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .onTapGesture { state.openTransferFile(transfer) }
        .contextMenu { menuItems }
        .sheet(isPresented: $renaming) {
            VStack(alignment: .leading, spacing: 16) {
                Text(L(.renameFile, lang)).font(.headline)
                TextField("", text: $newName).textFieldStyle(.roundedBorder).labelsHidden()
                HStack {
                    Spacer()
                    Button(L(.cancel, lang), role: .cancel) { renaming = false }
                    Button(L(.save, lang)) {
                        state.renameTransferFile(transfer, to: newName); renaming = false
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .padding(24).frame(width: 380)
        }
    }

    private var subtitle: String {
        let dir = transfer.direction == .incoming ? L(.from, lang) : L(.to, lang)
        switch transfer.state {
        case .pending, .queued:
            return "\(dir) \(transfer.peerName) · \(L(.queued, lang))"
        case .active:
            if let e = transfer.error, !e.isEmpty {   // reconnecting between auto-retries
                return "\(dir) \(transfer.peerName) · \(e)"
            }
            var s = "\(dir) \(transfer.peerName) · \(transfer.transferredBytes.humanBytes) / \(transfer.totalBytes.humanBytes)"
            if let rate = state.transferRates[transfer.id], rate > 1 {
                s += " · \(Int64(rate).humanBytes)/s"
                let eta = etaString(Double(transfer.totalBytes - transfer.transferredBytes) / rate)
                if !eta.isEmpty { s += " · \(eta) \(L(.remaining, lang))" }
            }
            return s
        case .completed:
            return "\(dir) \(transfer.peerName) · \(transfer.totalBytes.humanBytes)"
        case .interrupted:
            return "\(dir) \(transfer.peerName) · \(L(.interrupted, lang)) · \(Int(transfer.progress * 100))%"
        case .failed:
            return "\(dir) \(transfer.peerName) · \(transfer.error ?? L(.failed, lang))"
        }
    }

    /// Green for received, orange for sent; yellow when interrupted, red on failure.
    private var iconColor: Color {
        switch transfer.state {
        case .failed: return .red
        case .interrupted: return .yellow
        case .pending, .queued: return .secondary
        default: return transfer.direction == .incoming ? .green : .orange
        }
    }

    /// Right side: progress while active, otherwise a "⋯" actions menu.
    @ViewBuilder private var trailing: some View {
        if transfer.state == .active || transfer.state == .queued {
            HStack(spacing: 8) {
                if transfer.state == .active {
                    Text("\(Int(transfer.progress * 100))%")
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                }
                Button { state.cancelTransfer(transfer) } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help(L(.cancel, lang))
            }
        } else {
            HStack(spacing: 2) {
                if transfer.direction == .outgoing,
                   transfer.state == .interrupted || transfer.state == .failed,
                   transfer.localPath != nil {
                    Button { state.resumeTransfer(transfer) } label: {
                        Image(systemName: "arrow.clockwise.circle.fill")
                            .font(.title3).foregroundStyle(.tint)
                    }
                    .buttonStyle(.plain)
                    .help(L(.resume, lang))
                }
                Menu { menuItems } label: {
                    Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(.secondary)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .frame(width: 28)
            }
        }
    }

    @ViewBuilder private var menuItems: some View {
        Button(L(.open, lang)) { state.openTransferFile(transfer) }
            .disabled(!state.transferFileExists(transfer))
        Button(L(.revealInFinder, lang)) { state.revealTransferFile(transfer) }
            .disabled(!state.transferFileExists(transfer))
        if transfer.direction == .outgoing && (transfer.state == .failed || transfer.state == .interrupted) {
            Divider()
            Button(L(.resume, lang)) { state.resumeTransfer(transfer) }
                .disabled(transfer.localPath == nil)
        }
        if transfer.direction == .incoming {
            Divider()
            Button(L(.renameFile, lang)) { newName = transfer.fileName; renaming = true }
                .disabled(!state.transferFileExists(transfer))
            Button(L(.delete, lang), role: .destructive) { state.deleteTransferFile(transfer) }
        } else {
            Divider()
            if transfer.state == .active || transfer.state == .queued {
                Button(L(.cancel, lang), role: .destructive) { state.cancelTransfer(transfer) }
            } else {
                Button(L(.removeFromList, lang), role: .destructive) { state.removeTransfer(transfer) }
            }
        }
    }
}
