package com.syncinit.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Read-only snapshot of Android's active network path.
 * Does not collect SSID, BSSID, MAC addresses, or session credentials.
 */
internal object NetworkDiagnostics {
    fun deviceSnapshot(): String = buildString {
        appendLine("device.manufacturer=${Build.MANUFACTURER}")
        appendLine("device.model=${Build.MODEL}")
        appendLine("device.product=${Build.PRODUCT}")
        appendLine("device.androidRelease=${Build.VERSION.RELEASE}")
        appendLine("device.api=${Build.VERSION.SDK_INT}")
        appendLine("device.abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    fun networkSnapshot(context: Context, peerAddress: String? = null): String = try {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val properties = network?.let(connectivity::getLinkProperties)

        buildString {
            appendLine("network.snapshot.begin")
            append(deviceSnapshot())
            appendLine("network.active=${network ?: "none"}")
            appendLine("network.transports=${capabilities?.let(::transports) ?: "unavailable"}")
            appendLine("network.internet=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: "unknown"}")
            appendLine("network.validated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: "unknown"}")
            appendLine("network.metered=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not() ?: "unknown"}")
            appendLine("network.interface=${properties?.interfaceName ?: "unknown"}")
            appendLine("network.linkAddresses=${properties?.linkAddresses?.joinToString(";") { formatAddress(it) } ?: "unavailable"}")
            appendLine("network.dns=${properties?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" } ?: "unavailable"}")
            appendLine("network.routes=${properties?.routes?.joinToString(";") { formatRoute(it.toString(), it.isDefaultRoute) } ?: "unavailable"}")
            appendLine("network.proxy=${properties?.httpProxy ?: "none-or-unavailable"}")
            appendLine("network.interfaces=${activeInterfaces()}")
            if (peerAddress != null) {
                appendLine("peer.address=$peerAddress")
                appendLine("peer.isOwnInterfaceAddress=${properties?.linkAddresses?.any { it.address.hostAddress == peerAddress } ?: "unknown"}")
                appendLine("peer.addressScope=${addressScope(peerAddress)}")
                appendLine("peer.subnetRelation=${subnetRelation(properties, peerAddress)}")
                appendLine("peer.routingHint=${routingHint(properties, peerAddress)}")
            }
            appendLine("network.snapshot.end")
        }
    } catch (error: Exception) {
        "network.snapshot.failed=${error.javaClass.name}: ${error.message ?: "no message"}"
    }

    fun exceptionDetails(error: Throwable): String {
        val causes = generateSequence(error as Throwable?) { it.cause }.take(6).toList()
        val chain = causes.mapIndexed { index, cause ->
            "#$index ${cause.javaClass.name}: ${cause.message ?: "no message"}"
        }.joinToString(" <- ")
        val root = causes.lastOrNull() ?: error
        val category = when (root) {
            is java.net.NoRouteToHostException -> "NO_ROUTE_TO_HOST"
            is java.net.ConnectException -> when {
                root.message.orEmpty().contains("refused", ignoreCase = true) -> "CONNECTION_REFUSED"
                else -> "CONNECTION_FAILED"
            }
            is java.net.SocketTimeoutException -> "CONNECT_TIMEOUT_OR_FILTERED"
            is java.net.UnknownHostException -> "HOST_RESOLUTION_FAILED"
            is java.net.BindException -> "LOCAL_BIND_FAILED"
            is SecurityException -> "PERMISSION_OR_POLICY_BLOCK"
            is java.net.SocketException -> "SOCKET_FAILURE"
            else -> "UNCLASSIFIED_IO_FAILURE"
        }
        return "category=$category; causeChain=$chain"
    }

    private fun transports(capabilities: NetworkCapabilities): String = buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        if (isEmpty()) add("OTHER_OR_NONE")
    }.joinToString(",")

    private fun formatAddress(linkAddress: LinkAddress): String =
        "${linkAddress.address.hostAddress}/${linkAddress.prefixLength}"

    private fun formatRoute(route: String, isDefault: Boolean): String =
        if (isDefault) "$route [DEFAULT]" else route

    private fun activeInterfaces(): String = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces == null) return "unavailable"
        interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .joinToString(";") { networkInterface ->
                val addresses = networkInterface.inetAddresses.toList()
                    .joinToString(",") { it.hostAddress ?: "?" }
                "${networkInterface.name}(mtu=${networkInterface.mtu},addresses=$addresses)"
            }
            .ifBlank { "no-up-non-loopback-interface" }
    } catch (error: Exception) {
        "unavailable(${error.javaClass.simpleName}:${error.message ?: "no message"})"
    }

    private fun addressScope(peerAddress: String): String {
        val address = parseIpv4(peerAddress) ?: return "UNKNOWN_NON_IPV4_OR_INVALID"
        return when {
            address.isAnyLocalAddress -> "UNSPECIFIED"
            address.isLoopbackAddress -> "LOOPBACK"
            address.isLinkLocalAddress -> "LINK_LOCAL"
            address.isSiteLocalAddress -> "PRIVATE_IPV4"
            address.isMulticastAddress -> "MULTICAST"
            else -> "PUBLIC_OR_OTHER"
        }
    }

    private fun subnetRelation(properties: LinkProperties?, peerAddress: String): String {
        val peer = parseIpv4(peerAddress) ?: return "UNKNOWN_NON_IPV4_OR_INVALID"
        val localAddresses = properties?.linkAddresses.orEmpty()
        if (localAddresses.isEmpty()) return "UNKNOWN_NO_ACTIVE_LINK_ADDRESSES"
        val matching = localAddresses.firstOrNull { link ->
            val local = link.address as? Inet4Address ?: return@firstOrNull false
            sameSubnet(local, peer, link.prefixLength)
        }
        return if (matching != null) {
            "SAME_SUBNET(${formatAddress(matching)})"
        } else {
            "DIFFERENT_SUBNET(default-route-or-policy-route-required)"
        }
    }

    private fun routingHint(properties: LinkProperties?, peerAddress: String): String {
        val peer = parseIpv4(peerAddress) ?: return "Cannot infer route: peer is not an IPv4 literal"
        val localAddresses = properties?.linkAddresses.orEmpty()
        val match = localAddresses.firstOrNull { link ->
            val local = link.address as? Inet4Address ?: return@firstOrNull false
            sameSubnet(local, peer, link.prefixLength)
        }
        if (match != null) {
            return "Peer falls inside ${formatAddress(match)}; Android should route directly on ${properties?.interfaceName ?: "the active interface"}"
        }
        val defaultRoute = properties?.routes?.firstOrNull { it.isDefaultRoute }
        return if (defaultRoute != null) {
            "Peer is outside active IPv4 interface prefixes; default route is $defaultRoute"
        } else {
            "No default route is visible on the active network"
        }
    }

    private fun parseIpv4(value: String): Inet4Address? =
        try { InetAddress.getByName(value) as? Inet4Address } catch (_: Exception) { null }

    private fun sameSubnet(local: Inet4Address, peer: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) return false
        val localValue = ipv4ToInt(local)
        val peerValue = ipv4ToInt(peer)
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (localValue and mask) == (peerValue and mask)
    }

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }
}
