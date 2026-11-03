package com.syncinit.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Selects the Wi-Fi network whose route table can reach a discovered peer.
 *
 * INTERNET and VALIDATED capabilities are deliberately not required: a phone hotspot or
 * LocalOnlyHotspot is a valid transport even when it has no upstream internet connection.
 */
internal object PeerNetworkSelector {
    data class Selection(
        val network: Network,
        val interfaceName: String,
        val matchedRoute: String,
        val hasInternet: Boolean,
        val isValidated: Boolean
    )

    fun select(context: Context, peerAddress: String): Selection {
        val peer = parseIpv4(peerAddress)
            ?: throw IOException("Discovered host address is not an IPv4 literal: $peerAddress")
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork

        data class Candidate(
            val network: Network,
            val interfaceName: String,
            val route: android.net.RouteInfo,
            val routePrefixLength: Int,
            val hasInternet: Boolean,
            val isValidated: Boolean
        )

        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }

            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val route = properties.routes
                .asSequence()
                .filter { it.matches(peer) }
                .maxByOrNull { it.destination.prefixLength }
                ?: return@mapNotNull null

            Candidate(
                network = network,
                interfaceName = properties.interfaceName ?: "unknown",
                route = route,
                routePrefixLength = route.destination?.prefixLength ?: 0,
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }

        if (candidates.isEmpty()) {
            throw IOException(
                "No connected Wi-Fi network has a route to $peerAddress. " +
                    "Connect this phone to the host phone's hotspot and retry."
            )
        }

        val bestPrefix = candidates.maxOf { it.routePrefixLength }
        val best = candidates.filter { it.routePrefixLength == bestPrefix }
        val selected = when {
            best.size == 1 -> best.single()
            best.any { it.network == activeNetwork } -> best.first { it.network == activeNetwork }
            else -> throw IOException(
                "More than one Wi-Fi network has an equally specific route to $peerAddress. " +
                    "Disconnect the unused Wi-Fi/VPN path and retry."
            )
        }

        return Selection(
            network = selected.network,
            interfaceName = selected.interfaceName,
            matchedRoute = selected.route.toString(),
            hasInternet = selected.hasInternet,
            isValidated = selected.isValidated
        )
    }

    fun isLocalInterfaceAddress(context: Context, address: String): Boolean {
        val target = parseIpv4(address) ?: return false
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (connectivity.allNetworks.any { network ->
                connectivity.getLinkProperties(network)?.linkAddresses
                    ?.any { it.address == target } == true
            }
        ) {
            return true
        }

        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .any { networkInterface ->
                    networkInterface.inetAddresses.asSequence().any { it == target }
                }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseIpv4(value: String): Inet4Address? {
        if (!value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) return null
        return try {
            val parsed = InetAddress.getByName(value) as? Inet4Address ?: return null
            if (parsed.hostAddress == value) parsed else null
        } catch (_: Exception) {
            null
        }
    }
}
