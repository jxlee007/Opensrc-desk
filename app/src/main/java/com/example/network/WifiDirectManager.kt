package com.example.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiDirectPeerInfo(
    val deviceName: String,
    val deviceAddress: String,
    val status: Int
)

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _peers = MutableStateFlow<List<WifiDirectPeerInfo>>(emptyList())
    val peers: StateFlow<List<WifiDirectPeerInfo>> = _peers.asStateFlow()

    private val _groupOwnerIp = MutableStateFlow<String?>(null)
    val groupOwnerIp: StateFlow<String?> = _groupOwnerIp.asStateFlow()

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    try {
                        manager?.requestPeers(ch) { list ->
                            _peers.value = list.deviceList.map { dev: WifiP2pDevice ->
                                WifiDirectPeerInfo(
                                    deviceName = dev.deviceName.ifBlank { "Wi-Fi Direct Device" },
                                    deviceAddress = dev.deviceAddress,
                                    status = dev.status
                                )
                            }
                        }
                    } catch (_: SecurityException) {
                        _statusMessage.value = "Nearby Wi-Fi permission required"
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    manager?.requestConnectionInfo(ch) { info ->
                        if (info.groupFormed) {
                            _groupOwnerIp.value = info.groupOwnerAddress?.hostAddress
                            _statusMessage.value = if (info.isGroupOwner) {
                                "P2P Group Owner ready"
                            } else {
                                "Connected to ${info.groupOwnerAddress?.hostAddress ?: "peer"}"
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val mgr = manager ?: run {
            _statusMessage.value = "Wi-Fi Direct not supported on this hardware"
            return
        }
        if (channel == null) {
            channel = mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
        val ch = channel ?: return
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _statusMessage.value = "Scanning Wi-Fi Direct peers…"
                }

                override fun onFailure(reason: Int) {
                    _statusMessage.value = "Wi-Fi Direct scan error ($reason)"
                }
            })
        } catch (_: SecurityException) {
            _statusMessage.value = "Permission required for Wi-Fi Direct"
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _statusMessage.value = "Negotiating P2P link…"
                }

                override fun onFailure(reason: Int) {
                    _statusMessage.value = "P2P connect failed ($reason)"
                }
            })
        } catch (_: SecurityException) {
            _statusMessage.value = "Permission required for Wi-Fi Direct"
        }
    }

    fun stop() {
        if (receiverRegistered) {
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        val ch = channel ?: return
        try {
            manager?.stopPeerDiscovery(ch, null)
        } catch (_: Throwable) {
        }
    }
}
