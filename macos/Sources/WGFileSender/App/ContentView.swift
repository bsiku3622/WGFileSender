import SwiftUI

struct ContentView: View {
    @EnvironmentObject var state: AppState
    @AppStorage("appLanguage") private var langRaw = Lang.initial.rawValue
    private var lang: Lang { Lang(rawValue: langRaw) ?? .en }

    var body: some View {
        TabView(selection: $state.selectedTab) {
            DevicesView()
                .tabItem { Label(L(.devices, lang), systemImage: "laptopcomputer") }
                .tag(0)
            TransfersView()
                .tabItem { Label(L(.transfers, lang), systemImage: "arrow.left.arrow.right") }
                .tag(1)
            SettingsView()
                .tabItem { Label(L(.settings, lang), systemImage: "gearshape") }
                .tag(2)
        }
        .frame(minWidth: 560, minHeight: 440)
        // Incoming pairing prompt is global so it surfaces from any tab.
        .sheet(item: $state.pendingPairing) { _ in IncomingPairSheet() }
    }
}
