package com.ocam.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a newer build on the public release, fetches it when asked, and hands it to the system
 * installer. The network and file calls block; the caller keeps them off the main thread.
 */
class SelfUpdater(context: Context) {

    private val appContext = context.applicationContext
    private val packages = appContext.packageManager
    private val folder = File(appContext.cacheDir, "update")

    val installedVersionCode: Long =
        packages.getPackageInfo(appContext.packageName, 0).longVersionCode

    /** What the release holds, or null when it holds nothing newer than this build. */
    @Throws(IOException::class, org.json.JSONException::class)
    fun fetchOffer(): UpdateOffer? {
        val connection = open(RELEASE_API_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                // The API allows a device 60 unauthenticated requests an hour.
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException("GitHub is limiting requests. Try later.")
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }

        val assets = JSONObject(body).getJSONArray("assets")
        val all = List(assets.length()) { assets.getJSONObject(it) }
        val build = newestBuild(all.map { it.getString("name") })
            ?: throw IOException("The release names no build")
        if (!isNewer(build.versionCode, installedVersionCode)) return null

        val apk = all.firstOrNull { it.getString("name") == FIXED_APK_NAME }
            ?: throw IOException("The newest build is still being published. Try again in a minute.")
        return UpdateOffer(build, apk.getString("browser_download_url"), apk.getLong("size"))
    }

    /** Fetches the file into the cache. Stops, and throws, when the coroutine is cancelled. */
    suspend fun download(offer: UpdateOffer, onPercent: (Int) -> Unit): File {
        folder.mkdirs()
        val target = File(folder, FIXED_APK_NAME)
        val connection = open(offer.apkUrl)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: offer.sizeBytes
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val percent = (written * 100 / total.coerceAtLeast(1)).toInt().coerceAtMost(100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    /** Compares a downloaded file with this install: same package, same key, newer version. */
    fun verify(apk: File): Verdict {
        val installed = packages.getPackageInfo(
            appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        return judgeDownload(
            archive = readArchive(apk),
            ownPackage = appContext.packageName,
            installedVersionCode = installedVersionCode,
            installedSigners = signers(installed),
        )
    }

    /** Whether the owner has let this app install, which Android asks once per installer. */
    fun canInstall(): Boolean = packages.canRequestPackageInstalls()

    /** The system page where that is allowed. The owner comes back here with the back button. */
    fun openInstallPermission() {
        appContext.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Hands the file to the system. On success Android stops this process to replace it, so
     * nothing after this call can count on running. [UpdateInstallReceiver] hears the outcome.
     */
    @Throws(IOException::class)
    fun install(apk: File) {
        val installer = packages.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            setSize(apk.length())
            // Honoured from Android 12 for an app updating itself, under the conditions in the
            // README. Below that, or when they are not met, Android asks the owner instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Mutable, because the system fills in the status and the confirmation intent. The
            // target is named and not exported, so nothing else can receive or redirect it.
            val callback = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                Intent(appContext, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        } catch (e: IOException) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    /** Leftovers from a download that was never installed, or from the update that started this. */
    fun clearDownloads() {
        folder.deleteRecursively()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "oCam/$installedVersionCode")
        }

    /**
     * Before Android 13 an archive's certificates are collected only when GET_SIGNATURES is among
     * the flags, and GET_SIGNING_CERTIFICATES alone returns no signing info. Hence both.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun readArchive(apk: File): ApkFacts? {
        val info = packages.getPackageArchiveInfo(
            apk.path,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
        ) ?: return null
        return ApkFacts(info.packageName, info.longVersionCode, signers(info))
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty()
            .map { certificateFingerprint(it.toByteArray()) }
            .toSet()
}
