import SwiftUI

/// Shown on the receiving side when another device requests pairing.
struct IncomingPairSheet: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        if let pending = state.pendingPairing {
            VStack(spacing: 18) {
                Image(systemName: "person.badge.key.fill")
                    .font(.system(size: 40)).foregroundStyle(.tint)
                Text(String(format: L(.wantsToPair, lang), pending.body.deviceName))
                    .font(.headline).multilineTextAlignment(.center)
                Text(L(.confirmPinMatch, lang))
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Text(AppState.pretty(pending.body.pin))
                    .font(.system(size: 34, weight: .bold, design: .monospaced))
                    .tracking(4)
                HStack(spacing: 12) {
                    Button(L(.decline, lang), role: .cancel) { state.declineIncomingPair() }
                    Button(L(.accept, lang)) { state.acceptIncomingPair() }
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
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        if let outgoing = state.outgoingPairing {
            VStack(spacing: 18) {
                if outgoing.failed {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 40)).foregroundStyle(.red)
                } else {
                    ProgressView().controlSize(.large)
                }
                Text(String(format: L(.pairingWith, lang), outgoing.address)).font(.headline)
                Text(AppState.pretty(outgoing.pin))
                    .font(.system(size: 34, weight: .bold, design: .monospaced))
                    .tracking(4)
                Text(outgoing.status)
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button(outgoing.failed ? L(.close, lang) : L(.cancel, lang), role: .cancel) {
                    state.dismissOutgoingPair()
                }
            }
            .padding(28)
            .frame(width: 360)
        }
    }
}
