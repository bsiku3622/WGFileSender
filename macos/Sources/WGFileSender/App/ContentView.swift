import SwiftUI

struct ContentView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        TabView {
            DevicesView()
                .tabItem { Label(L(.devices, lang), systemImage: "laptopcomputer") }
            TransfersView()
                .tabItem { Label(L(.transfers, lang), systemImage: "arrow.left.arrow.right") }
            SettingsView()
                .tabItem { Label(L(.settings, lang), systemImage: "gearshape") }
        }
        .frame(minWidth: 560, minHeight: 440)
        // Incoming pairing prompt is global so it surfaces from any tab.
        .sheet(item: $state.pendingPairing) { _ in IncomingPairSheet() }
    }
}
