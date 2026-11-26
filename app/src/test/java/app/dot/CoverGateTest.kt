package app.dot

import org.junit.Assert.assertEquals
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art network gate tests (SHELL-SPEC §8.3). Pure.
class CoverGateTest {

    private fun facts(
        hasNetwork: Boolean = true,
        validated: Boolean = true,
        isWifi: Boolean = false,
        isCellular: Boolean = false,
        isVpn: Boolean = false,
    ) = CoverGate.NetworkFacts(hasNetwork, validated, isWifi, isCellular, isVpn)

    @Test
    fun noNetworkNeverQualifies() {
        val reading = CoverGate.gateFor(facts(hasNetwork = false), true)
        assertEquals(CoverGate.Gate.NO_NETWORK, reading.gate)
        assertEquals("no network", reading.label)
    }

    @Test
    fun unvalidatedNeverOpens() {
        val reading = CoverGate.gateFor(facts(validated = false), true)
        assertEquals(CoverGate.Gate.UNVALIDATED, reading.gate)
    }

    @Test
    fun wifiOpens() {
        val reading = CoverGate.gateFor(facts(isWifi = true), false)
        assertEquals(CoverGate.Gate.OPEN, reading.gate)
        assertEquals("Wi-Fi OK", reading.label)
    }

    @Test
    fun cellularNeedsTheOptIn() {
        assertEquals(
            CoverGate.Gate.METERED_NO_OPT_IN,
            CoverGate.gateFor(facts(isCellular = true), false).gate,
        )
        val reading = CoverGate.gateFor(facts(isCellular = true), true)
        assertEquals(CoverGate.Gate.OPEN, reading.gate)
        assertEquals("Mobile data OK", reading.label)
    }

    @Test
    fun vpnOverWifiCountsAsWifi() {
        val reading = CoverGate.gateFor(
            facts(isVpn = true, isWifi = true),
            false,
        )
        assertEquals(CoverGate.Gate.OPEN, reading.gate)
        assertEquals("Wi-Fi OK", reading.label)
    }

    @Test
    fun vpnWithoutWifiIsMetered() {
        assertEquals(
            CoverGate.Gate.METERED_NO_OPT_IN,
            CoverGate.gateFor(facts(isVpn = true), false).gate,
        )
    }
}
