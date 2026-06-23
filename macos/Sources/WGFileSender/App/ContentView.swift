import SwiftUI

struct ContentView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        TabView {
            DevicesView()
                .tabItem { Label("Devices", systemImage: "laptopcomputer") }
            TransfersView()
                .tabItem { Label("Transfers", systemImage: "arrow.left.arrow.right") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .frame(minWidth: 560, minHeight: 440)
        // Incoming pairing prompt is global so it surfaces from any tab.
        .sheet(item: $state.pendingPairing) { _ in IncomingPairSheet() }
    }
}
