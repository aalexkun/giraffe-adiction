package com.example.giraffeaddiction.vpn

import android.net.Network
import com.example.giraffeaddiction.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * Decides whether a WiFi link is home. Two strategies, used by whichever is configured and both
 * when both are: a saved SSID/BSSID list (needs the location grant) and a LAN probe, a one-second
 * TCP connect to a host that only answers at home, made over the WiFi network itself so a tunnel
 * that happens to be up cannot answer on its behalf.
 */
class HomeDetector {

    data class Verdict(val home: Boolean, val reason: String)

    suspend fun check(wifi: NetworkWatcher.WifiLink, settings: Settings): Verdict {
        val homes = settings.homeNetworkList
        val probeHost = settings.probeHost.trim()
        if (homes.isEmpty() && probeHost.isEmpty()) return Verdict(false, "no home network saved")

        val reasons = ArrayList<String>(2)
        var ssidOk: Boolean? = null
        if (homes.isNotEmpty()) {
            ssidOk = if (wifi.ssid == null) {
                reasons += "SSID hidden (location permission?)"
                false
            } else {
                val match = homes.any { h ->
                    h.ssid == wifi.ssid && (h.bssid.isBlank() || h.bssid.equals(wifi.bssid, ignoreCase = true))
                }
                reasons += if (match) "SSID ${wifi.ssid} is home" else "SSID ${wifi.ssid} is not home"
                match
            }
        }

        var probeOk: Boolean? = null
        if (probeHost.isNotEmpty()) {
            probeOk = probeReachable(wifi.network, probeHost, settings.probePort)
            reasons += if (probeOk) "$probeHost answers" else "$probeHost unreachable"
        }

        val home = (ssidOk ?: true) && (probeOk ?: true)
        return Verdict(home, reasons.joinToString(", "))
    }

    private suspend fun probeReachable(network: Network, host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val address = network.getByName(host)
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MS)
                }
                true
            } catch (t: Throwable) {
                false
            }
        }

    companion object {
        const val PROBE_TIMEOUT_MS = 1_000
    }
}
