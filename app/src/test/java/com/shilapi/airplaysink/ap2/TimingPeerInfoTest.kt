package com.shilapi.airplaysink.ap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `timingPeerInfo` answer to the initial SETUP.
 *
 * The symptom this file was written for looked like a refusal and was not one: the phone connected,
 * paired, opened the event channel, and then closed the control connection about a millisecond later
 * — before sending anything at all.
 *
 * What the log actually says, once every handshake in a long run is lined up against the address the
 * phone arrived on: which address family it picked decided the outcome, *within one prefix*. Nine
 * sessions where it arrived over IPv6 link-local all went on to `RECORD`; nineteen where it arrived
 * over the phone hotspot's global unicast address were all dropped at exactly that point. But global
 * unicast also worked — over a different prefix, earlier the same evening — so the address family
 * is not the cause either, and neither is the reply's size, `ClockID`, or the FairPlay mode.
 *
 * So the rule these tests hold is narrower than "name more addresses". It is: name every address
 * the sender could reach us on, and name nothing it could not. shairport-sync walks `getifaddrs()`
 * and gets this right by accident, because the hosts it runs on have one interface. A phone is
 * multi-homed — it is usually also carrying a SIM — and an address from the cellular network is
 * worse than no address at all, because the sender will try to run timing over it.
 *
 * The one-address version is smaller, reads tidier, and passes every other test in the suite, so
 * these are worth keeping.
 */
class TimingPeerInfoTest {

    private val interfaceAddresses = listOf(
        "fe80::101f:5dff:feca:4d78%wlan0",
        "172.20.10.2",
        "2408:8411:a892:8857:3f7d:691e:9150:3cec",
    )

    @Test
    fun `ID is the address the connection arrived on`() {
        // shairport-sync sets ID to self_ip_string — the local end of the connection being served,
        // not a separate identity of its own.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals("172.20.10.2", answer["ID"])
    }

    @Test
    fun `Addresses is never the connection address on its own`() {
        // This is the regression. With a single entry the phone either connected every time or not
        // at all, decided entirely by which address family it happened to reach us on.
        val answer = timingPeerInfoAnswer("fe80::101f:5dff:feca:4d78%wlan0", interfaceAddresses)
        val addresses = answer["Addresses"] as List<*>
        assertTrue(
            "timingPeerInfo.Addresses must name more than the connection address, got $addresses",
            addresses.size > 1,
        )
    }

    @Test
    fun `the connection address comes first`() {
        // It is the entry the sender is certain about, so it leads: shairport-sync appends
        // conn->self_ip_string before walking getifaddrs(), and this keeps that order.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertEquals("172.20.10.2", (answer["Addresses"] as List<*>).first())
    }

    @Test
    fun `every interface address is carried through`() {
        // If the phone picked one of ours and we did not name it, the session dies exactly the way
        // the head unit's did.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        val addresses = answer["Addresses"] as List<*>
        for (address in interfaceAddresses) {
            assertTrue("interface address $address is missing", addresses.contains(address))
        }
    }

    @Test
    fun `a link-local address keeps its zone`() {
        // fe80:: with no zone is not a usable address on a multi-interface host, and the sender has
        // no way to guess which interface it meant. hostAddress carries the zone and it has to
        // survive into the plist.
        val answer = timingPeerInfoAnswer("172.20.10.2", interfaceAddresses)
        assertTrue(
            "the link-local address must keep %wlan0",
            (answer["Addresses"] as List<*>).contains("fe80::101f:5dff:feca:4d78%wlan0"),
        )
    }

    @Test
    fun `the list holds no repeats`() {
        // Tethering mirrors repeat wlan0's addresses under a second interface name; sending the
        // duplicate costs bytes and tells the sender nothing new.
        val duplicated = listOf("172.20.10.2", "172.20.10.2", "fe80::1%wlan0")
        val answer = timingPeerInfoAnswer("172.20.10.2", duplicated)
        val addresses = answer["Addresses"] as List<*>
        assertEquals(addresses.size, addresses.toSet().size)
    }

    @Test
    fun `both address families appear when the host has both`() {
        // The measured failure was IPv6-global-only versus IPv6-link-local-only, so the case that
        // matters is both families being offered at once — which is what the phone matches against.
        val both = listOf(
            "2408:8411:a892:8857:3f7d:691e:9150:3cec",
            "fe80::101f:5dff:feca:4d78%wlan0",
            "172.20.10.2",
        )
        val answer = timingPeerInfoAnswer("2408:8411:a892:8857:3f7d:691e:9150:3cec", both)
        val addresses = answer["Addresses"] as List<*>
        assertTrue(addresses.contains("fe80::101f:5dff:feca:4d78%wlan0"))
        assertTrue(addresses.contains("172.20.10.2"))
    }
}
