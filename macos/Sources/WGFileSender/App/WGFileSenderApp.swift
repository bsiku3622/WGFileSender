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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Circle()
                    .fill(state.listenerError == nil ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(state.listenerError == nil
                     ? "Listening on port \(state.settings.port)"
                     : "Listener offline")
                    .font(.callout)
            }

            if state.activeTransferCount > 0 {
                Label("\(state.activeTransferCount) transferring…", systemImage: "arrow.left.arrow.right")
                    .font(.callout).foregroundStyle(.secondary)
            }

            Divider()

            Text("\(state.peers.count) paired device\(state.peers.count == 1 ? "" : "s")")
                .font(.caption).foregroundStyle(.secondary)

            Button("Open WGFileSender") {
                NSApp.activate(ignoringOtherApps: true)
                for window in NSApp.windows where window.canBecomeMain {
                    window.makeKeyAndOrderFront(nil)
                }
            }
            Button("Quit") { NSApp.terminate(nil) }
        }
        .padding(14)
        .frame(width: 240)
    }
}
