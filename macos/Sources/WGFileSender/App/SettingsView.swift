import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("showMenuBarIcon") private var showMenuBarIcon = true
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }
    @State private var portText = ""
    @State private var editingName = false

    var body: some View {
        Form {
            Section(L(.thisDevice, lang)) {
                LabeledContent(L(.name, lang)) {
                    HStack(spacing: 10) {
                        Text(state.identity.deviceName)
                            .foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.tail)
                        Button(L(.edit, lang)) { editingName = true }
                    }
                }
                LabeledContent(L(.identifier, lang)) {
                    Text(state.identity.deviceId)
                        .font(.caption).foregroundStyle(.secondary)
                        .lineLimit(1).truncationMode(.middle)
                        .textSelection(.enabled)
                }
            }

            Section(L(.receiving, lang)) {
                LabeledContent(L(.downloadFolder, lang)) {
                    HStack(spacing: 10) {
                        Text(state.settings.downloadRoot)
                            .font(.caption).foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.middle)
                        Button(L(.choose, lang)) { chooseFolder() }
                    }
                }
                LabeledContent(L(.listenPort, lang)) {
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

            Section(L(.appearance, lang)) {
                Toggle(L(.showMenuBarIcon, lang), isOn: $showMenuBarIcon)
                Text(L(.showMenuBarIconHint, lang))
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section(L(.language, lang)) {
                Picker(L(.language, lang), selection: $langRaw) {
                    ForEach(Lang.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }

            Section {
                Text(L(.settingsFooter, lang))
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
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L(.deviceNameTitle, lang)).font(.headline)
            Text(L(.deviceNameHint, lang))
                .font(.callout).foregroundStyle(.secondary)
            TextField(L(.deviceNamePlaceholder, lang), text: $name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button(L(.cancel, lang), role: .cancel) { dismiss() }
                Button(L(.save, lang)) {
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
