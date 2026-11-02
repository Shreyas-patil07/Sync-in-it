package com.syncinit.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

data class NearbySession(
    val serviceName: String,
    val hostAddress: String,
    val controlPort: Int,
    val sessionToken: String
)

/** Advertises an active host through Android DNS-SD/mDNS on the current local network. */
class LanSessionAdvertiser(
    context: Context,
    private val onStatus: (String) -> Unit
) : Closeable {
    companion object {
        const val SERVICE_TYPE = "_syncinit._tcp."
        private const val APP_ID = "syncinit"
    }

    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val closed = AtomicBoolean(false)
    @Volatile private var listener: NsdManager.RegistrationListener? = null
    @Volatile private var registered = false

    fun start(displayName: String, controlPort: Int, sessionToken: String) {
        check(!closed.get()) { "session advertiser is closed" }
        require(controlPort in 1..65_535)
        require(sessionToken.matches(Regex("[0-9A-Fa-f]{32}"))) {
            "session token must contain exactly 32 hexadecimal characters"
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName.take(55)
            serviceType = SERVICE_TYPE
            port = controlPort
            setAttribute("app", APP_ID.toByteArray(Charsets.US_ASCII))
            setAttribute("ver", byteArrayOf('1'.code.toByte()))
            // The token is visible to devices on this LAN. Use only a trusted private network.
            setAttribute("token", sessionToken.uppercase().toByteArray(Charsets.US_ASCII))
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (closed.get()) {
                    try { manager.unregisterService(this) } catch (_: IllegalArgumentException) { }
                    return
                }
                registered = true
                onStatus("Nearby discovery active · ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                onStatus("Host is running, but nearby discovery failed (NSD error $errorCode).")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onStatus("Could not stop nearby advertisement (NSD error $errorCode).")
            }
        }
        listener = registration
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = listener
        listener = null
        if (current != null && registered) {
            registered = false
            try {
                manager.unregisterService(current)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

/** Browses Sync In It sessions and resolves their address, port and join credential. */
class LanSessionBrowser(
    context: Context,
    private val onSessionFound: (NearbySession) -> Unit,
    private val onSessionLost: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : Closeable {
    companion object {
        private const val APP_ID = "syncinit"
        private const val MIN_TOKEN_LENGTH = 32
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val closed = AtomicBoolean(false)
    private val resolving = HashSet<String>()
    private val resolved = HashSet<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "session browser is closed" }
        if (discoveryListener != null) return

        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("Searching this Wi-Fi network for Sync In It hosts…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (closed.get() || serviceInfo.serviceName.isBlank()) return
                val key = serviceInfo.serviceName
                synchronized(this@LanSessionBrowser) {
                    if (key in resolving || key in resolved) return
                    resolving += key
                }
                try {
                    manager.resolveService(serviceInfo, createResolveListener(key))
                } catch (error: RuntimeException) {
                    synchronized(this@LanSessionBrowser) { resolving.remove(key) }
                    onStatus("Could not resolve nearby host $key.")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName
                synchronized(this@LanSessionBrowser) {
                    resolving.remove(key)
                    resolved.remove(key)
                }
                onSessionLost(key)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onStatus("Nearby search stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Nearby search failed (NSD error $errorCode). Check Wi-Fi and try again.")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Could not stop nearby search (NSD error $errorCode).")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(
                LanSessionAdvertiser.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (error: RuntimeException) {
            discoveryListener = null
            releaseMulticastLock()
            throw error
        }
    }

    private fun createResolveListener(key: String) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            synchronized(this@LanSessionBrowser) { resolving.remove(key) }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            synchronized(this@LanSessionBrowser) { resolving.remove(key) }
            if (closed.get()) return

            val attributes = serviceInfo.attributes
            val appId = attributes["app"]?.toString(Charsets.US_ASCII)
            val version = attributes["ver"]?.toString(Charsets.US_ASCII)
            val token = attributes["token"]?.toString(Charsets.US_ASCII)?.uppercase()
            val host = serviceInfo.host
            val address = (host as? Inet4Address)?.hostAddress

            if (appId != APP_ID || version != "1" || token == null ||
                token.length != MIN_TOKEN_LENGTH ||
                !token.all { it.digitToIntOrNull(16) != null } ||
                address.isNullOrBlank() ||
                serviceInfo.port !in 1..65_535
            ) {
                return
            }

            synchronized(this@LanSessionBrowser) {
                if (closed.get() || !resolved.add(key)) return
            }
            onSessionFound(
                NearbySession(
                    serviceName = serviceInfo.serviceName,
                    hostAddress = address,
                    controlPort = serviceInfo.port,
                    sessionToken = token
                )
            )
        }
    }

    @Synchronized
    private fun stopDiscovery() {
        val current = discoveryListener ?: return
        discoveryListener = null
        try {
            manager.stopServiceDiscovery(current)
        } catch (_: IllegalArgumentException) {
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("syncinit-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                try { lock.release() } catch (_: RuntimeException) { }
            }
        }
        multicastLock = null
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopDiscovery()
        resolving.clear()
        resolved.clear()
    }
}
