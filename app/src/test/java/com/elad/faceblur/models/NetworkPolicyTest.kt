package com.elad.faceblur.models

import org.junit.Assert.assertEquals
import org.junit.Test

/** When a model download may start: the user's network rule against the network now. */
class NetworkPolicyTest {
    private val mb = 1_000_000L

    private fun d(rule: NetworkRule, net: Net, bytes: Long, mobileOk: Boolean = false, asking: Boolean = true) =
        NetworkPolicy.decide(rule, net, bytes, mobileOk, asking)

    @Test
    fun wifiAlwaysStarts() {
        assertEquals(NetDecision.START, d(NetworkRule(wifiOnly = true), Net.UNMETERED, 600 * mb))
        assertEquals(NetDecision.START, d(NetworkRule(askOverMb = 0), Net.UNMETERED, 600 * mb))
    }

    @Test
    fun noNetworkWaits() {
        assertEquals(NetDecision.WAIT_NETWORK, d(NetworkRule(), Net.NONE, 1))
        assertEquals(NetDecision.WAIT_NETWORK, d(NetworkRule(), Net.NONE, 1, mobileOk = true))
    }

    @Test
    fun mobileDataUnderTheLimitStarts() {
        val rule = NetworkRule(askOverMb = 50)
        assertEquals(NetDecision.START, d(rule, Net.METERED, 19 * mb))
        assertEquals(NetDecision.START, d(rule, Net.METERED, 50 * mb))
        assertEquals(NetDecision.ASK, d(rule, Net.METERED, 50 * mb + 1))
        assertEquals(NetDecision.ASK, d(rule, Net.METERED, 574 * mb))
    }

    @Test
    fun zeroAlwaysAsks() {
        assertEquals(NetDecision.ASK, d(NetworkRule(askOverMb = 0), Net.METERED, 1))
    }

    @Test
    fun wifiOnlyWaitsUnlessTheUserSaidSo() {
        val rule = NetworkRule(wifiOnly = true)
        assertEquals(NetDecision.WAIT_WIFI, d(rule, Net.METERED, 1))
        assertEquals(NetDecision.WAIT_WIFI, d(rule, Net.METERED, 1, asking = false))
        assertEquals(NetDecision.START, d(rule, Net.METERED, 600 * mb, mobileOk = true))
    }

    @Test
    fun aWaitingDownloadDoesNotAskByItself() {
        // Wi-Fi dropped to mobile data while a big download waited: it waits for Wi-Fi, no question pops up.
        assertEquals(NetDecision.WAIT_WIFI, d(NetworkRule(askOverMb = 50), Net.METERED, 100 * mb, asking = false))
        assertEquals(NetDecision.START, d(NetworkRule(askOverMb = 50), Net.METERED, 10 * mb, asking = false))
        assertEquals(NetDecision.START, d(NetworkRule(askOverMb = 50), Net.METERED, 100 * mb, mobileOk = true, asking = false))
    }

    @Test
    fun choices() {
        assertEquals(listOf(0, 25, 50, 100, 250), NetworkRule.CHOICES_MB)
        assertEquals(50, NetworkRule().askOverMb)
        assertEquals(false, NetworkRule().wifiOnly)
    }
}
