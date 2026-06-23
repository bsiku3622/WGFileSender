import SwiftUI
import UniformTypeIdentifiers

struct DevicesView: View {
    @EnvironmentObject var state: AppState
    @State private var showAdd = false
    @State private var importPeer: PeerDevice?
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
                                onSend: { importPeer = peer },
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
            isPresented: Binding(get: { importPeer != nil }, set: { if !$0 { importPeer = nil } }),
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
            Text("Devices").font(.title2).bold()
            Spacer()
            Button { showAdd = true } label: { Label("Add Device", systemImage: "plus") }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "laptopcomputer.and.iphone")
                .font(.system(size: 44)).foregroundStyle(.secondary)
            Text("No paired devices").font(.headline)
            Text("Add a device by its WireGuard IP. Both sides confirm a PIN to pair.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 320)
            Button { showAdd = true } label: { Label("Add Device", systemImage: "plus") }
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

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: Theme.icon(for: peerPlatform))
                .font(.title2).foregroundStyle(.secondary).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.displayName).font(.body).bold()
                Text(peer.peerAddress).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button { onSend() } label: { Label("Send", systemImage: "paperplane") }
                .buttonStyle(.bordered)
            Menu {
                Button("Rename…") { onRename() }
                Button("Remove", role: .destructive) { onRemove() }
            } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).frame(width: 28)
        }
        .padding(.vertical, 6)
    }

    // platform isn't stored on the peer record yet; default to laptop glyph.
    private var peerPlatform: String { "" }
}

struct AddDeviceSheet: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add Device").font(.headline)
            Text("Enter the device's WireGuard address. The default port is \(kDefaultPort).")
                .font(.callout).foregroundStyle(.secondary)
            TextField("10.0.0.2 or 10.0.0.2:\(kDefaultPort)", text: $address)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Pair") {
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
    let peer: PeerDevice
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Rename Device").font(.headline)
            Text("Local name overrides the name this device advertises. Files arrive under this folder name.")
                .font(.callout).foregroundStyle(.secondary)
            TextField(peer.peerName, text: $name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Save") { state.renamePeer(peer, to: name); dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 380)
        .onAppear { name = peer.localName ?? peer.peerName }
    }
}
