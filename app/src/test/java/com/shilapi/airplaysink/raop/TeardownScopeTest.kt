package com.shilapi.airplaysink.raop

import com.shilapi.airplaysink.ap2.BPlist
import com.shilapi.airplaysink.ap2.BPlistReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TEARDOWN` carries two meanings under one name, and the body is the only thing that tells them
 * apart.
 *
 * The phone sends the stream form on every pause. Reading it as "the session is over" closes the
 * control connection, which is why play after pause used to do nothing: the command had no channel
 * left. These cases pin the reading so it cannot quietly flip back.
 */
class TeardownScopeTest {

    private fun parse(plist: Map<String, Any?>): Any? = BPlistReader.parse(BPlist.write(plist))

    @Test
    fun `a plist naming streams only ends the stream`() {
        val body = parse(mapOf("streams" to listOf(mapOf("streamID" to 1234L))))
        assertTrue(teardownEndsOnlyTheStream(body))
    }

    @Test
    fun `an empty plist ends the session`() {
        assertFalse(teardownEndsOnlyTheStream(parse(emptyMap())))
    }

    @Test
    fun `a plist with other keys but no streams ends the session`() {
        assertFalse(teardownEndsOnlyTheStream(parse(mapOf("reason" to 1L))))
    }

    @Test
    fun `a body that is not a dictionary ends the session`() {
        // Classic RAOP sends no body at all, and an unreadable one must not be guessed at: closing
        // the session is the safe side, because a wrong "keep it open" leaks a connection that the
        // phone will never speak on again.
        assertFalse(teardownEndsOnlyTheStream(null))
        assertFalse(teardownEndsOnlyTheStream("not a plist"))
    }
}
