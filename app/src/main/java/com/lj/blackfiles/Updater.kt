package com.lj.blackfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the repo's GitHub Releases. CI tags each build v1.0.<run number> and uses the
 * same run number as versionCode, so the tag tells us whether the release is newer.
 */
object Updater {
    class Release(val version: String, val versionCode: Int, val apkUrl: String, val size: Long) {
        val isNewer get() = versionCode > BuildConfig.VERSION_CODE
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val conn = connect("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .first { it.getString("name").endsWith(".apk") }
        Release(version, version.substringAfterLast('.').toInt(), apk.getString("browser_download_url"), apk.optLong("size"))
    }

    /** Downloads into the cache, reporting 0..1 progress. A finished download is reused. */
    suspend fun download(c: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "BlackFiles-${release.versionCode}.apk")
        if (apk.exists() && (release.size <= 0 || apk.length() == release.size)) return@withContext apk
        dir.listFiles()?.forEach { it.delete() }

        val part = File(dir, apk.name + ".part")
        val conn = connect(release.apkUrl)
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        check(part.renameTo(apk)) { "can't save update" }
        apk
    }

    /** Hands the APK to the system installer. First time round, Android wants "install unknown apps" allowed. */
    fun install(c: Context, apk: File) {
        if (!c.packageManager.canRequestPackageInstalls()) {
            toast(c, "Allow Black Files to install apps, then tap Install again")
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")))
            return
        }
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Storage.uri(c, apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "BlackFiles")
    }
}
