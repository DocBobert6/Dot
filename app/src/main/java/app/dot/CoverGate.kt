package app.dot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art network gate (SHELL-SPEC §8.3). OPEN if and only
// if the network validated AND (unmetered Wi-Fi OR the mobile-data
// opt-in is on). No network never qualifies. A network that has not
// validated (captive portal) never opens the gate.
//
// VPN-aware: VPN over Wi-Fi counts as Wi-Fi — the active network
// is the VPN transport when a VPN is up, so the reader inspects
// the other currently-available networks for a validated Wi-Fi
// network underneath it. A remembered-but-disconnected Wi-Fi never
// appears in the available list, and the candidate must be
// validated too, so captive-portal Wi-Fi under a VPN that actually
// rides cellular is never claimed as Wi-Fi.
//
// The Android reader resolves the facts from ConnectivityManager
// and never throws (any failure to read is NO_NETWORK).

object CoverGate {

    enum class Gate {
        OPEN,
        NO_NETWORK,
        METERED_NO_OPT_IN,
        UNVALIDATED,
    }

    data class GateReading(val gate: Gate, val label: String)

    data class NetworkFacts(
        val hasNetwork: Boolean,
        val validated: Boolean,
        val isWifi: Boolean,
        val isCellular: Boolean,
        val isVpn: Boolean,
    )

    /** The gate for [facts] and the mobile-data opt-in. Pure. */
    fun gateFor(facts: NetworkFacts, mobileDataOptIn: Boolean): GateReading {
        if (!facts.hasNetwork) {
            return GateReading(Gate.NO_NETWORK, "no network")
        }
        if (!facts.validated) {
            return GateReading(
                Gate.UNVALIDATED,
                "network not validated (captive portal?)",
            )
        }
        // Unmetered: Wi-Fi, or anything that is neither cellular
        // nor a VPN (a VPN without a validated Wi-Fi underneath is
        // metered — it may ride cellular).
        val unmetered = facts.isWifi || (!facts.isCellular && !facts.isVpn)
        if (unmetered) {
            return GateReading(
                Gate.OPEN,
                if (facts.isWifi) "Wi-Fi OK" else "unmetered connection",
            )
        }
        return if (mobileDataOptIn) {
            GateReading(Gate.OPEN, "Mobile data OK")
        } else {
            GateReading(
                Gate.METERED_NO_OPT_IN,
                "metered connection — mobile data off",
            )
        }
    }

    /** Reads the facts from ConnectivityManager. Never throws. */
    fun readFacts(context: Context): NetworkFacts {
        return try {
            val cm = context.getSystemService(
                Context.CONNECTIVITY_SERVICE,
            ) as? ConnectivityManager
                ?: return NetworkFacts(false, false, false, false, false)
            val active = cm.activeNetwork ?: return NetworkFacts(
                false, false, false, false, false,
            )
            val caps = cm.getNetworkCapabilities(active)
                ?: return NetworkFacts(false, false, false, false, false)
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkFacts(false, false, false, false, false)
            }
            val validated = caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            )
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            var isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = caps.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR,
            )
            if (isVpn) {
                isWifi = cm.allNetworks.any { net ->
                    if (net == active) {
                        false
                    } else {
                        val c = try {
                            cm.getNetworkCapabilities(net)
                        } catch (_: Exception) {
                            null
                        }
                        c != null &&
                            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            c.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                            )
                    }
                }
            }
            NetworkFacts(true, validated, isWifi, isCellular, isVpn)
        } catch (_: Exception) {
            NetworkFacts(false, false, false, false, false)
        }
    }

    /** The gate, read live. Never throws. */
    fun read(context: Context, mobileDataOptIn: Boolean): GateReading {
        return gateFor(readFacts(context), mobileDataOptIn)
    }
}
