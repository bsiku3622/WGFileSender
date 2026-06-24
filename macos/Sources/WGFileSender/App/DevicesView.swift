import SwiftUI
import UniformTypeIdentifiers

struct DevicesView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    @State private var showAdd = false
    @State private var importPeer: PeerDevice?
    @State private var showImporter = false
    @State private var renaming: PeerDevice?

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if state.peers.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(state.peers) { peer in
                        PeerRow(peer: peer,
                                onSend: { importPeer = peer; showImporter = true },
                                onRename: { renaming = peer },
                                onRemove: { state.removePeer(peer) })
                            .dropDestination(for: URL.self) { urls, _ in
                                state.sendFiles(urls, to: peer)
                                return true
                            }
                    }
                }
                .listStyle(.inset)
            }
        }
        .sheet(isPresented: $showAdd) { AddDeviceSheet() }
        .sheet(item: $state.outgoingPairing) { _ in OutgoingPairSheet() }
        .sheet(item: $renaming) { peer in RenameSheet(peer: peer) }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.item], allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result, let peer = importPeer {
                state.sendFiles(urls, to: peer)
            }
            importPeer = nil
        }
    }

    private var header: some View {
        HStack {
            Text(L(.devices, lang)).font(.title2).bold()
            Spacer()
            Button { showAdd = true } label: { Label(L(.addDevice, lang), systemImage: "plus") }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "laptopcomputer.and.iphone")
                .font(.system(size: 44)).foregroundStyle(.secondary)
            Text(L(.noPairedDevices, lang)).font(.headline)
            Text(L(.noPairedDevicesHint, lang))
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 320)
            Button { showAdd = true } label: { Label(L(.addDevice, lang), systemImage: "plus") }
                .buttonStyle(.borderedProminent)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PeerRow: View {
    let peer: PeerDevice
    let onSend: () -> Void
    let onRename: () -> Void
    let onRemove: () -> Void
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "laptopcomputer")
                .font(.title2).foregroundStyle(.secondary).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.displayName).font(.body).bold()
                Text(peer.peerAddress).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button { onSend() } label: { Label(L(.send, lang), systemImage: "paperplane") }
                .buttonStyle(.bordered)
            Menu {
                Button(L(.rename, lang)) { onRename() }
                Button(L(.remove, lang), role: .destructive) { onRemove() }
            } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .frame(width: 28)
        }
        .padding(.vertical, 6)
    }
}

struct AddDeviceSheet: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }
    @State private var address = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L(.addDevice, lang)).font(.headline)
            Text(String(format: L(.addDeviceHint, lang), Int(kDefaultPort)))
                .font(.callout).foregroundStyle(.secondary)
            TextField(String(format: L(.addDevicePlaceholder, lang), Int(kDefaultPort)), text: $address)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button(L(.cancel, lang), role: .cancel) { dismiss() }
                Button(L(.pair, lang)) {
                    state.startOutgoingPair(address: normalized(address))
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 380)
    }

    private func normalized(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespaces)
        return trimmed.contains(":") ? trimmed : "\(trimmed):\(kDefaultPort)"
    }
}

struct RenameSheet: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }
    let peer: PeerDevice
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L(.renameTitle, lang)).font(.headline)
            Text(L(.renameHint, lang))
                .font(.callout).foregroundStyle(.secondary)
            TextField(peer.peerName, text: $name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button(L(.cancel, lang), role: .cancel) { dismiss() }
                Button(L(.save, lang)) { state.renamePeer(peer, to: name); dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 380)
        .onAppear { name = peer.localName ?? peer.peerName }
    }
}
