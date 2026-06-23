package com.jaewonbaek.wgfilesender.data

import android.content.Context
import android.os.Build
import com.jaewonbaek.wgfilesender.model.Identity
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** SharedPreferences-backed JSON persistence. Tokens live here too for the MVP. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wgfs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadIdentity(): Identity {
        prefs.getString("identity", null)?.let { return json.decodeFromString(it) }
        val name = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ").ifBlank { "Android" }
        val id = Identity(deviceId = UUID.randomUUID().toString(), deviceName = name)
        saveIdentity(id)
        return id
    }

    fun saveIdentity(identity: Identity) {
        prefs.edit().putString("identity", json.encodeToString(identity)).apply()
    }

    fun loadPeers(): List<PeerDevice> {
        val raw = prefs.getString("peers", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PeerDevice>>(raw) }.getOrDefault(emptyList())
    }

    fun savePeers(peers: List<PeerDevice>) {
        prefs.edit().putString("peers", json.encodeToString(peers)).apply()
    }

    fun loadSettings(): Settings {
        val raw = prefs.getString("settings", null) ?: return Settings()
        return runCatching { json.decodeFromString<Settings>(raw) }.getOrDefault(Settings())
    }

    fun saveSettings(settings: Settings) {
        prefs.edit().putString("settings", json.encodeToString(settings)).apply()
    }
}
