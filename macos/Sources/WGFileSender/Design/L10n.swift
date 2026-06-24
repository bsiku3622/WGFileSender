import Foundation

/// App language, chosen in Settings (not tied to the system locale).
enum Lang: String, CaseIterable, Identifiable {
    case en, ko
    var id: String { rawValue }
    var label: String { self == .ko ? "한국어" : "English" }

    /// First-run default: follow the system language, else English.
    static var initial: Lang {
        if let saved = UserDefaults.standard.string(forKey: "appLanguage"),
           let lang = Lang(rawValue: saved) { return lang }
        return (Locale.preferredLanguages.first?.hasPrefix("ko") == true) ? .ko : .en
    }

    /// Current choice for non-view code (model layer).
    static var current: Lang {
        Lang(rawValue: UserDefaults.standard.string(forKey: "appLanguage") ?? "") ?? initial
    }
}

enum LKey {
    case devices, transfers, settings
    case add, addDevice, send, rename, remove
    case noPairedDevices, noPairedDevicesHint
    case noTransfers, noTransfersHint, clearFinished
    case thisDevice, name, identifier, edit
    case receiving, downloadFolder, choose, listenPort
    case appearance, showMenuBarIcon, showMenuBarIconHint
    case language, settingsFooter
    case deviceNameTitle, deviceNameHint, deviceNamePlaceholder
    case cancel, save, pair
    case addDeviceHint, addDevicePlaceholder
    case renameTitle, renameHint
    case wantsToPair, confirmPinMatch, decline, accept
    case pairingWith, close
    case listeningOnPort, listenerOffline, transferring, pairedDevices
    case openApp, quit, ready
    case from, to, failed
    case confirmPinOnOther, pairingFailed, cantBindPort, checksumMismatch
}

/// Localized string. Views pass an @AppStorage-tracked `lang` so they re-render on change.
func L(_ key: LKey, _ lang: Lang = .current) -> String {
    let ko = lang == .ko
    switch key {
    case .devices:            return ko ? "기기" : "Devices"
    case .transfers:          return ko ? "전송" : "Transfers"
    case .settings:           return ko ? "설정" : "Settings"
    case .add:                return ko ? "추가" : "Add"
    case .addDevice:          return ko ? "기기 추가" : "Add Device"
    case .send:               return ko ? "보내기" : "Send"
    case .rename:             return ko ? "이름 변경…" : "Rename…"
    case .remove:             return ko ? "삭제" : "Remove"
    case .noPairedDevices:    return ko ? "페어링된 기기 없음" : "No paired devices"
    case .noPairedDevicesHint:
        return ko ? "WireGuard IP로 기기를 추가하세요. 양쪽에서 PIN을 확인하면 페어링됩니다."
                  : "Add a device by its WireGuard IP. Both sides confirm a PIN to pair."
    case .noTransfers:        return ko ? "아직 전송 없음" : "No transfers yet"
    case .noTransfersHint:
        return ko ? "보내거나 받은 파일이 여기에 표시됩니다." : "Files you send or receive will appear here."
    case .clearFinished:      return ko ? "완료 항목 지우기" : "Clear Finished"
    case .thisDevice:         return ko ? "내 기기" : "This Device"
    case .name:               return ko ? "이름" : "Name"
    case .identifier:         return ko ? "식별자" : "Identifier"
    case .edit:               return ko ? "편집…" : "Edit…"
    case .receiving:          return ko ? "수신" : "Receiving"
    case .downloadFolder:     return ko ? "다운로드 폴더" : "Download folder"
    case .choose:             return ko ? "선택…" : "Choose…"
    case .listenPort:         return ko ? "수신 포트" : "Listen port"
    case .appearance:         return ko ? "모양" : "Appearance"
    case .showMenuBarIcon:    return ko ? "메뉴 막대 아이콘 표시" : "Show menu bar icon"
    case .showMenuBarIconHint:
        return ko ? "끄면 Dock에서 앱을 엽니다." : "When off, open the app from the Dock instead."
    case .language:           return ko ? "언어" : "Language"
    case .settingsFooter:
        return ko ? "파일은 보낸 기기 이름의 하위 폴더에 저장됩니다. 전송은 WireGuard 터널을 통해 직접 이뤄지며 중계 서버가 없습니다."
                  : "Files arrive in a subfolder named after the sending device. Transfers go directly over your WireGuard tunnel — no relay server."
    case .deviceNameTitle:    return ko ? "기기 이름" : "Device Name"
    case .deviceNameHint:
        return ko ? "페어링한 기기에 표시되며, 상대 쪽에서 하위 폴더 이름으로 쓰입니다."
                  : "Shown to devices you pair with, and used as your subfolder name on their side."
    case .deviceNamePlaceholder: return ko ? "기기 이름" : "Device name"
    case .cancel:             return ko ? "취소" : "Cancel"
    case .save:               return ko ? "저장" : "Save"
    case .pair:               return ko ? "페어링" : "Pair"
    case .addDeviceHint:
        return ko ? "기기의 WireGuard 주소를 입력하세요. 기본 포트는 %d입니다."
                  : "Enter the device's WireGuard address. The default port is %d."
    case .addDevicePlaceholder: return "10.0.0.2 : %d"
    case .renameTitle:        return ko ? "기기 이름 변경" : "Rename Device"
    case .renameHint:
        return ko ? "로컬 이름은 기기가 알리는 이름을 대체합니다. 파일이 이 폴더 이름으로 저장됩니다."
                  : "Local name overrides the name this device advertises. Files arrive under this folder name."
    case .wantsToPair:        return ko ? "%@이(가) 페어링을 요청합니다" : "%@ wants to pair"
    case .confirmPinMatch:
        return ko ? "이 PIN이 상대 기기에 표시된 것과 같은지 확인하세요."
                  : "Confirm this PIN matches the one on that device."
    case .decline:            return ko ? "거절" : "Decline"
    case .accept:             return ko ? "수락" : "Accept"
    case .pairingWith:        return ko ? "%@와(과) 페어링 중" : "Pairing with %@"
    case .close:              return ko ? "닫기" : "Close"
    case .listeningOnPort:    return ko ? "포트 %d에서 수신 대기 중" : "Listening on port %d"
    case .listenerOffline:    return ko ? "리스너 꺼짐" : "Listener offline"
    case .transferring:       return ko ? "%d개 전송 중…" : "%d transferring…"
    case .pairedDevices:      return ko ? "페어링된 기기 %d대" : "%d paired device(s)"
    case .openApp:            return ko ? "WGFileSender 열기" : "Open WGFileSender"
    case .quit:               return ko ? "종료" : "Quit"
    case .ready:              return ko ? "포트 %d에서 수신 대기 중" : "Ready to receive on port %d"
    case .from:               return ko ? "보낸 곳" : "from"
    case .to:                 return ko ? "받는 곳" : "to"
    case .failed:             return ko ? "실패" : "failed"
    case .confirmPinOnOther:
        return ko ? "상대 기기에서 PIN %@을(를) 확인한 뒤 수락하세요."
                  : "Confirm PIN %@ on the other device, then accept there."
    case .pairingFailed:      return ko ? "페어링 실패: %@" : "Pairing failed: %@"
    case .cantBindPort:       return ko ? "포트 %d 바인딩 실패: %@" : "Couldn't bind port %d: %@"
    case .checksumMismatch:   return ko ? "체크섬 불일치" : "checksum mismatch"
    }
}
