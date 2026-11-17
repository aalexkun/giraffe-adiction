package com.example.giraffeaddiction.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.example.giraffeaddiction.BuildConfig
import com.example.giraffeaddiction.data.ShizukuStatus
import com.example.giraffeaddiction.privileged.IForegroundListener
import com.example.giraffeaddiction.privileged.IPauserService
import com.example.giraffeaddiction.privileged.PauserUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Binds the privileged worker and keeps it bound: binder received, binder dead, permission, and
 * reconnects. Everything the engine needs from Shizuku goes through [call], which returns null
 * when the worker is not there so the caller can take the fail-safe branch.
 */
class ShizukuBridge(
    private val context: Context,
    private val events: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onForegroundChanged(packageName: String, foreground: Boolean)
        fun onObserverFailed(reason: String)
    }

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status

    @Volatile
    private var service: IPauserService? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, PauserUserService::class.java.name))
        .daemon(true)
        .processNameSuffix("pauser")
        .debuggable(BuildConfig.DEBUG)
        .version(PauserUserService.VERSION)
        .tag("giraffe-pauser")

    private val foregroundListener = object : IForegroundListener.Stub() {
        override fun onForegroundChanged(packageName: String, pid: Int, uid: Int, foreground: Boolean) {
            events.onForegroundChanged(packageName, foreground)
        }

        override fun onObserverFailed(reason: String) {
            events.onObserverFailed(reason)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "worker died")
        service = null
        _status.value = ShizukuStatus.DEAD
        events.onDisconnected("worker died")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _status.value = ShizukuStatus.DEAD
                events.onDisconnected("bind returned a dead binder")
                return
            }
            val s = IPauserService.Stub.asInterface(binder)
            runCatching { binder.linkToDeath(deathRecipient, 0) }
            try {
                s.setForegroundListener(foregroundListener)
            } catch (t: Throwable) {
                Log.w(TAG, "could not register foreground listener", t)
            }
            service = s
            _status.value = ShizukuStatus.CONNECTED
            events.onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _status.value = ShizukuStatus.DEAD
            events.onDisconnected("service disconnected")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { bind() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        _status.value = ShizukuStatus.NOT_RUNNING
        events.onDisconnected("Shizuku stopped")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
        else _status.value = ShizukuStatus.NO_PERMISSION
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        if (!Shizuku.pingBinder()) _status.value = ShizukuStatus.NOT_RUNNING
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        // The worker is a daemon: it keeps running for the next bind, which is the point.
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        service = null
    }

    /** Try again; cheap to call whenever something might have changed. */
    fun bind() {
        if (!Shizuku.pingBinder()) {
            _status.value = ShizukuStatus.NOT_RUNNING
            return
        }
        if (Shizuku.isPreV11()) {
            _status.value = ShizukuStatus.NOT_RUNNING
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _status.value = ShizukuStatus.NO_PERMISSION
            return
        }
        if (service != null) return
        _status.value = ShizukuStatus.CONNECTING
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            Log.w(TAG, "bindUserService failed", t)
            _status.value = ShizukuStatus.DEAD
        }
    }

    val isConnected: Boolean get() = service != null

    /** Runs [block] against the worker on an IO thread; null when there is no worker or it throws. */
    suspend fun <T> call(block: (IPauserService) -> T): T? {
        val s = service ?: return null
        return withContext(Dispatchers.IO) {
            try {
                block(s)
            } catch (t: Throwable) {
                Log.w(TAG, "worker call failed", t)
                null
            }
        }
    }

    /** Null = applied; otherwise why not. */
    suspend fun setSuspended(packageName: String, suspended: Boolean): String? {
        val result = call { s ->
            val err = if (suspended) s.suspendPackage(packageName) else s.unsuspendPackage(packageName)
            if (err == null && suspended) s.forceStopPackage(packageName)
            err ?: ""
        } ?: return "Shizuku worker not connected"
        return result.ifEmpty { null }
    }

    companion object {
        private const val TAG = "ShizukuBridge"
        const val PERMISSION_REQUEST_CODE = 7001

        fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun hasPermission(): Boolean = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        fun requestPermission() {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        }
    }
}
