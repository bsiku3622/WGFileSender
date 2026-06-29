package com.jaewonbaek.wgfilesender.net

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList()
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)

/** A newer release resolved for this platform. */
data class UpdateInfo(
    val version: String,
    val releaseNotes: String,
    val pageUrl: String,
    val assetUrl: String?,   // direct .apk download; null → open the page
    val assetName: String?,
    val assetSize: Long
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Downloaded(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks GitHub Releases for a newer build and downloads the APK. */
class UpdateService(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(HttpTimeout) }

    /** This build's versionName (falls back to 0.0.0 if unreadable). */
    val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    /** Update details if the latest release is newer than the running build, else null. */
    suspend fun checkForUpdate(): UpdateInfo? {
        val release = latestRelease()
        val latest = normalize(release.tagName)
        if (!isNewer(latest, currentVersion)) return null
        val asset = apkAsset(release.assets)
        return UpdateInfo(
            version = latest,
            releaseNotes = release.body?.trim().orEmpty(),
            pageUrl = release.htmlUrl,
            assetUrl = asset?.browserDownloadUrl,
            assetName = asset?.name,
            assetSize = asset?.size ?: 0L
        )
    }

    /** Downloads the APK into the cache dir and returns the file. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val url = info.assetUrl ?: error("no asset")
        val bytes: ByteArray = client.get(url) {
            onDownload { sent, total ->
                val t = total ?: info.assetSize
                if (t > 0) onProgress((sent.toFloat() / t).coerceIn(0f, 1f))
            }
        }.body()
        val file = File(context.cacheDir, info.assetName ?: "update.apk")
        file.writeBytes(bytes)
        return file
    }

    private suspend fun latestRelease(): GitHubRelease {
        val resp = client.get("https://api.github.com/repos/$REPO/releases/latest") {
            header("Accept", "application/vnd.github+json")
            timeout { requestTimeoutMillis = 20_000 }
        }
        check(resp.status.value == 200) { "github ${resp.status.value}" }
        return json.decodeFromString(resp.bodyAsText())
    }

    companion object {
        const val REPO = "bsiku3622/WGFileSender"

        fun normalize(tag: String): String = tag.removePrefix("v")

        fun apkAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
            assets.firstOrNull { it.name.lowercase().endsWith(".apk") }

        fun isNewer(a: String, b: String): Boolean = compareVer(a, b) > 0

        /** semver-ish: numeric major.minor.patch; a pre-release (`-…`) ranks below its release. */
        fun compareVer(a: String, b: String): Int {
            val (av, ap) = splitPre(a)
            val (bv, bp) = splitPre(b)
            for (i in 0..2) if (av[i] != bv[i]) return if (av[i] < bv[i]) -1 else 1
            if (ap.isEmpty() != bp.isEmpty()) return if (ap.isEmpty()) 1 else -1
            return ap.compareTo(bp)
        }

        private fun splitPre(s: String): Pair<List<Int>, String> {
            val parts = s.split("-", limit = 2)
            val nums = parts[0].split(".").map { it.toIntOrNull() ?: 0 }
            val padded = (nums + listOf(0, 0, 0)).take(3)
            return padded to (parts.getOrNull(1) ?: "")
        }
    }
}
