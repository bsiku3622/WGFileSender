import SwiftUI

/// Shown on the receiving side when another device requests pairing.
struct IncomingPairSheet: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        if let pending = state.pendingPairing {
            VStack(spacing: 18) {
                Image(systemName: "person.badge.key.fill")
                    .font(.system(size: 40)).foregroundStyle(.tint)
                Text("\(pending.body.deviceName) wants to pair")
                    .font(.headline).multilineTextAlignment(.center)
                Text("Confirm this PIN matches the one shown on that device.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Text(AppState.pretty(pending.body.pin))
                    .font(.system(size: 34, weight: .bold, design: .monospaced))
                    .tracking(4)
                HStack(spacing: 12) {
                    Button("Decline", role: .cancel) { state.declineIncomingPair() }
                    Button("Accept") { state.acceptIncomingPair() }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(28)
            .frame(width: 360)
        }
    }
}

/// Shown on the initiating side while waiting for the other device to accept.
struct OutgoingPairSheet: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        if let outgoing = state.outgoingPairing {
            VStack(spacing: 18) {
                if outgoing.failed {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 40)).foregroundStyle(.red)
                } else {
                    ProgressView().controlSize(.large)
                }
                Text("Pairing with \(outgoing.address)").font(.headline)
                Text(AppState.pretty(outgoing.pin))
                    .font(.system(size: 34, weight: .bold, design: .monospaced))
                    .tracking(4)
                Text(outgoing.status)
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button(outgoing.failed ? "Close" : "Cancel", role: .cancel) {
                    state.dismissOutgoingPair()
                }
            }
            .padding(28)
            .frame(width: 360)
        }
    }
}
