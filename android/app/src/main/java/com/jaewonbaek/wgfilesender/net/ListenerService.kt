package com.jaewonbaek.wgfilesender.net

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jaewonbaek.wgfilesender.MainActivity
import com.jaewonbaek.wgfilesender.R
import com.jaewonbaek.wgfilesender.WgfsApp
import com.jaewonbaek.wgfilesender.controller
import com.jaewonbaek.wgfilesender.model.TransferState
import com.jaewonbaek.wgfilesender.ui.S
import com.jaewonbaek.wgfilesender.ui.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Keeps the HTTP listener alive in the background via a foreground notification. */
class ListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifyJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        controller.startListener()
        // Reflect active transfers in the notification, throttled so progress updates
        // don't spam the notification manager.
        notifyJob?.cancel()
        notifyJob = scope.launch {
            controller.transfers.sample(700).collect {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        controller.stopListener()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val lang = controller.language.value
        val active = controller.transfers.value.filter { it.state == TransferState.ACTIVE }
        val text = if (active.isEmpty()) {
            String.format(str(S.notifReady, lang), controller.settings.value.port)
        } else {
            val sent = active.sumOf { it.transferredBytes }
            val total = active.sumOf { it.totalBytes }
            val pct = if (total > 0) (sent * 100 / total).toInt() else 0
            String.format(str(S.notifTransferring, lang), active.size, pct)
        }
        return NotificationCompat.Builder(this, WgfsApp.CHANNEL_ID)
            .setContentTitle("WGFileSender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
