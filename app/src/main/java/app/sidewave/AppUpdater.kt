package app.sidewave

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

// Обновления берутся из GitHub Releases репозитория, из которого собран APK
class AppUpdater(private val activity: Activity, private val deliver: (JSONObject) -> Unit) {
    private companion object {
        const val MAX_APK_SIZE = 64L * 1024 * 1024
        val TRUSTED_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }

    private class Release(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

    private val executor = Executors.newSingleThreadExecutor()
    private val folder = File(activity.cacheDir, "updates")
    @Volatile private var release: Release? = null
    @Volatile private var busy = false
    @Volatile private var downloaded: File? = null
    private var awaitingPermission = false

    fun check() {
        if (busy) return
        busy = true
        executor.execute {
            try {
                val json = JSONObject(String(get("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest", 1024 * 1024), Charsets.UTF_8))
                val version = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                if (apk == null || compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                    release = null
                    send("latest")
                } else {
                    val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    val found = Release(version, json.optString("body").take(2000), apk.getString("browser_download_url"), apk.optLong("size"), digest)
                    release = found
                    send("available", JSONObject().put("version", found.version).put("notes", found.notes).put("size", found.size))
                }
            } catch (_: Exception) {
                send("error")
            } finally {
                busy = false
            }
        }
    }

    fun install() {
        val target = release ?: return
        if (busy) return
        downloaded?.takeIf { it.isFile && it.name == fileName(target) }?.let { launchInstaller(it); return }
        busy = true
        executor.execute {
            try {
                folder.mkdirs()
                folder.listFiles()?.forEach { it.delete() }
                val file = File(folder, fileName(target))
                download(target, file)
                if (!isOwnUpdate(file)) {
                    file.delete()
                    send("error", JSONObject().put("reason", "signature"))
                    return@execute
                }
                downloaded = file
                activity.runOnUiThread { launchInstaller(file) }
            } catch (_: Exception) {
                send("error", JSONObject().put("reason", "download"))
            } finally {
                busy = false
            }
        }
    }

    // Пользователь вернулся из настроек «Установка неизвестных приложений»
    fun onResume() {
        if (!awaitingPermission) return
        awaitingPermission = false
        downloaded?.takeIf { it.isFile }?.let { launchInstaller(it) }
    }

    fun close() = executor.shutdownNow()

    private fun launchInstaller(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            send("permission")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        send("installing")
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun download(target: Release, file: File) {
        val connection = open(target.url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: target.size
            if (total > MAX_APK_SIZE) error("APK is too large")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var reported = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_APK_SIZE) error("APK is too large")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = if (total > 0) (received * 100 / total).toInt().coerceAtMost(100) else 0
                        if (percent >= reported + 5) {
                            reported = percent
                            send("downloading", JSONObject().put("progress", percent))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            target.sha256?.let { if (!it.equals(actual, ignoreCase = true)) error("Checksum mismatch") }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    // Установщик и так отклонит чужую подпись, но молча; проверка здесь даёт понятную ошибку
    @Suppress("DEPRECATION")
    private fun isOwnUpdate(file: File): Boolean {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != activity.packageName) return false
        val incoming = archive.signingInfo?.apkContentsSigners ?: return false
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners ?: return false
        if (incoming.isEmpty() || installed.isEmpty()) return false
        return incoming.map { it.toCharsString() }.toSet() == installed.map { it.toCharsString() }.toSet()
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > limit) error("Response is too large")
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    // Редиректы проверяются вручную: скачивать APK можно только с хостов GitHub и только по HTTPS
    private fun open(start: String): HttpURLConnection {
        var url = URL(start)
        repeat(5) {
            if (url.protocol != "https" || url.host !in TRUSTED_HOSTS) error("Untrusted host ${url.host}")
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            connection.setRequestProperty("User-Agent", "Dashphonic/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                url = URL(url, location ?: error("Redirect without location"))
            } else if (code == 200) {
                return connection
            } else {
                connection.disconnect()
                error("HTTP $code")
            }
        }
        error("Too many redirects")
    }

    private fun fileName(target: Release) = "dashphonic-${target.version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk"

    private fun send(state: String, extra: JSONObject = JSONObject()) {
        deliver(extra.put("state", state).put("current", BuildConfig.VERSION_NAME))
    }
}

// 1.10.0 > 1.9.2; суффикс после дефиса (1.2.0-beta) считается младше релиза
internal fun compareVersions(a: String, b: String): Int {
    fun parts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val left = parts(a)
    val right = parts(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return (if ('-' in a) 0 else 1) - (if ('-' in b) 0 else 1)
}
