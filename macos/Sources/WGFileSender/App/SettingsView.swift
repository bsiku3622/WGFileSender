import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("showMenuBarIcon") private var showMenuBarIcon = true
    @State private var portText = ""
    @State private var editingName = false

    var body: some View {
        Form {
            Section("This Device") {
                LabeledContent("Name") {
                    HStack(spacing: 10) {
                        Text(state.identity.deviceName)
                            .foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.tail)
                        Button("Edit…") { editingName = true }
                    }
                }
                LabeledContent("Identifier") {
                    Text(state.identity.deviceId)
                        .font(.caption).foregroundStyle(.secondary)
                        .lineLimit(1).truncationMode(.middle)
                        .textSelection(.enabled)
                }
            }

            Section("Receiving") {
                LabeledContent("Download folder") {
                    HStack(spacing: 10) {
                        Text(state.settings.downloadRoot)
                            .font(.caption).foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.middle)
                        Button("Choose…") { chooseFolder() }
                    }
                }
                LabeledContent("Listen port") {
                    TextField("", text: $portText)
                        .labelsHidden()
                        .multilineTextAlignment(.trailing)
                        .lineLimit(1)
                        .frame(width: 80)
                        .onSubmit { applyPort() }
                }
                if let err = state.listenerError {
                    Text(err).font(.caption).foregroundStyle(.red)
                }
            }

            Section("Appearance") {
                Toggle("Show menu bar icon", isOn: $showMenuBarIcon)
                Text("When off, open the app from the Dock instead.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section {
                Text("Files arrive in a subfolder named after the sending device. Transfers go directly over your WireGuard tunnel — no relay server.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .onAppear { portText = String(state.settings.port) }
        .sheet(isPresented: $editingName) { EditNameSheet() }
    }

    private func applyPort() {
        guard let p = UInt16(portText.trimmingCharacters(in: .whitespaces)), p > 0 else {
            portText = String(state.settings.port); return
        }
        state.updatePort(p)
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.directoryURL = URL(fileURLWithPath: state.settings.downloadRoot)
        if panel.runModal() == .OK, let url = panel.url {
            state.updateDownloadRoot(url.path)
        }
    }
}

/// Modal for renaming this device.
struct EditNameSheet: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Device Name").font(.headline)
            Text("Shown to devices you pair with, and used as your subfolder name on their side.")
                .font(.callout).foregroundStyle(.secondary)
            TextField("Device name", text: $name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { state.updateDeviceName(trimmed) }
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 400)
        .onAppear { name = state.identity.deviceName }
    }
}
