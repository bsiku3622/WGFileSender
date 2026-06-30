package com.jaewonbaek.wgfilesender.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** App language, chosen in Settings (not tied to the system locale). */
enum class Lang(val label: String) {
    EN("English"), KO("한국어");

    companion object {
        fun from(s: String?): Lang = entries.firstOrNull { it.name == s } ?: systemDefault()
        fun systemDefault(): Lang =
            if (Locale.getDefault().language == "ko") KO else EN
    }
}

enum class S {
    devices, transfers, settings, add, send, rename, remove,
    noPairedDevices, noPairedDevicesHint, noTransfers, noTransfersHint, clear,
    filesReadyToSend, tapDeviceToSend,
    listening, listenerOffline, readyOnPort,
    thisDevice, nameLabel, deviceNamePlaceholder, saveName,
    receiving, downloadFolder, folderNotSet, chooseFolder, listenPort, apply,
    settingsFooter, language,
    addDeviceTitle, addDeviceHint, addDevicePlaceholder, pair, cancel,
    renameTitle, renameHint, save,
    wantsToPair, confirmPinMatch, decline, accept,
    pairingWith, close, from, to, failed,
    confirmPinOnOther, pairingFailed, checksumMismatch, noDownloadFolder,
    channelName, channelDesc, notifReady,
    open, delete, renameFile, openFolder, resend, canceled,
    resume, interrupted, connectionLost, retrying, remaining, queued, removeFromList,
    statActive, statDone,
    updates, currentVersion, checkForUpdates, checkingForUpdates, upToDate,
    updateAvailable, whatsNew, downloadUpdate, downloadingUpdate, installUpdate,
    updateDownloadedHint, updateCheckFailed, retry, later,
    notifTransferring, backgroundReceive, backgroundReceiveHint,
    myAddress, copy, transferProgress
}

val LocalLang = staticCompositionLocalOf { Lang.EN }

@Composable
fun t(key: S): String = str(key, LocalLang.current)

@Suppress("CyclomaticComplexMethod")
fun str(key: S, lang: Lang): String {
    val ko = lang == Lang.KO
    return when (key) {
        S.devices -> if (ko) "기기" else "Devices"
        S.transfers -> if (ko) "전송" else "Transfers"
        S.settings -> if (ko) "설정" else "Settings"
        S.add -> if (ko) "추가" else "Add"
        S.send -> if (ko) "보내기" else "Send"
        S.rename -> if (ko) "이름 변경" else "Rename"
        S.remove -> if (ko) "삭제" else "Remove"
        S.noPairedDevices -> if (ko) "페어링된 기기 없음" else "No paired devices"
        S.noPairedDevicesHint -> if (ko)
            "WireGuard IP로 기기를 추가하세요. 양쪽에서 PIN을 확인하면 페어링됩니다."
        else "Add a device by its WireGuard IP. Both sides confirm a PIN to pair."
        S.noTransfers -> if (ko) "아직 전송 없음" else "No transfers yet"
        S.noTransfersHint -> if (ko) "보내거나 받은 파일이 여기에 표시됩니다."
        else "Files you send or receive will appear here."
        S.clear -> if (ko) "지우기" else "Clear"
        S.filesReadyToSend -> if (ko) "보낼 파일 %d개" else "%d file(s) ready to send"
        S.tapDeviceToSend -> if (ko) "아래에서 기기를 눌러 보내세요." else "Tap a device below to send them."
        S.listening -> if (ko) "수신 대기 중" else "Listening"
        S.listenerOffline -> if (ko) "리스너 꺼짐" else "Listener offline"
        S.readyOnPort -> if (ko) "포트 %d에서 수신 대기 중" else "Ready to receive on port %d"
        S.thisDevice -> if (ko) "내 기기" else "This Device"
        S.nameLabel -> if (ko) "이름" else "Name"
        S.deviceNamePlaceholder -> if (ko) "기기 이름" else "Device name"
        S.saveName -> if (ko) "이름 저장" else "Save name"
        S.receiving -> if (ko) "수신" else "Receiving"
        S.downloadFolder -> if (ko) "다운로드 폴더" else "Download folder"
        S.folderNotSet -> if (ko) "미설정 — 파일을 받으려면 필요합니다" else "Not set — required to receive files"
        S.chooseFolder -> if (ko) "폴더 선택" else "Choose folder"
        S.listenPort -> if (ko) "수신 포트" else "Listen port"
        S.apply -> if (ko) "적용" else "Apply"
        S.settingsFooter -> if (ko)
            "파일은 보낸 기기 이름의 하위 폴더에 저장됩니다. 전송은 WireGuard 터널을 통해 직접 이뤄지며 중계 서버가 없습니다."
        else "Files arrive in a subfolder named after the sending device. Transfers go directly over your WireGuard tunnel — no relay server."
        S.language -> if (ko) "언어" else "Language"
        S.addDeviceTitle -> if (ko) "기기 추가" else "Add Device"
        S.addDeviceHint -> if (ko) "기기의 WireGuard 주소를 입력하세요. 기본 포트는 %d입니다."
        else "Enter the device's WireGuard address. Default port is %d."
        S.addDevicePlaceholder -> "10.0.0.2 : %d"
        S.pair -> if (ko) "페어링" else "Pair"
        S.cancel -> if (ko) "취소" else "Cancel"
        S.renameTitle -> if (ko) "기기 이름 변경" else "Rename Device"
        S.renameHint -> if (ko)
            "로컬 이름은 기기가 알리는 이름을 대체합니다. 파일이 이 폴더 이름으로 저장됩니다."
        else "Local name overrides what this device advertises. Files arrive under this folder name."
        S.save -> if (ko) "저장" else "Save"
        S.wantsToPair -> if (ko) "%s이(가) 페어링을 요청합니다" else "%s wants to pair"
        S.confirmPinMatch -> if (ko) "이 PIN이 상대 기기와 같은지 확인하세요."
        else "Confirm this PIN matches the one on that device."
        S.decline -> if (ko) "거절" else "Decline"
        S.accept -> if (ko) "수락" else "Accept"
        S.pairingWith -> if (ko) "%s와(과) 페어링 중" else "Pairing with %s"
        S.close -> if (ko) "닫기" else "Close"
        S.from -> if (ko) "보낸 곳" else "from"
        S.to -> if (ko) "받는 곳" else "to"
        S.failed -> if (ko) "실패" else "failed"
        S.confirmPinOnOther -> if (ko) "상대 기기에서 PIN %s을(를) 확인한 뒤 수락하세요."
        else "Confirm PIN %s on the other device, then accept there."
        S.pairingFailed -> if (ko) "페어링 실패: %s" else "Pairing failed: %s"
        S.checksumMismatch -> if (ko) "체크섬 불일치" else "checksum mismatch"
        S.noDownloadFolder -> if (ko) "다운로드 폴더가 설정되지 않음" else "no download folder set"
        S.channelName -> if (ko) "수신" else "Receiving"
        S.channelDesc -> if (ko) "WGFileSender가 파일을 받을 수 있게 유지합니다"
        else "Keeps WGFileSender ready to receive files"
        S.notifReady -> if (ko) "포트 %d에서 수신 대기 중" else "Ready to receive on port %d"
        S.notifTransferring -> if (ko) "%d개 전송 중 · %d%%" else "Sending %d · %d%%"
        S.backgroundReceive -> if (ko) "백그라운드 수신" else "Background receive"
        S.backgroundReceiveHint -> if (ko) "끄면 알림이 사라지고, 켜질 때까지 파일을 받지 않습니다."
        else "When off, the notification goes away and files aren't received until you turn it back on."
        S.myAddress -> if (ko) "내 주소" else "My address"
        S.copy -> if (ko) "복사" else "Copy"
        S.transferProgress -> if (ko) "전송 진행" else "Progress"
        S.open -> if (ko) "열기" else "Open"
        S.delete -> if (ko) "삭제" else "Delete"
        S.renameFile -> if (ko) "파일 이름 변경" else "Rename File"
        S.openFolder -> if (ko) "폴더 열기" else "Open Folder"
        S.resend -> if (ko) "재전송" else "Resend"
        S.canceled -> if (ko) "취소됨" else "Canceled"
        S.resume -> if (ko) "이어받기" else "Resume"
        S.interrupted -> if (ko) "중단됨" else "Interrupted"
        S.connectionLost -> if (ko) "연결 끊김" else "Connection lost"
        S.retrying -> if (ko) "다시 연결 중…" else "Reconnecting…"
        S.remaining -> if (ko) "남음" else "left"
        S.queued -> if (ko) "대기 중" else "Queued"
        S.removeFromList -> if (ko) "목록에서 제거" else "Remove from List"
        S.statActive -> if (ko) "전송 중" else "Active"
        S.statDone -> if (ko) "완료" else "Done"
        S.updates -> if (ko) "업데이트" else "Updates"
        S.currentVersion -> if (ko) "현재 버전" else "Current version"
        S.checkForUpdates -> if (ko) "업데이트 확인" else "Check for Updates"
        S.checkingForUpdates -> if (ko) "확인 중…" else "Checking…"
        S.upToDate -> if (ko) "최신 버전입니다" else "You're up to date"
        S.updateAvailable -> if (ko) "새 버전 %s 사용 가능" else "Version %s available"
        S.whatsNew -> if (ko) "새로운 점" else "What's new"
        S.downloadUpdate -> if (ko) "다운로드" else "Download"
        S.downloadingUpdate -> if (ko) "다운로드 중…" else "Downloading…"
        S.installUpdate -> if (ko) "설치" else "Install"
        S.updateDownloadedHint -> if (ko) "다운로드 완료 — 설치를 진행하세요." else "Downloaded — tap install to continue."
        S.updateCheckFailed -> if (ko) "확인 실패: %s" else "Check failed: %s"
        S.retry -> if (ko) "다시 시도" else "Retry"
        S.later -> if (ko) "나중에" else "Later"
    }
}
