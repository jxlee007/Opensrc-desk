package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiEpoch = MutableStateFlow(0)
    val wifiEpoch: StateFlow<Int> = _wifiEpoch.asStateFlow()

    private val _localIpAddress = MutableStateFlow(resolveLocalIpv4())
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _localIpAddress.value = resolveLocalIpv4()
            _wifiEpoch.value = _wifiEpoch.value + 1
        }

        override fun onLost(network: Network) {
            _localIpAddress.value = resolveLocalIpv4()
            _wifiEpoch.value = _wifiEpoch.value + 1
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val latest = resolveLocalIpv4()
            if (latest != _localIpAddress.value) {
                _localIpAddress.value = latest
                _wifiEpoch.value = _wifiEpoch.value + 1
            }
        }
    }

    fun start() {
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            registered = true
        } catch (_: Throwable) {
            // Fallback if callback registration fails on restricted devices
        }
    }

    fun stop() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        registered = false
    }

    fun refreshNow() {
        _localIpAddress.value = resolveLocalIpv4()
        _wifiEpoch.value = _wifiEpoch.value + 1
    }

    companion object {
        fun resolveLocalIpv4(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                var fallback: String? = null
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (nif.name.startsWith("wlan") || nif.name.startsWith("eth")) {
                                return ip
                            }
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                fallback ?: "127.0.0.1"
            } catch (_: Throwable) {
                "127.0.0.1"
            }
        }
    }
}
