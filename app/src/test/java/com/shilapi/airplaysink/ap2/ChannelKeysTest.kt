package com.shilapi.airplaysink.ap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The HKDF strings for both AirPlay 2 connections, pinned as literals.
 *
 * This exists because of a bug that looked like something else entirely: the event connection was
 * keyed with the mirror of the control connection (write with the *read* key), every frame out
 * failed its Poly1305 check on the phone, and iOS answered by closing the event connection *and* the
 * control connection within tens of milliseconds. That reads exactly like "the phone refuses to be
 * driven", which cost a day of chasing the wrong thing. The two rows are not mirror images — the
 * asymmetry is in the protocol, from shairport-sync's `cipher_new(channel)`: channel 3 (control)
 * writes with the read key, channel 4 (events) writes with the write key.
 *
 * If someone ever "tidies" these into a loop over a prefix, these tests fail instead of the audio.
 */
class ChannelKeysTest {

    @Test
    fun `control channel writes with the read key and reads with the write key`() {
        val keys = ChannelKeys.CONTROL
        assertEquals("Control-Salt", keys.salt)
        assertEquals("Control-Read-Encryption-Key", keys.write)
        assertEquals("Control-Write-Encryption-Key", keys.read)
    }

    @Test
    fun `event channel writes with the write key and reads with the read key`() {
        val keys = ChannelKeys.EVENTS
        assertEquals("Events-Salt", keys.salt)
        assertEquals("Events-Write-Encryption-Key", keys.write)
        assertEquals("Events-Read-Encryption-Key", keys.read)
    }

    @Test
    fun `the event channel is not the control layout with a different salt`() {
        // The original bug in one line: events was derived by swapping only the salt, which turns
        // control's proven-correct write key into "Events-Read-Encryption-Key". If that ever equals
        // what the event channel writes with again, the phone will start dropping sessions again.
        assertNotEquals(
            "events must not write with the mirrored control key",
            ChannelKeys.CONTROL.write.replace("Control", "Events"),
            ChannelKeys.EVENTS.write,
        )
        assertEquals("Control-Read-Encryption-Key", ChannelKeys.CONTROL.write)
        assertEquals("Events-Read-Encryption-Key", ChannelKeys.CONTROL.write.replace("Control", "Events"))
    }
}
