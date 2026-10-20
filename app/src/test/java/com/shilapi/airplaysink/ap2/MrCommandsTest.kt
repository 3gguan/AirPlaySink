package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.util.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complaint this decoder answers is "the button did nothing". A scan command is accepted with a
 * 200 whether or not the app behind it can scrub, so the only real evidence is the list the sender
 * publishes — and that list is a plist inside a plist, which is exactly the shape that breaks
 * silently.
 */
class MrCommandsTest {

    /**
     * The wire shape, captured from the phone rather than assumed: short key names, and the two
     * capability flags nested inside an options dictionary. An earlier version of this fixture used
     * the `kMRMediaRemoteCommandInfo*` names from Apple's headers, which the sender never sends —
     * the decoder happily returned zero commands for every app and the fixture agreed with it.
     */
    private fun entry(
        number: Int,
        enabled: Boolean = true,
        scrubbing: Boolean = false,
        position: Boolean = false,
    ): ByteArray = BPlist.write(
        linkedMapOf<String, Any?>(
            "kCommandInfoCommandKey" to number.toLong(),
            "kCommandInfoEnabledKey" to enabled,
            "kCommandInfoOptionsKey" to linkedMapOf<String, Any?>(
                "kMRMediaRemoteCommandInfoCanBeControlledByScrubbingKey" to scrubbing,
                "kMRMediaRemoteCommandInfoSupportsReferencePosition" to position,
            ),
        ),
    )

    /** One entry exactly as it arrived, hex and all — 253 bytes carrying command 24. */
    private val captured =
        "62706c6973743030d301020304090a5f10166b436f6d6d616e64496e666f4f7074696f6e734b65795f10166b436f6d" +
            "6d616e64496e666f436f6d6d616e644b65795f10166b436f6d6d616e64496e666f456e61626c65644b6579d20506" +
            "07075f10366b4d524d6564696152656d6f7465436f6d6d616e64496e666f43616e4265436f6e74726f6c6c65644279" +
            "536372756262696e674b65795f10326b4d524d6564696152656d6f7465436f6d6d616e64496e666f537570706f72" +
            "74735265666572656e6365506f736974696f6e0808101809080f28415a5f98cdcecfd10000000000000101000000" +
            "000000000b000000000000000000000000000000d2"

    @Test
    fun `reads the command numbers out of the nested bplists`() {
        val report = MrCommands.read(message(entry(0), entry(2), entry(4), entry(5)))!!
        assertEquals(setOf(0, 2, 4, 5), report.commands)
        assertFalse(report.scrubbing)
        // No scan numbers either: this app cannot be scrubbed by either signal.
        assertTrue(report.scrubbingUnlikely)
    }

    @Test
    fun `a sample captured from the phone decodes to its command number`() {
        val report = MrCommands.read(message(Codec.fromHex(captured)))!!
        assertEquals(setOf(24), report.commands)
        assertFalse(report.scrubbing)
        assertFalse(report.position)
    }

    @Test
    fun `an entry handed over as hex decodes the same way`() {
        val report = MrCommands.read(message(Codec.toHex(entry(4, scrubbing = true))))!!
        assertEquals(setOf(4), report.commands)
        assertTrue(report.scrubbing)
    }

    private fun message(vararg entries: Any): Map<String, Any?> = linkedMapOf(
        "type" to "updateMRSupportedCommands",
        "params" to linkedMapOf<String, Any?>(
            "mrSupportedCommandsFromSender" to entries.toList(),
        ),
    )

    @Test
    fun `a scrubbing flag clears the warning even without the scan numbers`() {
        val report = MrCommands.read(message(entry(0, scrubbing = true), entry(2)))!!
        assertTrue(report.scrubbing)
        assertFalse(report.scrubbingUnlikely)
    }

    @Test
    fun `the scan numbers alone also count as scrubbing`() {
        val report = MrCommands.read(message(entry(8), entry(10)))!!
        assertEquals(setOf(8, 10), report.commands)
        assertFalse(report.scrubbingUnlikely)
    }

    @Test
    fun `a disabled command is not counted`() {
        // A command listed but disabled means the same thing to us as one that is missing.
        val report = MrCommands.read(message(entry(8, enabled = false), entry(9)))!!
        assertEquals(setOf(9), report.commands)
        assertTrue(report.scrubbingUnlikely)
    }

    @Test
    fun `other command messages are left alone`() {
        val other = linkedMapOf<String, Any?>("type" to "sendMediaRemoteCommand")
        assertNull(MrCommands.read(other))
        assertNull(MrCommands.read(null))
        assertNull(MrCommands.read("not a plist"))
    }

    @Test
    fun `the summary names the buttons rather than the integers`() {
        val summary = MrCommands.read(message(entry(2), entry(4), entry(5), entry(8, scrubbing = true)))!!
            .describe()
        assertTrue(summary, summary.contains("play/pause next previous ff"))
        assertTrue(summary, summary.contains("scrubbing=yes"))
    }
}
