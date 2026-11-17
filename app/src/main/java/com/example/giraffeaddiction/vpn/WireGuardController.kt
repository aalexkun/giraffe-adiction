package com.example.giraffeaddiction.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

enum class VpnTarget { UP, DOWN }

/**
 * Talks to WireGuard with the explicit broadcast its manifest receiver listens for. The receiver
 * only acts when "Allow remote control apps" is on in WireGuard, and only for senders holding
 * [PERMISSION]; there is no query intent, so the real state is read from the network instead.
 */
class WireGuardController(private val context: Context) {

    fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Null when the broadcast went out; otherwise why it could not. */
    fun send(target: VpnTarget, tunnelName: String): String? {
        if (!isInstalled()) return "WireGuard is not installed"
        if (!hasPermission()) return "CONTROL_TUNNELS permission not granted"
        val tunnel = tunnelName.trim()
        if (tunnel.isEmpty()) return "tunnel name is empty"
        val action = if (target == VpnTarget.UP) ACTION_UP else ACTION_DOWN
        context.sendBroadcast(
            Intent(action)
                .setComponent(ComponentName(PACKAGE, RECEIVER))
                .putExtra("tunnel", tunnel)
                // Foreground queue so the request is not held behind background broadcasts, and
                // delivered even if WireGuard sits in the stopped state after a force-stop.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        )
        return null
    }

    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    companion object {
        const val PACKAGE = "com.wireguard.android"
        const val RECEIVER = "com.wireguard.android.model.TunnelManager\$IntentReceiver"
        const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
        const val ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
        const val ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    }
}
