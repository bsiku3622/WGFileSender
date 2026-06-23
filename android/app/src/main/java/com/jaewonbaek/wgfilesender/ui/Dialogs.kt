package com.jaewonbaek.wgfilesender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jaewonbaek.wgfilesender.data.AppController
import com.jaewonbaek.wgfilesender.model.DEFAULT_PORT
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.ui.components.BtnVariant
import com.jaewonbaek.wgfilesender.ui.components.ShadButton
import com.jaewonbaek.wgfilesender.ui.components.ShadCard
import com.jaewonbaek.wgfilesender.ui.components.ShadTextField
import com.jaewonbaek.wgfilesender.ui.theme.Shad
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun IncomingPairDialog(controller: AppController, pending: AppController.PendingPairing) {
    Dialog(onDismissRequest = { controller.declineIncomingPair() }) {
        ShadCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Key, null, tint = Shad.accent, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("${pending.body.deviceName} wants to pair",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Confirm this PIN matches the one on that device.",
                    color = Shad.mutedForeground, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(AppController.pretty(pending.body.pin),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShadButton("Decline", { controller.declineIncomingPair() },
                        Modifier.weight(1f), BtnVariant.Outline)
                    ShadButton("Accept", { controller.acceptIncomingPair() }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun OutgoingPairDialog(controller: AppController, outgoing: AppController.OutgoingPairing) {
    Dialog(onDismissRequest = { if (outgoing.failed) controller.dismissOutgoingPair() }) {
        ShadCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (outgoing.failed) {
                    Icon(Icons.Rounded.Error, null, tint = Shad.destructive, modifier = Modifier.size(36.dp))
                } else {
                    CircularProgressIndicator(color = Shad.accent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Pairing with ${outgoing.address}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(AppController.pretty(outgoing.pin),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(12.dp))
                Text(outgoing.status, color = Shad.mutedForeground, fontSize = 13.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                ShadButton(if (outgoing.failed) "Close" else "Cancel",
                    { controller.dismissOutgoingPair() }, Modifier.fillMaxWidth(), BtnVariant.Outline)
            }
        }
    }
}

@Composable
fun AddDeviceDialog(controller: AppController, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        ShadCard {
            Text("Add Device", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Enter the device's WireGuard address. Default port is $DEFAULT_PORT.",
                color = Shad.mutedForeground, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            ShadTextField(address, { address = it }, "10.0.0.2 or 10.0.0.2:$DEFAULT_PORT")
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadButton("Cancel", onDismiss, Modifier.weight(1f), BtnVariant.Outline)
                ShadButton("Pair", {
                    val normalized = address.trim().let { if (it.contains(":")) it else "$it:$DEFAULT_PORT" }
                    controller.startOutgoingPair(normalized)
                    onDismiss()
                }, Modifier.weight(1f), enabled = address.trim().isNotEmpty())
            }
        }
    }
}

@Composable
fun RenameDialog(controller: AppController, peer: PeerDevice, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(peer.localName ?: peer.peerName) }
    Dialog(onDismissRequest = onDismiss) {
        ShadCard {
            Text("Rename Device", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Local name overrides what this device advertises. Files arrive under this folder name.",
                color = Shad.mutedForeground, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            ShadTextField(name, { name = it }, peer.peerName)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadButton("Cancel", onDismiss, Modifier.weight(1f), BtnVariant.Outline)
                ShadButton("Save", { controller.renamePeer(peer, name); onDismiss() }, Modifier.weight(1f))
            }
        }
    }
}
