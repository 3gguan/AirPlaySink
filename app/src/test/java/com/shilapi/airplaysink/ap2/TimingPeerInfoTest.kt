package com.shilapi.airplaysink.ap2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `timingPeerInfo` answer to the initial SETUP.
 *
 * The symptom this file was written for looked like a refusal and was not one: the phone connected,
 * paired, opened the event channel, and then closed the control connection about a millisecond later
 * — before sending anything at all.
 *
 * What decides the outcome turned out to be neither the address family nor the number of addresses,
 * which is what the first two versions of this file assumed. Both of those rules fit part of the
 * data and were killed by a counterexample:
 *
 * - "Name the address the connection arrived on." Four handshakes in which the phone reached us
 *   over IPv4, the answer named that same IPv4, and all four were dropped a millisecond after the
 *   event channel opened.
 * - "Name several addresses and let the sender pick." Four handshakes carrying every interface
 *   address: none of them went on to `RECORD`, against nine for nine on a single link-local entry.
 *
 * What survives is one address, and it is the receiver's own **IPv6 link-local** address:
 *
 * | named in `timingPeerInfo` | handshakes | reached `RECORD` |
 * |---|---|---|
 * | link-local (`fe80::…%wlan0`) | 11 | **11** |
 * | global unicast | 17 | 2 |
 * | IPv4 | 4 | 0 |
 * | every interface address | 4 | 0 |
 *
 * The two global-unicast successes were on the phone's own Wi-Fi, where the address came from the
 * router. The fifteen failures were the same address family handed out by the phone's own hotspot,
 * where the address belongs to the phone's cellular prefix and the phone's route for that prefix
 * goes out of the radio.
 *
 * The address family is the load-bearing part. AirPlay 2's timing is PTP, and PTP runs over IPv6
 * link-local multicast: a sender handed an IPv4 or a global address has nothing it can synchronize
 * against, and it drops the session rather than fall back. The link-local address is also the one
 * address guaranteed to be reachable on the link the sender is already on — which is what makes it
 * the right answer for a sender that may have connected to us over an address of its own choosing.
 *
 * The zone stays on the address. `fe80::…` without one names no interface, and the sender has no way
 * to guess which was meant.
 */
class TimingPeerInfoTest {

    private val interfaceAddresses = listOf(
        "fe80::101f:5dff:feca:4d78%wlan0",
        "172.20.10.2",
        "2408:8411:a892:8857:3f7d:691e:9150:3cec",
    )

    @Test
    fun `the link-local address is named`() {
        // The one shape that worked, every time it was tried.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals("fe80::101f:5dff:feca:4d78%wlan0", answer["ID"])
    }

    @Test
    fun `only one address is named`() {
        // Naming several is not "more information for the sender": it picks one, and any address from
        // another network is one it will pick and then wait on forever. Four handshakes carrying every
        // interface address went on to RECORD zero times.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals(listOf("fe80::101f:5dff:feca:4d78%wlan0"), answer["Addresses"])
    }

    @Test
    fun `the arrival address does not change the answer`() {
        // The phone picks where to connect, and it may pick an address that is fine for TCP and
        // useless for timing — a global address out of its own cellular prefix, which it routes over
        // the radio. Naming that address reproduces the drop, so the answer must not depend on it.
        val arrivedOnHotspotGlobal = "2408:8411:a892:8857:95c4:2630:831a:889c"
        val answer = timingPeerInfoAnswer(arrivedOnHotspotGlobal, interfaceAddresses)
        assertEquals("fe80::101f:5dff:feca:4d78%wlan0", answer["ID"])
        assertEquals(listOf("fe80::101f:5dff:feca:4d78%wlan0"), answer["Addresses"])
    }

    @Test
    fun `an IPv4 arrival still lands on the link-local address`() {
        // The cleanest counterexample to "name the arrival address": the phone reached us over IPv4,
        // the answer named that IPv4, and all four such sessions were dropped a millisecond after the
        // event channel opened.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals("fe80::101f:5dff:feca:4d78%wlan0", answer["ID"])
    }

    @Test
    fun `the link-local address keeps its zone`() {
        // fe80:: with no zone is not a usable address on a multi-interface host, and the sender has no
        // way to guess which interface it meant.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals(
            "fe80::101f:5dff:feca:4d78%wlan0",
            answer["ID"],
        )
    }

    @Test
    fun `a host with no link-local falls back to the arrival address`() {
        // The link-local address is the rule because AirPlay 2 timing needs one, but a host that has
        // none must still answer — an IPv6-only link, or a scan that came back empty.
        val v6only = listOf("2408:8411:a892:8857::1", "172.20.10.2")
        val answer = timingPeerInfoAnswer("2408:8411:a892:8857::1", v6only)
        assertEquals("2408:8411:a892:8857::1", answer["ID"])
        assertEquals(listOf("2408:8411:a892:8857::1"), answer["Addresses"])
    }

    @Test
    fun `a host with no other addresses still answers`() {
        // The interface scan can come back empty — an interface walk that fails, a ROM that hides them.
        // The answer must not depend on it.
        val answer = timingPeerInfoAnswer("172.20.10.2", emptyList())
        assertEquals("172.20.10.2", answer["ID"])
        assertEquals(listOf("172.20.10.2"), answer["Addresses"])
    }
}
