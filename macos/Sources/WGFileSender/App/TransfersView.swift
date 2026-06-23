import SwiftUI

struct TransfersView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Transfers").font(.title2).bold()
                Spacer()
                Button("Clear Finished") { state.clearFinishedTransfers() }
                    .disabled(!state.transfers.contains { $0.state != .active })
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            Divider()

            if state.transfers.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Image(systemName: "tray").font(.system(size: 40)).foregroundStyle(.secondary)
                    Text("No transfers yet").font(.headline)
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

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: transfer.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
                .font(.title2)
                .foregroundStyle(Theme.color(for: transfer.state))
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
            statusBadge
        }
        .padding(.vertical, 6)
    }

    private var subtitle: String {
        let dir = transfer.direction == .incoming ? "from" : "to"
        switch transfer.state {
        case .active:
            return "\(dir) \(transfer.peerName) · \(transfer.transferredBytes.humanBytes) / \(transfer.totalBytes.humanBytes)"
        case .completed:
            return "\(dir) \(transfer.peerName) · \(transfer.totalBytes.humanBytes)"
        case .failed:
            return "\(dir) \(transfer.peerName) · \(transfer.error ?? "failed")"
        }
    }

    @ViewBuilder private var statusBadge: some View {
        switch transfer.state {
        case .active: Text("\(Int(transfer.progress * 100))%").font(.caption).monospacedDigit().foregroundStyle(.secondary)
        case .completed: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
        }
    }
}
