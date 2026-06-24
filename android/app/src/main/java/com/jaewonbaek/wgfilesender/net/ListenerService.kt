package com.jaewonbaek.wgfilesender.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jaewonbaek.wgfilesender.MainActivity
import com.jaewonbaek.wgfilesender.R
import com.jaewonbaek.wgfilesender.WgfsApp
import com.jaewonbaek.wgfilesender.controller
import com.jaewonbaek.wgfilesender.ui.S
import com.jaewonbaek.wgfilesender.ui.str

/** Keeps the HTTP listener alive in the background via a foreground notification. */
class ListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        controller.startListener()
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stopListener()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val port = controller.settings.value.port
        return NotificationCompat.Builder(this, WgfsApp.CHANNEL_ID)
            .setContentTitle("WGFileSender")
            .setContentText(String.format(str(S.notifReady, controller.language.value), port))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
