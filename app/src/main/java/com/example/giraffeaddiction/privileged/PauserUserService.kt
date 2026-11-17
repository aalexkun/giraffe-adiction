package com.example.giraffeaddiction.privileged

import android.app.IProcessObserver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The privileged worker. Shizuku spawns it as the shell user (uid 2000) in its own process, with
 * this APK on the class path and no Application object, so nothing here may touch [com.example.giraffeaddiction.Graph].
 *
 * It does exactly two things: executes privileged calls on request, and watches foreground changes
 * through the activity manager's process observer, reporting them to the app over AIDL. If the
 * observer cannot be registered on this ROM, it polls the top task every two seconds instead and
 * tells the app so.
 */
class PauserUserService() : IPauserService.Stub() {

    /** Shizuku 13 passes a package context; this constructor is what it looks for. */
    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "started with context for ${context.packageName}")
    }

    @Volatile
    private var listener: IForegroundListener? = null

    private val foregroundPids = ConcurrentHashMap<Int, String>()
    private val packageByUid = ConcurrentHashMap<Int, String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "giraffe-poller").apply { isDaemon = true }
    }

    @Volatile
    private var observer: IProcessObserver? = null

    @Volatile
    private var pollFuture: ScheduledFuture<*>? = null

    @Volatile
    private var lastPolledTop: String? = null

    @Volatile
    private var mode: String = "idle"

    override fun destroy() {
        Log.i(TAG, "destroy")
        stopWatching()
        scheduler.shutdownNow()
        System.exit(0)
    }

    override fun exit() = destroy()

    override fun getVersion(): Int = VERSION

    override fun getObserverMode(): String = mode

    // --- privileged calls ---------------------------------------------------------------------

    override fun suspendPackage(packageName: String): String? {
        val out = shell("cmd", "package", "suspend", "--user", "0", packageName)
        return if (out.contains("new suspended state: true", ignoreCase = true)) null else out.ifBlank { "no output" }
    }

    override fun unsuspendPackage(packageName: String): String? {
        val out = shell("cmd", "package", "unsuspend", "--user", "0", packageName)
        return if (out.contains("new suspended state: false", ignoreCase = true)) null else out.ifBlank { "no output" }
    }

    override fun forceStopPackage(packageName: String): String? {
        return try {
            ActivityManagerApis.forceStopPackage(packageName, 0)
            null
        } catch (t: Throwable) {
            // The CLI path works on every version; the binder is just quicker.
            val out = shell("am", "force-stop", packageName)
            if (out.isBlank() || out.contains("Error", ignoreCase = true).not()) null else out
        }
    }

    override fun isPackageSuspended(packageName: String): Boolean {
        val info = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0L, 0)
        if (info != null) return info.flags and ApplicationInfo.FLAG_SUSPENDED != 0
        // Fallback for anything the binder would not hand over: the dump prints the user state.
        val dump = shell("dumpsys", "package", packageName)
        return dump.lineSequence().any { it.trimStart().startsWith("User 0:") && it.contains("suspended=true") }
    }

    override fun getForegroundPackage(): String? = topPackage()

    override fun allowBackgroundStart(packageName: String, seconds: Int): String? {
        val ms = (seconds.coerceIn(1, 600) * 1_000L).toString()
        var out = shell("cmd", "deviceidle", "tempwhitelist", "-d", ms, packageName)
        if (out.contains("Unknown command", ignoreCase = true)) {
            out = shell("cmd", "deviceidle", "tempallowlist", "-d", ms, packageName)
        }
        val failed = listOf("exception", "error", "unknown", "denied", "usage:", "exec failed")
            .any { out.contains(it, ignoreCase = true) }
        return if (failed) out.lineSequence().firstOrNull()?.take(200) ?: "no output" else null
    }

    // --- foreground watching ------------------------------------------------------------------

    override fun setForegroundListener(listener: IForegroundListener?) {
        this.listener = listener
        if (listener == null) {
            stopWatching()
            return
        }
        if (observer != null || pollFuture != null) return
        try {
            val obs = createObserver()
            ActivityManagerApis.registerProcessObserver(obs)
            observer = obs
            mode = "observer"
            Log.i(TAG, "process observer registered")
        } catch (t: Throwable) {
            Log.w(TAG, "process observer unavailable, polling instead", t)
            mode = "polling"
            safely { listener.onObserverFailed(t.toString()) }
            startPolling()
        }
    }

    private fun stopWatching() {
        observer?.let { obs -> runCatching { ActivityManagerApis.unregisterProcessObserver(obs) } }
        observer = null
        pollFuture?.cancel(false)
        pollFuture = null
        mode = "idle"
    }

    private fun createObserver(): IProcessObserver.Stub = object : IProcessObserver.Stub() {
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean) {
            val pkg = if (foregroundActivities) {
                packageForUid(uid)?.also { foregroundPids[pid] = it }
            } else {
                foregroundPids.remove(pid) ?: packageForUid(uid)
            }
            if (pkg != null) notify(pkg, pid, uid, foregroundActivities)
        }

        override fun onProcessDied(pid: Int, uid: Int) {
            foregroundPids.remove(pid)?.let { notify(it, pid, uid, false) }
        }

        override fun onProcessStateChanged(pid: Int, uid: Int, procState: Int) = Unit
        override fun onForegroundServicesChanged(pid: Int, uid: Int, serviceTypes: Int) = Unit
        override fun onProcessStarted(pid: Int, processUid: Int, packageUid: Int, packageName: String?, processName: String?) = Unit
    }

    private fun startPolling() {
        pollFuture = scheduler.scheduleWithFixedDelay({
            val top = topPackage()
            val previous = lastPolledTop
            if (top != previous) {
                lastPolledTop = top
                if (previous != null) notify(previous, 0, 0, false)
                if (top != null) notify(top, 0, 0, true)
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun topPackage(): String? = try {
        ActivityManagerApis.getTasks(1).firstOrNull()?.topActivity?.packageName
    } catch (t: Throwable) {
        null
    }

    private fun packageForUid(uid: Int): String? =
        packageByUid[uid] ?: PackageManagerApis.getPackagesForUidNoThrow(uid)?.firstOrNull()?.also { packageByUid[uid] = it }

    private fun notify(pkg: String, pid: Int, uid: Int, foreground: Boolean) {
        val l = listener ?: return
        try {
            l.onForegroundChanged(pkg, pid, uid, foreground)
        } catch (e: RemoteException) {
            // The app side is gone; the next bind will set a new listener.
            listener = null
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "listener call failed", t)
        }
    }

    // --- shell ----------------------------------------------------------------------------------

    private fun shell(vararg command: String): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        text.trim()
    } catch (t: Throwable) {
        "exec failed: $t"
    }

    companion object {
        private const val TAG = "PauserUserService"
        private const val POLL_INTERVAL_MS = 2_000L

        /** Bump whenever this class changes: Shizuku restarts a running worker on a mismatch. */
        const val VERSION = 2

        init {
            // This process is not an app process; make sure the hidden binder stubs are reachable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }
        }
    }
}
