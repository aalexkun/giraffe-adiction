package com.example.giraffeaddiction.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Three callbacks on [ConnectivityManager], folded into one [Snapshot]:
 *
 *  - a WiFi request, so the VPN being the default network does not hide the WiFi underneath it,
 *    built with the location flag so capabilities carry a [WifiInfo] with SSID and BSSID;
 *  - an "any physical internet" request (INTERNET and NOT_VPN), which is what decides whether the
 *    tunnel can be brought up at all;
 *  - a VPN request, which is the only honest way to know whether the tunnel is actually up.
 */
class NetworkWatcher(context: Context) {

    data class WifiLink(val network: Network, val ssid: String?, val bssid: String?) {
        val key: String get() = "$network|$ssid|$bssid"
    }

    data class Snapshot(
        val wifi: WifiLink? = null,
        val internetNetworks: Set<Network> = emptySet(),
        val vpnNetworks: Set<Network> = emptySet(),
        /** Bumped to force a re-evaluation without any real change. */
        val tick: Long = 0,
    ) {
        val anyInternet: Boolean get() = internetNetworks.isNotEmpty()
        val vpnUp: Boolean get() = vpnNetworks.isNotEmpty()
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
    ) {
        @Volatile
        var current: Network? = null

        override fun onAvailable(network: Network) {
            current = network
            _snapshot.update { it.copy(wifi = WifiLink(network, null, null)) }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != current) return
            val info = caps.transportInfo as? WifiInfo
            val link = WifiLink(network, cleanSsid(info?.ssid), cleanBssid(info?.bssid))
            _snapshot.update { if (it.wifi == link) it else it.copy(wifi = link) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // An address arrived: the LAN probe may succeed now where it failed a second ago.
            if (network == current) poke()
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                _snapshot.update { it.copy(wifi = null) }
            }
        }
    }

    private val internetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _snapshot.update { it.copy(internetNetworks = it.internetNetworks + network) }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val has = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _snapshot.update {
                val set = if (has) it.internetNetworks + network else it.internetNetworks - network
                if (set == it.internetNetworks) it else it.copy(internetNetworks = set)
            }
        }

        override fun onLost(network: Network) {
            _snapshot.update { it.copy(internetNetworks = it.internetNetworks - network) }
        }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _snapshot.update { it.copy(vpnNetworks = it.vpnNetworks + network) }
        }

        override fun onLost(network: Network) {
            _snapshot.update { it.copy(vpnNetworks = it.vpnNetworks - network) }
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                internetCallback,
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                vpnCallback,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not register network callbacks", t)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        for (cb in listOf(wifiCallback, internetCallback, vpnCallback)) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    /** Re-emit the current picture so the decision rule runs again. */
    fun poke() {
        _snapshot.update { it.copy(tick = it.tick + 1) }
    }

    /**
     * Re-registers the WiFi callback. Android redacts the SSID for a service that was started in
     * the background; once the app has been in front, a fresh registration gets the full
     * capabilities delivered again, SSID included.
     */
    fun refreshWifi() {
        if (!started) return
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        wifiCallback.current = null
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
        }.onFailure { Log.w(TAG, "could not re-register the WiFi callback", it) }
    }

    companion object {
        private const val TAG = "NetworkWatcher"

        fun cleanSsid(raw: String?): String? {
            if (raw == null) return null
            val s = raw.removeSurrounding("\"")
            return if (s.isBlank() || s == WifiManagerUnknownSsid) null else s
        }

        fun cleanBssid(raw: String?): String? {
            if (raw == null) return null
            return if (raw.isBlank() || raw == "02:00:00:00:00:00") null else raw.lowercase()
        }

        private const val WifiManagerUnknownSsid = "<unknown ssid>"
    }
}
