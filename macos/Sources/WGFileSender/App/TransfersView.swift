import SwiftUI

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
                    .disabled(!state.transfers.contains { $0.state != .active })
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            Divider()

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
                if transfer.state == .active {
                    ProgressView(value: transfer.progress)
                        .progressViewStyle(.linear)
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
        case .active:
            return "\(dir) \(transfer.peerName) · \(transfer.transferredBytes.humanBytes) / \(transfer.totalBytes.humanBytes)"
        case .completed:
            return "\(dir) \(transfer.peerName) · \(transfer.totalBytes.humanBytes)"
        case .failed:
            return "\(dir) \(transfer.peerName) · \(transfer.error ?? L(.failed, lang))"
        }
    }

    /// Green for received, orange for sent; red on failure.
    private var iconColor: Color {
        if transfer.state == .failed { return .red }
        return transfer.direction == .incoming ? .green : .orange
    }

    /// Right side: progress while active, otherwise a "⋯" actions menu.
    @ViewBuilder private var trailing: some View {
        if transfer.state == .active {
            HStack(spacing: 8) {
                Text("\(Int(transfer.progress * 100))%")
                    .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                Button { state.cancelTransfer(transfer) } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help(L(.cancel, lang))
            }
        } else {
            Menu { menuItems } label: {
                Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(.secondary)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .frame(width: 28)
        }
    }

    @ViewBuilder private var menuItems: some View {
        Button(L(.open, lang)) { state.openTransferFile(transfer) }
            .disabled(!state.transferFileExists(transfer))
        Button(L(.revealInFinder, lang)) { state.revealTransferFile(transfer) }
            .disabled(!state.transferFileExists(transfer))
        if transfer.direction == .outgoing && transfer.state == .failed {
            Divider()
            Button(L(.resend, lang)) { state.resendTransfer(transfer) }
                .disabled(transfer.localPath == nil)
        }
        if transfer.direction == .incoming {
            Divider()
            Button(L(.renameFile, lang)) { newName = transfer.fileName; renaming = true }
                .disabled(!state.transferFileExists(transfer))
            Button(L(.delete, lang), role: .destructive) { state.deleteTransferFile(transfer) }
        }
    }
}
