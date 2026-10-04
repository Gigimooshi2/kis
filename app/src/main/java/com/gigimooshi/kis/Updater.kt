package com.gigimooshi.kis

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub releases (same approach as GigiKav):
 * check every few hours, download quietly, install while the app is closed.
 * Android 12+ lets an app update itself with no prompt once it is its own installer of record,
 * so only the first update asks for confirmation (shown as a banner the next time you open Kis).
 */
object Updater {
    const val OWNER = "Gigimooshi2"
    const val REPO = "kis"
    private const val APK_NAME = "kis.apk"
    private const val TAG = "KisUpdate"

    private const val JOB_PERIODIC = 4201
    private const val JOB_SOON = 4202
    private const val CHECK_EVERY_MS = 30 * 60_000L

    @Volatile var visible = false

    /** Foreground status for the in-app banner. */
    @Volatile var status: String? = null

    data class Release(val versionCode: Long, val tag: String, val apkUrl: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun autoUpdate(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoUpdate(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto", on).apply()
        schedule(ctx)
    }

    @Suppress("DEPRECATION")
    private fun vc(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun installedVersion(ctx: Context): Long = vc(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Downloaded update waiting to be installed, if it's newer than what's installed. */
    fun pendingFile(ctx: Context): File? {
        val v = prefs(ctx).getLong("pending", 0L)
        if (v <= installedVersion(ctx)) return null
        return apkFor(ctx, v).takeIf { it.exists() }
    }

    fun pendingVersion(ctx: Context): Long = prefs(ctx).getLong("pending", 0L)

    private fun apkFor(ctx: Context, version: Long) = File(File(ctx.cacheDir, "updates"), "kis-$version.apk")

    // ---- GitHub ------------------------------------------------------------

    private fun open(url: String, follow: Boolean) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = follow
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Kis")
    }

    /**
     * Newest release, via github.com's /releases/latest redirect (no API rate limit).
     * Tags are v1.0.<build number>, and the build number is the versionCode.
     */
    fun latest(): Release? {
        val c = open("https://github.com/$OWNER/$REPO/releases/latest", follow = false)
        try {
            val code = c.responseCode
            if (code == 404) throw IOException("Release page not found (the repo must be public)")
            if (code !in 300..399) throw IOException("GitHub HTTP $code")
            val loc = c.getHeaderField("Location") ?: return null
            val tag = loc.substringAfter("/releases/tag/", "").substringBefore('?')
            val build = Regex("(\\d+)$").find(tag)?.value?.toLongOrNull() ?: return null
            return Release(build, tag, "https://github.com/$OWNER/$REPO/releases/download/$tag/$APK_NAME")
        } finally {
            c.disconnect()
        }
    }

    fun download(ctx: Context, rel: Release): File {
        val file = apkFor(ctx, rel.versionCode)
        val dir = file.parentFile!!.apply { mkdirs() }
        if (file.exists() && isValidApk(ctx, file, rel.versionCode)) return file
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, file.name + ".part")
        val c = open(rel.apkUrl, follow = true)
        try {
            if (c.responseCode != 200) throw IOException("Download HTTP ${c.responseCode}")
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(file)) throw IOException("Couldn't save the download")
        if (!isValidApk(ctx, file, rel.versionCode)) {
            file.delete()
            throw IOException("Downloaded file isn't a valid Kis update")
        }
        prefs(ctx).edit().putLong("pending", rel.versionCode).apply()
        return file
    }

    @Suppress("DEPRECATION")
    private fun isValidApk(ctx: Context, file: File, version: Long): Boolean {
        val info = ctx.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return info.packageName == ctx.packageName && vc(info) >= version
    }

    /** Checks (throttled), downloads if newer. Returns the release if an update is now waiting. Blocking. */
    fun checkAndDownload(ctx: Context, force: Boolean): Release? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last_check", 0L) < CHECK_EVERY_MS) return null
        p.edit().putLong("last_check", now).apply()
        val rel = latest() ?: return null
        if (rel.versionCode <= installedVersion(ctx)) return null
        download(ctx, rel)
        return rel
    }

    // ---- install -----------------------------------------------------------

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** True once Kis installed itself at least once — from then on, Android 12+ updates need no prompt. */
    private fun isOwnInstaller(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName
        }.getOrDefault(false)
    }

    /** Session install. Makes Kis its own installer of record, which is what allows silent updates later. */
    fun install(ctx: Context, file: File, silent: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED
                )
            }
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateResultReceiver::class.java), flags)
            s.commit(result.intentSender)
        }
    }

    // ---- background --------------------------------------------------------

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!autoUpdate(ctx)) {
            js.cancel(JOB_PERIODIC)
            js.cancel(JOB_SOON)
            return
        }
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        js.schedule(
            JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(4 * 60 * 60_000L)
                .setPersisted(true)
                .build()
        )
    }

    /** App just went to the background: try installing shortly, if it stays closed. */
    fun soon(ctx: Context) {
        if (!autoUpdate(ctx) || pendingFile(ctx) == null) return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        js.schedule(
            JobInfo.Builder(JOB_SOON, ComponentName(ctx, UpdateJob::class.java))
                .setMinimumLatency(90_000L)
                .build()
        )
    }

    /** Runs on a background thread from [UpdateJob]. */
    fun backgroundRun(ctx: Context) {
        if (!autoUpdate(ctx)) return
        checkAndDownload(ctx, force = false)
        val file = pendingFile(ctx) ?: return
        // Silent install is only possible once Kis is its own installer; otherwise the
        // first update waits for a tap on the in-app banner.
        if (visible || !canInstall(ctx) || !isOwnInstaller(ctx)) return
        install(ctx, file, silent = true)
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var retry = false
            try {
                Updater.backgroundRun(applicationContext)
            } catch (e: Exception) {
                Log.w("KisUpdate", "background update failed", e)
                retry = params.jobId != 4201 // periodic job just waits for its next run
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First self-update: Android wants confirmation. Only possible while Kis is open;
                // otherwise the banner offers it next time.
                if (!Updater.visible) return
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = null
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Log.w("KisUpdate", "install failed: $msg")
                Updater.status = "Update failed: $msg"
            }
        }
    }
}

/** After an update lands, make sure the periodic check is still scheduled. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.schedule(ctx)
    }
}
