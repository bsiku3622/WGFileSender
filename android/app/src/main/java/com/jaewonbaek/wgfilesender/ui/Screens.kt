package com.jaewonbaek.wgfilesender.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaewonbaek.wgfilesender.data.AppController
import com.jaewonbaek.wgfilesender.model.DEFAULT_PORT
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Transfer
import com.jaewonbaek.wgfilesender.model.TransferDirection
import com.jaewonbaek.wgfilesender.model.TransferState
import com.jaewonbaek.wgfilesender.ui.components.BtnVariant
import com.jaewonbaek.wgfilesender.ui.components.ShadButton
import com.jaewonbaek.wgfilesender.ui.components.ShadCard
import com.jaewonbaek.wgfilesender.ui.components.ShadTabs
import com.jaewonbaek.wgfilesender.ui.components.ShadTextField
import com.jaewonbaek.wgfilesender.ui.theme.Shad
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun AppScreen(controller: AppController) {
    var tab by remember { mutableIntStateOf(0) }
    val pending by controller.pendingPairing.collectAsState()
    val outgoing by controller.outgoingPairing.collectAsState()

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
            ShadTabs(listOf("Devices", "Transfers", "Settings"), tab, { tab = it })
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

@Composable
private fun DevicesScreen(controller: AppController) {
    val peers by controller.peers.collectAsState()
    val shared by controller.sharedUris.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PeerDevice?>(null) }
    var pickerPeer by remember { mutableStateOf<PeerDevice?>(null) }

    // GetMultipleContents → ACTION_GET_CONTENT: shows an app chooser (Photos, Files,
    // Gallery, …) and supports selecting multiple items.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val peer = pickerPeer
        if (peer != null && uris.isNotEmpty()) controller.sendFiles(uris, peer)
        pickerPeer = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            ShadButton("Add", { showAdd = true }, icon = Icons.Rounded.Add)
        }

        if (shared.isNotEmpty()) {
            ShadCard(Modifier.padding(bottom = 12.dp)) {
                Text("${shared.size} file(s) ready to send",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("Tap a device below to send them.",
                    color = Shad.mutedForeground, fontSize = 13.sp)
            }
        }

        if (peers.isEmpty()) {
            EmptyState(Icons.Rounded.Smartphone, "No paired devices",
                "Add a device by its WireGuard IP. Both sides confirm a PIN to pair.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(peers, key = { it.peerId }) { peer ->
                    PeerCard(
                        peer = peer,
                        onSend = {
                            if (shared.isNotEmpty()) controller.sendFiles(shared, peer)
                            else { pickerPeer = peer; filePicker.launch("*/*") }
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
            ShadButton("Send", onSend, icon = Icons.Rounded.Send, variant = BtnVariant.Outline)
            Box {
                Icon(Icons.Rounded.MoreVert, null, tint = Shad.mutedForeground,
                    modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun TransfersScreen(controller: AppController) {
    val transfers by controller.transfers.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (transfers.any { it.state != TransferState.ACTIVE }) {
                ShadButton("Clear", { controller.clearFinished() }, variant = BtnVariant.Ghost)
            }
        }
        if (transfers.isEmpty()) {
            EmptyState(Icons.Rounded.Inbox, "No transfers yet",
                "Files you send or receive will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(transfers, key = { it.id }) { TransferCard(it) }
            }
        }
    }
}

@Composable
private fun TransferCard(t: Transfer) {
    ShadCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val incoming = t.direction == TransferDirection.INCOMING
            Icon(
                if (incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                null,
                tint = when (t.state) {
                    TransferState.ACTIVE -> Shad.accent
                    TransferState.COMPLETED -> Shad.success
                    TransferState.FAILED -> Shad.destructive
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle(t), color = Shad.mutedForeground, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.state == TransferState.ACTIVE) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { t.progress },
                        color = Shad.accent,
                        trackColor = Shad.muted,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            when (t.state) {
                TransferState.ACTIVE -> Text("${(t.progress * 100).toInt()}%",
                    color = Shad.mutedForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                TransferState.COMPLETED -> Icon(Icons.Rounded.CheckCircle, null,
                    tint = Shad.success, modifier = Modifier.size(20.dp))
                TransferState.FAILED -> Icon(Icons.Rounded.Warning, null,
                    tint = Shad.destructive, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun subtitle(t: Transfer): String {
    val dir = if (t.direction == TransferDirection.INCOMING) "from" else "to"
    return when (t.state) {
        TransferState.ACTIVE -> "$dir ${t.peerName} · ${t.transferredBytes.humanBytes()} / ${t.totalBytes.humanBytes()}"
        TransferState.COMPLETED -> "$dir ${t.peerName} · ${t.totalBytes.humanBytes()}"
        TransferState.FAILED -> "$dir ${t.peerName} · ${t.error ?: "failed"}"
    }
}

@Composable
private fun SettingsScreen(controller: AppController) {
    val identity by controller.identity.collectAsState()
    val settings by controller.settings.collectAsState()
    val running by controller.listenerRunning.collectAsState()
    val error by controller.listenerError.collectAsState()

    var name by remember(identity.deviceName) { mutableStateOf(identity.deviceName) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { controller.setDownloadTree(it) } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ShadCard {
                StatusRow(running, error, settings.port)
            }
        }
        item {
            ShadCard {
                Text("This Device", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text("Name", color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ShadTextField(name, { name = it }, "Device name")
                Spacer(Modifier.height(10.dp))
                ShadButton("Save name", { controller.setDeviceName(name) }, variant = BtnVariant.Outline)
            }
        }
        item {
            ShadCard {
                Text("Receiving", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text("Download folder", color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(settings.downloadTreeUri ?: "Not set — required to receive files",
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (settings.downloadTreeUri == null) Shad.destructive else Shad.foreground)
                Spacer(Modifier.height(8.dp))
                ShadButton("Choose folder", { treePicker.launch(null) },
                    icon = Icons.Rounded.Folder, variant = BtnVariant.Outline)
                Spacer(Modifier.height(16.dp))
                Text("Listen port", color = Shad.mutedForeground, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShadTextField(port, { port = it }, "$DEFAULT_PORT",
                        modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    Spacer(Modifier.width(10.dp))
                    ShadButton("Apply", {
                        port.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { controller.setPort(it) }
                    }, variant = BtnVariant.Outline)
                }
            }
        }
        item {
            Text("Files arrive in a subfolder named after the sending device. Transfers go directly over your WireGuard tunnel — no relay server.",
                color = Shad.mutedForeground, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun StatusRow(running: Boolean, error: String?, port: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape)
            .background(if (running && error == null) Shad.success else Shad.destructive))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (running && error == null) "Listening" else "Listener offline",
                fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(error ?: "Ready to receive on port $port",
                color = if (error != null) Shad.destructive else Shad.mutedForeground, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
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

fun Long.humanBytes(): String {
    if (this < 1024) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = this.toDouble(); var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
