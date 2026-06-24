package com.jaewonbaek.wgfilesender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
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

@Composable
fun IncomingPairDialog(controller: AppController, pending: AppController.PendingPairing) {
    Dialog(onDismissRequest = { controller.declineIncomingPair() }) {
        ShadCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Key, null, tint = Shad.accent, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text(String.format(t(S.wantsToPair), pending.body.deviceName),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(t(S.confirmPinMatch),
                    color = Shad.mutedForeground, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(AppController.pretty(pending.body.pin),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShadButton(t(S.decline), { controller.declineIncomingPair() },
                        Modifier.weight(1f), BtnVariant.Outline)
                    ShadButton(t(S.accept), { controller.acceptIncomingPair() }, Modifier.weight(1f))
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
                Text(String.format(t(S.pairingWith), outgoing.address),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(AppController.pretty(outgoing.pin),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(12.dp))
                Text(outgoing.status, color = Shad.mutedForeground, fontSize = 13.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                ShadButton(if (outgoing.failed) t(S.close) else t(S.cancel),
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
            Text(t(S.addDeviceTitle), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(String.format(t(S.addDeviceHint), DEFAULT_PORT),
                color = Shad.mutedForeground, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            ShadTextField(address, { address = it }, String.format(t(S.addDevicePlaceholder), DEFAULT_PORT))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadButton(t(S.cancel), onDismiss, Modifier.weight(1f), BtnVariant.Outline)
                ShadButton(t(S.pair), {
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
            Text(t(S.renameTitle), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(t(S.renameHint), color = Shad.mutedForeground, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            ShadTextField(name, { name = it }, peer.peerName)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadButton(t(S.cancel), onDismiss, Modifier.weight(1f), BtnVariant.Outline)
                ShadButton(t(S.save), { controller.renamePeer(peer, name); onDismiss() }, Modifier.weight(1f))
            }
        }
    }
}
