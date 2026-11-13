package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class MdnsResolvedPeer(
    val serviceName: String,
    val peerId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val displaySummary: String
)

class MdnsManager(context: Context) {

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _resolvedPeers = MutableStateFlow<List<MdnsResolvedPeer>>(emptyList())
    val resolvedPeers: StateFlow<List<MdnsResolvedPeer>> = _resolvedPeers.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    fun startAdvertising(
        peerId: String,
        deviceName: String,
        displaySummary: String,
        port: Int
    ) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "ExtDesk-$peerId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("peerId", peerId)
            setAttribute("name", deviceName.take(40))
            setAttribute("summary", displaySummary.take(48))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                _isBroadcasting.value = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isBroadcasting.value = false
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                _isBroadcasting.value = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isBroadcasting.value = false
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            _isBroadcasting.value = false
        }
    }

    fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (_: Throwable) {
        }
        _isBroadcasting.value = false
    }

    fun startDiscovery(localDeviceId: String) {
        stopDiscovery()
        _resolvedPeers.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_extdesk")) return
                if (service.serviceName == "ExtDesk-$localDeviceId") return
                resolveQueue.add(service)
                drainResolveQueue()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _resolvedPeers.value = _resolvedPeers.value.filterNot {
                    it.serviceName == service.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        resolveQueue.clear()
        resolving.set(false)
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        try {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val hostAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                    } else {
                        serviceInfo.host?.hostAddress
                    }
                    if (!hostAddr.isNullOrBlank()) {
                        val attrs = serviceInfo.attributes
                        val peerId = attrs["peerId"]?.toString(Charsets.UTF_8)
                            ?: serviceInfo.serviceName.removePrefix("ExtDesk-")
                        val name = attrs["name"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName
                        val summary = attrs["summary"]?.toString(Charsets.UTF_8) ?: "Extended Screen"
                        val peer = MdnsResolvedPeer(
                            serviceName = serviceInfo.serviceName,
                            peerId = peerId,
                            deviceName = name,
                            ipAddress = hostAddr,
                            port = serviceInfo.port,
                            displaySummary = summary
                        )
                        val updated = _resolvedPeers.value
                            .filterNot { it.peerId == peer.peerId } + peer
                        _resolvedPeers.value = updated
                    }
                    resolving.set(false)
                    drainResolveQueue()
                }
            })
        } catch (_: Throwable) {
            resolving.set(false)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_extdesk._tcp."
    }
}
