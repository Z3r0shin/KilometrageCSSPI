package com.transportlogger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app from the repo's public GitHub releases, where every build is
 * attached as Kilometrage-CSSPI-v<version>.apk.
 *
 * Three checks stand between a download and the installer: the file must match
 * the SHA-256 GitHub publishes for it, it must be this app, and it must be a
 * higher versionCode than the one installed. Android adds the fourth: an
 * update must carry the same signature (the shared debug key), or it is refused.
 *
 * Network calls block; run them off the main thread.
 */
object Updater {

    private const val LATEST =
        "https://api.github.com/repos/Z3r0shin/KilometrageCSSPI/releases/latest"
    private const val APK_PREFIX = "Kilometrage-CSSPI-v"

    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    class Release(
        val version: String,
        val apkUrl: String,
        val size: Long,
        /** Lowercase hex, when GitHub gives one. */
        val sha256: String?,
        val notes: String
    )

    /** Thrown with a message meant for the user. */
    class UpdateException(message: String) : IOException(message)

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    private fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** True when the automatic check has not run in the last day. */
    fun dueForCheck(ctx: Context): Boolean {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()
        return now - last >= DAY_MS || now < last
    }

    private fun markChecked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * The latest published release if it is newer than what is installed,
     * else null. Throws [UpdateException] when GitHub cannot be reached.
     */
    fun newerRelease(ctx: Context): Release? {
        val json = try {
            get(LATEST, ctx).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw UpdateException("Impossible de joindre GitHub. Vérifiez la connexion.")
        }
        markChecked(ctx)
        val release = parse(json) ?: return null
        return release.takeIf { isNewer(it.version, installedVersion(ctx)) }
    }

    private fun parse(json: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.startsWith(APK_PREFIX) || !name.endsWith(".apk")) continue
            return Release(
                version = version,
                apkUrl = a.optString("browser_download_url").ifBlank { return null },
                size = a.optLong("size", -1),
                sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")?.lowercase(),
                notes = notes(o.optString("body"))
            )
        }
        return null
    }

    /**
     * Release notes as plain text for the dialog: Markdown marks dropped, and
     * paragraphs about the .apk file left out (they explain how to install by
     * hand, which the app is doing for you).
     */
    internal fun notes(body: String): String =
        body.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .filterNot { it.contains(".apk", ignoreCase = true) }
            .joinToString("\n\n") { para ->
                para.lines().joinToString("\n") { line ->
                    line.replace(Regex("""^\s{0,3}#+\s*"""), "")
                        .replace("**", "").replace("__", "").replace("`", "")
                        .trimEnd()
                }.trim()
            }
            .trim()

    /** "1.10" is newer than "1.9", "1.3.1" than "1.3"; a leading "v" is ignored. */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-")
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Download the release's APK into the cache and check it. [onProgress]
     * gets 0..100, from the worker thread; [cancelled] is polled between
     * chunks.
     */
    fun download(
        ctx: Context, release: Release, onProgress: (Int) -> Unit, cancelled: () -> Boolean
    ): File {
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val apk = File(dir, "$APK_PREFIX${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            get(release.apkUrl, ctx).use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        if (cancelled()) throw UpdateException("Téléchargement annulé.")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (release.size > 0) {
                            val pct = (done * 100 / release.size).toInt().coerceIn(0, 100)
                            if (pct != shown) onProgress(pct).also { shown = pct }
                        }
                    }
                }
            }
        } catch (e: UpdateException) {
            apk.delete()
            throw e
        } catch (e: IOException) {
            apk.delete()
            throw UpdateException("Le téléchargement a été interrompu. Réessayez.")
        }

        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && hex != release.sha256) {
            apk.delete()
            throw UpdateException("Le fichier téléchargé est corrompu. Réessayez.")
        }
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info == null || info.packageName != ctx.packageName) {
            apk.delete()
            throw UpdateException("Ce fichier n'est pas une version de l'application.")
        }
        if (PackageInfoCompat.getLongVersionCode(info) <= installedCode(ctx)) {
            apk.delete()
            throw UpdateException("Cette version est déjà installée.")
        }
        return apk
    }

    /**
     * Hand the APK to Android's installer. Its answer comes to
     * [UpdateReceiver], which shows the confirmation screen when one is needed.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // Once this app has installed itself, later updates may need no tap.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, UpdateReceiver::class.java), flags
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw UpdateException("L'installation n'a pas pu démarrer.")
        } finally {
            apk.delete()
        }
    }

    private fun get(url: String, ctx: Context) =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "KilometrageCSSPI/${installedVersion(ctx)}")
            if (responseCode !in 200..299) {
                disconnect()
                throw IOException("HTTP $responseCode")
            }
            inputStream
        }
}
