import SwiftUI

@main
struct WGFileSenderApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var state = AppState.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
                .frame(minWidth: 560, minHeight: 440)
        }
        .windowResizability(.contentMinSize)
        // Menu-bar status item is managed by AppDelegate (AppKit) for tight padding.
    }
}

/// Compact panel shown from the menu bar.
struct MenuBarContent: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Circle()
                    .fill(state.listenerError == nil ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(state.listenerError == nil
                     ? String(format: L(.listeningOnPort, lang), Int(state.settings.port))
                     : L(.listenerOffline, lang))
                    .font(.callout)
            }

            if state.activeTransferCount > 0 {
                Label(String(format: L(.transferring, lang), state.activeTransferCount),
                      systemImage: "arrow.left.arrow.right")
                    .font(.callout).foregroundStyle(.secondary)
            }

            Divider()

            Text(String(format: L(.pairedDevices, lang), state.peers.count))
                .font(.caption).foregroundStyle(.secondary)

            Button(L(.openApp, lang)) {
                NSApp.activate(ignoringOtherApps: true)
                for window in NSApp.windows where window.canBecomeMain {
                    window.makeKeyAndOrderFront(nil)
                }
            }
            Button(L(.quit, lang)) { NSApp.terminate(nil) }
        }
        .padding(14)
        .frame(width: 240)
    }
}
