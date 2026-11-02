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
            setAttribute("app", APP_ID)
            setAttribute("ver", "1")
            // The token is visible to devices on this LAN. Use only a trusted private network.
            setAttribute("token", sessionToken.uppercase())
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (closed.get()) {
                    try { manager.unregisterService(this) } catch (_: IllegalArgumentException) { }
                    return
                }
                registered = true
                onStatus("DIAG NSD_ADVERTISEMENT_REGISTERED service=${serviceInfo.serviceName} type=$SERVICE_TYPE port=$controlPort")
                onStatus("Nearby discovery active · ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                onStatus("DIAG NSD_ADVERTISEMENT_FAILED service=${serviceInfo.serviceName} errorCode=$errorCode")
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
    private val pendingResolutions = LinkedHashMap<String, NsdServiceInfo>()
    private val unavailable = HashSet<String>()
    private val resolved = HashSet<String>()
    private var resolvingKey: String? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "session browser is closed" }
        if (discoveryListener != null) return

        acquireMulticastLock()
        onStatus("DIAG NSD_SCAN_BEGIN type=${LanSessionAdvertiser.SERVICE_TYPE} multicastLockHeld=${multicastLock?.isHeld == true}")
        onStatus(NetworkDiagnostics.networkSnapshot(appContext))
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("DIAG NSD_SCAN_STARTED type=$serviceType")
                onStatus("Searching this Wi-Fi network for Sync In It hosts…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (closed.get() || serviceInfo.serviceName.isBlank()) return
                onStatus("DIAG NSD_SERVICE_FOUND name=${serviceInfo.serviceName} type=${serviceInfo.serviceType} unresolvedPort=${serviceInfo.port}")
                val key = serviceInfo.serviceName
                synchronized(this@LanSessionBrowser) {
                    if (key in resolved || key == resolvingKey || key in pendingResolutions) return
                    unavailable.remove(key)
                    pendingResolutions[key] = serviceInfo
                    resolveNextLocked()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName
                onStatus("DIAG NSD_SERVICE_LOST name=$key")
                synchronized(this@LanSessionBrowser) {
                    unavailable += key
                    pendingResolutions.remove(key)
                    resolved.remove(key)
                }
                onSessionLost(key)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onStatus("Nearby search stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("DIAG NSD_SCAN_START_FAILED type=$serviceType errorCode=$errorCode")
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
            synchronized(this@LanSessionBrowser) {
                if (resolvingKey == key) resolvingKey = null
                resolveNextLocked()
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            synchronized(this@LanSessionBrowser) {
                if (resolvingKey == key) resolvingKey = null
                resolveNextLocked()
                if (closed.get() || key in unavailable) return
            }

            val attributes = serviceInfo.attributes
            val appId = attributes["app"]?.toString(Charsets.US_ASCII)
            val version = attributes["ver"]?.toString(Charsets.US_ASCII)
            val token = attributes["token"]?.toString(Charsets.US_ASCII)?.uppercase()
            val host = serviceInfo.host
            val address = (host as? Inet4Address)?.hostAddress
            val advertisedFingerprint = Regex("([0-9A-Fa-f]{4})(?: \\(\\d+\\))?$")
                .find(serviceInfo.serviceName)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase()

            val rejectionReason = when {
                appId != APP_ID -> "APP_ID_MISMATCH(actual=$appId expected=$APP_ID)"
                version != "1" -> "PROTOCOL_VERSION_MISMATCH(actual=$version expected=1)"
                token == null -> "TOKEN_ATTRIBUTE_MISSING"
                token.length != MIN_TOKEN_LENGTH -> "TOKEN_LENGTH_INVALID(actual=${token.length})"
                !token.all { it.digitToIntOrNull(16) != null } -> "TOKEN_NOT_HEX"
                advertisedFingerprint != null && !token.startsWith(advertisedFingerprint) -> "SERVICE_FINGERPRINT_MISMATCH"
                address.isNullOrBlank() -> "NO_IPV4_ADDRESS(host=${host ?: "null"})"
                serviceInfo.port !in 1..65_535 -> "INVALID_CONTROL_PORT(port=${serviceInfo.port})"
                else -> null
            }
            onStatus(
                "DIAG NSD_RESOLVED name=${serviceInfo.serviceName} address=${address ?: "none"} " +
                    "port=${serviceInfo.port} app=$appId version=$version tokenPresent=${token != null} " +
                    "fingerprint=${advertisedFingerprint ?: "none"}"
            )
            if (rejectionReason != null) {
                onStatus("DIAG NSD_RECORD_REJECTED name=${serviceInfo.serviceName} reason=$rejectionReason")
                onStatus("Ignored an invalid or stale nearby-host record: ${serviceInfo.serviceName}.")
                return
            }
            onStatus("DIAG NSD_PEER_ROUTE ${NetworkDiagnostics.networkSnapshot(appContext, address)}")

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
        pendingResolutions.clear()
        unavailable.clear()
        resolved.clear()
        resolvingKey = null
    }

    /** NsdManager limits outstanding resolutions; resolve one discovered host at a time. */
    private fun resolveNextLocked() {
        if (closed.get() || resolvingKey != null) return
        val next = pendingResolutions.entries.firstOrNull() ?: return
        pendingResolutions.remove(next.key)
        resolvingKey = next.key
        try {
            manager.resolveService(next.value, createResolveListener(next.key))
        } catch (error: RuntimeException) {
            resolvingKey = null
            onStatus("Could not resolve nearby host ${next.key}.")
            resolveNextLocked()
        }
    }
}
