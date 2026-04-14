package com.meshaudio.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

data class MeshBroadcast(val displayName: String, val host: String, val port: Int)

class MeshNsd(private val context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("meshaudio_mdns")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    fun register(port: Int, serviceName: String, onRegistered: (String) -> Unit, onFailed: (String) -> Unit) {
        unregister()
        val info =
            NsdServiceInfo().apply {
                this.serviceName = serviceName.take(63)
                serviceType = SERVICE_TYPE
                this.port = port
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registered: NsdServiceInfo) {
                    val name = registered.serviceName ?: serviceName
                    Log.i(TAG, "NSD registered $name on $port")
                    onRegistered(name)
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onFailed("NSD register failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
        registrationListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registrationListener = null
            onFailed(e.message ?: "registerService")
        }
    }

    fun unregister() {
        registrationListener?.let { l ->
            try {
                nsdManager.unregisterService(l)
            } catch (_: Exception) {
            }
        }
        registrationListener = null
    }

    fun startDiscovery(onFound: (MeshBroadcast) -> Unit) {
        stopDiscovery()
        acquireMulticastLock()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery start failed $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("meshaudio", ignoreCase = true)) return
                    val resolve =
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val legacyHost = resolved.host?.hostAddress
                                val host =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        resolved.hostAddresses?.firstOrNull()?.hostAddress ?: legacyHost
                                    } else {
                                        legacyHost
                                    }
                                val port = resolved.port
                                val name = resolved.serviceName ?: "mesh"
                                if (host != null && port > 0) {
                                    onFound(MeshBroadcast(name, host, port))
                                }
                            }
                        }
                    try {
                        nsdManager.resolveService(serviceInfo, resolve)
                    } catch (_: Exception) {
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { l ->
            try {
                nsdManager.stopServiceDiscovery(l)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    fun shutdown() {
        stopDiscovery()
        unregister()
    }

    companion object {
        private const val TAG = "MeshNsd"
        const val SERVICE_TYPE = "_meshaudio._tcp."
    }
}
