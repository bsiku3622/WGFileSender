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

            Section(L(.updates, lang)) {
                LabeledContent(L(.currentVersion, lang)) {
                    Text("v\(state.appVersion)").foregroundStyle(.secondary)
                }
                updateContent
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

    /// State-driven UI for the Updates section.
    @ViewBuilder private var updateContent: some View {
        switch state.updateState {
        case .idle:
            Button(L(.checkForUpdates, lang)) { state.checkForUpdates(manual: true) }
        case .checking:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(L(.checkingForUpdates, lang)).foregroundStyle(.secondary)
            }
        case .upToDate:
            HStack {
                Text(L(.upToDate, lang)).foregroundStyle(.secondary)
                Spacer()
                Button(L(.checkForUpdates, lang)) { state.checkForUpdates(manual: true) }
            }
        case .available(let info):
            VStack(alignment: .leading, spacing: 8) {
                Text(String(format: L(.updateAvailable, lang), info.version)).font(.headline)
                if !info.releaseNotes.isEmpty {
                    Text(L(.whatsNew, lang)).font(.caption).foregroundStyle(.secondary)
                    ScrollView {
                        Text(info.releaseNotes).font(.caption)
                            .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                    }
                    .frame(maxHeight: 120)
                }
                HStack {
                    Button(L(.downloadUpdate, lang)) { state.downloadUpdate() }
                        .buttonStyle(.borderedProminent)
                    Button(L(.later, lang)) { state.dismissUpdate() }
                }
            }
        case .downloading(let p):
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: p)
                Text("\(L(.downloadingUpdate, lang)) \(Int(p * 100))%")
                    .font(.caption).foregroundStyle(.secondary)
            }
        case .downloaded:
            VStack(alignment: .leading, spacing: 8) {
                Text(L(.updateDownloadedHint, lang)).font(.caption).foregroundStyle(.secondary)
                Button(L(.openToInstall, lang)) { state.revealUpdate() }
                    .buttonStyle(.borderedProminent)
            }
        case .failed(let msg):
            VStack(alignment: .leading, spacing: 6) {
                Text(String(format: L(.updateCheckFailed, lang), msg))
                    .font(.caption).foregroundStyle(.red)
                Button(L(.retry, lang)) { state.checkForUpdates(manual: true) }
            }
        }
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
