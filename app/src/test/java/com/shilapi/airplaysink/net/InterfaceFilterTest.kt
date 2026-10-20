package com.shilapi.airplaysink.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which interfaces may contribute addresses to the AirPlay 2 `timingPeerInfo` answer.
 *
 * shairport-sync walks `getifaddrs()` without filtering, and gets away with it: the hosts it runs on
 * have a single network interface, so the walk only ever sees the link the sender is on. A phone is
 * not that. It is normally carrying a SIM as well as Wi-Fi, so the walk turns up a global unicast
 * address on `rmnet_ims10` — a different network, several hops away, unreachable from the peer.
 *
 * Putting that in the answer is worse than leaving it out. The sender matches our list against the
 * address it reached us on and drives PTP over what it finds, so an address from the cellular network
 * is an entry it will pick and then wait on forever.
 */
class InterfaceFilterTest {

    @Test
    fun `cellular interfaces are excluded`() {
        // The real names on a dual-SIM handset.
        assertFalse(LocalNetwork.carriesAirPlay("rmnet_ims00"))
        assertFalse(LocalNetwork.carriesAirPlay("rmnet_ims10"))
        assertFalse(LocalNetwork.carriesAirPlay("ccmni0"))
    }

    @Test
    fun `tunnel interfaces are excluded`() {
        // A VPN's address is just as unreachable as the cellular one, and the AirPlay link is never
        // the tunnel — the sender is on the far side of it.
        assertFalse(LocalNetwork.carriesAirPlay("tun0"))
        assertFalse(LocalNetwork.carriesAirPlay("ppp0"))
        assertFalse(LocalNetwork.carriesAirPlay("ipsec0"))
    }

    @Test
    fun `mirrors of the wifi interface are excluded`() {
        // Wi-Fi Direct and the dummy bridge repeat wlan0's addresses under other names; carrying
        // them costs bytes and tells the sender nothing it did not already have.
        assertFalse(LocalNetwork.carriesAirPlay("p2p-iwlan0-0"))
        assertFalse(LocalNetwork.carriesAirPlay("dummy0"))
    }

    @Test
    fun `the wifi interface itself is kept`() {
        // Including the head unit's own hotspot, which is named ap* and is exactly the case this
        // receiver has to work on.
        assertTrue(LocalNetwork.carriesAirPlay("wlan0"))
        assertTrue(LocalNetwork.carriesAirPlay("swlan0"))
        assertTrue(LocalNetwork.carriesAirPlay("ap0"))
        assertTrue(LocalNetwork.carriesAirPlay("eth0"))
    }

    @Test
    fun `interface names are matched case insensitively`() {
        // Android hands back lower case today; this keeps the filter from being a name-format
        // dependency if that ever changes.
        assertFalse(LocalNetwork.carriesAirPlay("RMNET_IMS10"))
        assertTrue(LocalNetwork.carriesAirPlay("WLAN0"))
    }
}
