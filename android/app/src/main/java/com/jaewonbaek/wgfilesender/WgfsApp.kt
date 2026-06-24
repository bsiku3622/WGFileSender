package com.jaewonbaek.wgfilesender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.jaewonbaek.wgfilesender.data.AppController
import com.jaewonbaek.wgfilesender.ui.S
import com.jaewonbaek.wgfilesender.ui.str

class WgfsApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
        createChannel()
    }

    private fun createChannel() {
        val lang = controller.language.value
        val channel = NotificationChannel(
            CHANNEL_ID,
            str(S.channelName, lang),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = str(S.channelDesc, lang) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "wgfs_receiving"
    }
}

val Context.controller: AppController
    get() = (applicationContext as WgfsApp).controller
