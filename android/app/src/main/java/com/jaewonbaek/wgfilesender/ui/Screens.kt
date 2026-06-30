package com.jaewonbaek.wgfilesender.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jaewonbaek.wgfilesender.data.AppController
import com.jaewonbaek.wgfilesender.data.NetInfo
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Transfer
import com.jaewonbaek.wgfilesender.model.TransferDirection
import com.jaewonbaek.wgfilesender.model.TransferState
import com.jaewonbaek.wgfilesender.net.UpdateState
import com.jaewonbaek.wgfilesender.ui.components.BtnVariant
import com.jaewonbaek.wgfilesender.ui.components.ShadButton
import com.jaewonbaek.wgfilesender.ui.components.ShadCard
import com.jaewonbaek.wgfilesender.ui.components.ShadTabs
import com.jaewonbaek.wgfilesender.ui.components.ShadTextField
import com.jaewonbaek.wgfilesender.ui.theme.Shad
import kotlin.math.roundToInt

@Composable
fun AppScreen(controller: AppController) {
    val lang by controller.language.collectAsState()
    val pending by controller.pendingPairing.collectAsState()
    val outgoing by controller.outgoingPairing.collectAsState()
    val tab by controller.selectedTab.collectAsState()

    CompositionLocalProvider(LocalLang provides lang) {
        Surface(color = Shad.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Text("WGFileSender",
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                ShadTabs(listOf(t(S.devices), t(S.transfers), t(S.settings)), tab, { controller.setTab(it) })
                Spacer(Modifier.height(14.dp))
                when (tab) {
                    0 -> DevicesScreen(controller)
                    1 -> TransfersScreen(controller)
                    else -> SettingsScreen(controller)
                }
            }
        }

        pending?.let { IncomingPairDialog(controller, it) }
        outgoing?.let { OutgoingPairDialog(controller, it) }
    }
}

@Composable
private fun DevicesScreen(controller: AppController) {
    val peers by controller.peers.collectAsState()
    val shared by controller.sharedUris.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PeerDevice?>(null) }
    var pickerPeer by remember { mutableStateOf<PeerDevice?>(null) }

    // Explicit chooser so an app-picker (Photos, Gallery, Files, …) always appears;
    // EXTRA_ALLOW_MULTIPLE keeps multi-select.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val peer = pickerPeer
        if (peer != null && result.resultCode == Activity.RESULT_OK) {
            val uris = extractUris(result.data)
            if (uris.isNotEmpty()) controller.sendFiles(uris, peer)
        }
        pickerPeer = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t(S.devices), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            ShadButton(t(S.add), { showAdd = true }, icon = Icons.Rounded.Add)
        }

        if (shared.isNotEmpty()) {
            ShadCard(Modifier.padding(bottom = 12.dp)) {
                Text(String.format(t(S.filesReadyToSend), shared.size),
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(t(S.tapDeviceToSend), color = Shad.mutedForeground, fontSize = 13.sp)
            }
        }

        if (peers.isEmpty()) {
            EmptyState(Icons.Rounded.Smartphone, t(S.noPairedDevices), t(S.noPairedDevicesHint))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(peers, key = { it.peerId }) { peer ->
                    PeerCard(
                        peer = peer,
                        onSend = {
                            if (shared.isNotEmpty()) controller.sendFiles(shared, peer)
                            else {
                                pickerPeer = peer
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                                filePicker.launch(Intent.createChooser(intent, null))
                            }
                        },
                        onRename = { renaming = peer },
                        onRemove = { controller.removePeer(peer) }
                    )
                }
            }
        }
    }

    if (showAdd) AddDeviceDialog(controller) { showAdd = false }
    renaming?.let { peer -> RenameDialog(controller, peer) { renaming = null } }
}

@Composable
private fun PeerCard(peer: PeerDevice, onSend: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ShadCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(Shad.radiusMd)).background(Shad.muted),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Smartphone, null, tint = Shad.foreground, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(peer.peerAddress, color = Shad.mutedForeground, fontSize = 12.sp)
            }
            ShadButton(t(S.send), onSend, icon = Icons.Rounded.Send, variant = BtnVariant.Outline)
            Box {
                Icon(Icons.Rounded.MoreVert, null, tint = Shad.mutedForeground,
                    modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(t(S.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(t(S.remove)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun TransfersScreen(controller: AppController) {
    val transfers by controller.transfers.collectAsState()
    val rates by controller.transferRates.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t(S.transfers), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            ShadButton(t(S.openFolder), { controller.openDownloadFolder() },
                icon = Icons.Rounded.Folder, variant = BtnVariant.Ghost)
            if (transfers.any { it.state == TransferState.COMPLETED || it.state == TransferState.FAILED }) {
                ShadButton(t(S.clear), { controller.clearFinished() }, variant = BtnVariant.Ghost)
            }
        }
        if (transfers.isNotEmpty()) {
            TransfersSummary(transfers)
            Spacer(Modifier.height(10.dp))
        }
        if (transfers.isEmpty()) {
            EmptyState(Icons.Rounded.Inbox, t(S.noTransfers), t(S.noTransfersHint))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(transfers, key = { it.id }) { TransferCard(controller, it, rates[it.id]) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransfersSummary(transfers: List<Transfer>) {
    val total = transfers.size
    val sending = transfers.count { it.state == TransferState.ACTIVE && it.direction == TransferDirection.OUTGOING }
    val receiving = transfers.count { it.state == TransferState.ACTIVE && it.direction == TransferDirection.INCOMING }
    val waiting = transfers.count { it.state == TransferState.PENDING || it.state == TransferState.QUEUED }
    val done = transfers.filter { it.state == TransferState.COMPLETED }
    val failed = transfers.count { it.state == TransferState.FAILED }
    val stopped = transfers.count { it.state == TransferState.INTERRUPTED }
    val doneBytes = done.sumOf { it.totalBytes }

    ShadCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(S.transferProgress), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            Text("${done.size} / $total", fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, color = Shad.mutedForeground)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.size.toFloat() / total else 0f },
            color = Shad.received, trackColor = Shad.muted,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sending > 0) StatChip(t(S.sending), sending, Shad.sent)
            if (receiving > 0) StatChip(t(S.receivingNow), receiving, Shad.received)
            if (waiting > 0) StatChip(t(S.queued), waiting, Shad.mutedForeground)
            if (done.isNotEmpty()) StatChip(t(S.statDone), done.size, Shad.received, doneBytes.humanBytes())
            if (failed > 0) StatChip(t(S.failed), failed, Shad.destructive)
            if (stopped > 0) StatChip(t(S.interrupted), stopped, Shad.sent)
        }
    }
}

/** Tiny markdown renderer for release notes: headers, bullets, and bold inline. */
@Composable
private fun MarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.split("\n").forEach { line ->
            when {
                line.startsWith("## ") ->
                    Text(inlineMd(line.drop(3)), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                line.startsWith("# ") ->
                    Text(inlineMd(line.drop(2)), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                line.startsWith("- ") || line.startsWith("* ") ->
                    Row {
                        Text("•  ", fontSize = 12.sp, color = Shad.mutedForeground)
                        Text(inlineMd(line.drop(2)), fontSize = 12.sp)
                    }
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(inlineMd(line), fontSize = 12.sp)
            }
        }
    }
}

private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex("""\*\*(.+?)\*\*""")
    var last = 0
    for (m in regex.findAll(s)) {
        append(s.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}

@Composable
private fun StatChip(label: String, count: Int, color: Color, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("$count", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = Shad.mutedForeground, fontSize = 12.sp)
        if (detail != null) Text("· $detail", color = Shad.mutedForeground, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferCard(controller: AppController, transfer: Transfer, rate: Double?) {
    val lang = LocalLang.current
    val incoming = transfer.direction == TransferDirection.INCOMING
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(transfer.fileName) }

    ShadCard(Modifier.combinedClickable(
        onClick = { controller.openTransfer(transfer) },
        onLongClick = { menu = true }
    )) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = when {
                transfer.state == TransferState.FAILED -> Shad.destructive
                transfer.state == TransferState.INTERRUPTED -> Shad.sent   // amber-ish, "paused"
                transfer.state == TransferState.QUEUED || transfer.state == TransferState.PENDING -> Shad.mutedForeground
                incoming -> Shad.received   // green
                else -> Shad.sent           // orange
            }
            Icon(
                when {
                    transfer.state == TransferState.INTERRUPTED -> Icons.Rounded.Warning
                    incoming -> Icons.Rounded.ArrowDownward
                    else -> Icons.Rounded.ArrowUpward
                },
                null, tint = tint, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(transfer.fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle(transfer, lang, rate), color = Shad.mutedForeground, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (transfer.state == TransferState.ACTIVE || transfer.state == TransferState.INTERRUPTED) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { transfer.progress },
                        color = if (transfer.state == TransferState.INTERRUPTED) Shad.sent else Shad.accent,
                        trackColor = Shad.muted,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (transfer.state == TransferState.ACTIVE || transfer.state == TransferState.QUEUED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (transfer.state == TransferState.ACTIVE) {
                        Text("${(transfer.progress * 100).toInt()}%",
                            color = Shad.mutedForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(Icons.Rounded.Close, null, tint = Shad.mutedForeground,
                        modifier = Modifier.size(20.dp).clip(CircleShape)
                            .clickable { controller.cancelTransfer(transfer) })
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!incoming &&
                        (transfer.state == TransferState.INTERRUPTED || transfer.state == TransferState.FAILED) &&
                        transfer.localPath != null) {
                        Icon(Icons.Rounded.Refresh, null, tint = Shad.sent,
                            modifier = Modifier.size(22.dp).clip(CircleShape)
                                .clickable { controller.resumeTransfer(transfer) })
                        Spacer(Modifier.width(4.dp))
                    }
                    Box {
                        Icon(Icons.Rounded.MoreVert, null, tint = Shad.mutedForeground,
                            modifier = Modifier.size(24.dp).clip(CircleShape).clickable { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(t(S.open)) },
                                onClick = { menu = false; controller.openTransfer(transfer) })
                            if (!incoming &&
                                (transfer.state == TransferState.FAILED || transfer.state == TransferState.INTERRUPTED)) {
                                DropdownMenuItem(text = { Text(t(S.resume)) },
                                    onClick = { menu = false; controller.resumeTransfer(transfer) })
                            }
                            if (incoming) {
                                DropdownMenuItem(text = { Text(t(S.renameFile)) },
                                    onClick = { menu = false; newName = transfer.fileName; renaming = true })
                                DropdownMenuItem(text = { Text(t(S.delete)) },
                                    onClick = { menu = false; controller.deleteTransfer(transfer) })
                            } else if (transfer.state == TransferState.ACTIVE || transfer.state == TransferState.QUEUED) {
                                DropdownMenuItem(text = { Text(t(S.cancel)) },
                                    onClick = { menu = false; controller.cancelTransfer(transfer) })
                            } else {
                                DropdownMenuItem(text = { Text(t(S.removeFromList)) },
                                    onClick = { menu = false; controller.removeTransfer(transfer) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        Dialog(onDismissRequest = { renaming = false }) {
            ShadCard {
                Text(t(S.renameFile), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                ShadTextField(newName, { newName = it }, transfer.fileName)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShadButton(t(S.cancel), { renaming = false }, Modifier.weight(1f), BtnVariant.Outline)
                    ShadButton(t(S.save), {
                        controller.renameTransfer(transfer, newName); renaming = false
                    }, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun subtitle(transfer: Transfer, lang: Lang, rate: Double? = null): String {
    val dir = if (transfer.direction == TransferDirection.INCOMING) str(S.from, lang) else str(S.to, lang)
    return when (transfer.state) {
        TransferState.PENDING, TransferState.QUEUED -> "$dir ${transfer.peerName} · ${str(S.queued, lang)}"
        TransferState.ACTIVE -> {
            val e = transfer.error
            if (!e.isNullOrEmpty()) return "$dir ${transfer.peerName} · $e"   // reconnecting between retries
            var s = "$dir ${transfer.peerName} · ${transfer.transferredBytes.humanBytes()} / ${transfer.totalBytes.humanBytes()}"
            if (rate != null && rate > 1) {
                s += " · ${rate.toLong().humanBytes()}/s"
                val eta = etaString((transfer.totalBytes - transfer.transferredBytes) / rate)
                if (eta.isNotEmpty()) s += " · $eta ${str(S.remaining, lang)}"
            }
            s
        }
        TransferState.COMPLETED -> "$dir ${transfer.peerName} · ${transfer.totalBytes.humanBytes()}"
        TransferState.INTERRUPTED ->
            "$dir ${transfer.peerName} · ${str(S.interrupted, lang)} · ${(transfer.progress * 100).toInt()}%"
        TransferState.FAILED -> "$dir ${transfer.peerName} · ${transfer.error ?: str(S.failed, lang)}"
    }
}

@Composable
private fun SettingsScreen(controller: AppController) {
    val identity by controller.identity.collectAsState()
    val settings by controller.settings.collectAsState()
    val running by controller.listenerRunning.collectAsState()
    val error by controller.listenerError.collectAsState()
    val lang = LocalLang.current
    val clipboard = LocalClipboardManager.current

    var name by remember(identity.deviceName) { mutableStateOf(identity.deviceName) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { controller.setDownloadTree(it) } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ShadCard { StatusRow(running, error, settings.port) }
        }
        item {
            ShadCard {
                Text(t(S.thisDevice), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(t(S.nameLabel), color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ShadTextField(name, { name = it }, t(S.deviceNamePlaceholder))
                Spacer(Modifier.height(10.dp))
                ShadButton(t(S.saveName), { controller.setDeviceName(name) }, variant = BtnVariant.Outline)
                val myAddr = remember { NetInfo.tunnelIPv4Addresses().firstOrNull() }
                if (myAddr != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(t(S.myAddress), color = Shad.mutedForeground, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$myAddr:${settings.port}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        ShadButton(t(S.copy), {
                            clipboard.setText(AnnotatedString("$myAddr:${settings.port}"))
                        }, variant = BtnVariant.Outline)
                    }
                }
            }
        }
        item {
            ShadCard {
                Text(t(S.receiving), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(t(S.downloadFolder), color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(settings.downloadTreeUri ?: t(S.folderNotSet),
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (settings.downloadTreeUri == null) Shad.destructive else Shad.foreground)
                Spacer(Modifier.height(8.dp))
                ShadButton(t(S.chooseFolder), { treePicker.launch(null) },
                    icon = Icons.Rounded.Folder, variant = BtnVariant.Outline)
                Spacer(Modifier.height(16.dp))
                Text(t(S.listenPort), color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShadTextField(port, { port = it }, "$DEFAULT_PORT_TEXT",
                        modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    Spacer(Modifier.width(10.dp))
                    ShadButton(t(S.apply), {
                        port.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { controller.setPort(it) }
                    }, variant = BtnVariant.Outline)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t(S.backgroundReceive), fontSize = 14.sp)
                        Text(t(S.backgroundReceiveHint), color = Shad.mutedForeground, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = settings.backgroundReceive,
                        onCheckedChange = { controller.setBackgroundReceive(it) })
                }
            }
        }
        item {
            ShadCard {
                Text(t(S.language), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                ShadTabs(
                    Lang.entries.map { it.label },
                    lang.ordinal,
                    { controller.setLanguage(Lang.entries[it]) }
                )
            }
        }
        item { UpdateCard(controller) }
        item {
            Text(t(S.settingsFooter),
                color = Shad.mutedForeground, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun UpdateCard(controller: AppController) {
    val state by controller.updateState.collectAsState()
    ShadCard {
        Text(t(S.updates), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(S.currentVersion), color = Shad.mutedForeground, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            Text("v${controller.appVersion}", fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is UpdateState.Idle ->
                ShadButton(t(S.checkForUpdates), { controller.checkForUpdates(true) }, variant = BtnVariant.Outline)
            is UpdateState.Checking ->
                Text(t(S.checkingForUpdates), color = Shad.mutedForeground, fontSize = 13.sp)
            is UpdateState.UpToDate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t(S.upToDate), color = Shad.mutedForeground, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    ShadButton(t(S.checkForUpdates), { controller.checkForUpdates(true) }, variant = BtnVariant.Outline)
                }
            is UpdateState.Available ->
                Column {
                    Text(String.format(t(S.updateAvailable), s.info.version),
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (s.info.releaseNotes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(t(S.whatsNew), color = Shad.mutedForeground, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        MarkdownText(s.info.releaseNotes)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShadButton(t(S.downloadUpdate), { controller.downloadUpdate() })
                        ShadButton(t(S.later), { controller.dismissUpdate() }, variant = BtnVariant.Ghost)
                    }
                }
            is UpdateState.Downloading ->
                Column {
                    LinearProgressIndicator(progress = { s.progress }, color = Shad.accent,
                        trackColor = Shad.muted,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    Spacer(Modifier.height(6.dp))
                    Text("${t(S.downloadingUpdate)} ${(s.progress * 100).toInt()}%",
                        color = Shad.mutedForeground, fontSize = 12.sp)
                }
            is UpdateState.Downloaded ->
                Column {
                    Text(t(S.updateDownloadedHint), color = Shad.mutedForeground, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    ShadButton(t(S.installUpdate), { controller.installUpdate() })
                }
            is UpdateState.Failed ->
                Column {
                    Text(String.format(t(S.updateCheckFailed), s.message),
                        color = Shad.destructive, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    ShadButton(t(S.retry), { controller.checkForUpdates(true) }, variant = BtnVariant.Outline)
                }
        }
    }
}

@Composable
private fun StatusRow(running: Boolean, error: String?, port: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape)
            .background(if (running && error == null) Shad.received else Shad.destructive))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (running && error == null) t(S.listening) else t(S.listenerOffline),
                fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(error ?: String.format(t(S.readyOnPort), port),
                color = if (error != null) Shad.destructive else Shad.mutedForeground, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Shad.mutedForeground, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Shad.mutedForeground, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp))
    }
}

/** Pulls selected uris from a chooser result (multiple via clipData, single via data). */
private fun extractUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val out = mutableListOf<Uri>()
    data.clipData?.let { clip -> for (i in 0 until clip.itemCount) out.add(clip.getItemAt(i).uri) }
    if (out.isEmpty()) data.data?.let { out.add(it) }
    return out
}

private const val DEFAULT_PORT_TEXT = "51900"

fun Long.humanBytes(): String {
    if (this < 1024) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = this.toDouble(); var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

/** Compact remaining-time label: "15s", "2m 10s", "1h 4m". Empty when not meaningful. */
fun etaString(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0 || seconds >= 86_400) return ""
    val s = seconds.roundToInt()
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s / 60 % 60}m"
    }
}
