package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.dacp.DacpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message shape is copied from shairport-sync's `remote/remote.c`; these tests pin the parts a
 * typo would silently break — the command number as a *string*, and the archived group UUID that
 * addresses the command to the right AirPlay group.
 */
class Ap2RemoteTest {

    private val groupId = "343F4AF7-9158-4466-AC59-250A1281FFD6"

    @Test
    fun `command numbers match shairport-sync's simple_command_t`() {
        assertEquals(0, Ap2Remote.number(DacpClient.Command.PLAY))
        assertEquals(1, Ap2Remote.number(DacpClient.Command.PAUSE))
        assertEquals(2, Ap2Remote.number(DacpClient.Command.PLAY_PAUSE))
        assertEquals(3, Ap2Remote.number(DacpClient.Command.STOP))
        assertEquals(4, Ap2Remote.number(DacpClient.Command.NEXT))
        assertEquals(5, Ap2Remote.number(DacpClient.Command.PREVIOUS))
    }

    @Test
    fun `seeking is a scan with a start and a stop number per direction`() {
        // The last four of shairport-sync's simple_command_t, which its header documents as the
        // numbers the real AP2 remote control command uses. Order matters: 9 stops a forward scan
        // and 11 a backward one, and pairing them wrongly leaves the phone winding forever.
        assertEquals(8, Ap2Remote.number(DacpClient.Command.SEEK_FORWARD))
        assertEquals(9, Ap2Remote.number(DacpClient.Command.SEEK_FORWARD_STOP))
        assertEquals(10, Ap2Remote.number(DacpClient.Command.SEEK_BACKWARD))
        assertEquals(11, Ap2Remote.number(DacpClient.Command.SEEK_BACKWARD_STOP))

        assertEquals("scan forward", Ap2Remote.label(Ap2Remote.SCAN_FORWARD))
        assertEquals("stop scanning backward", Ap2Remote.label(Ap2Remote.SCAN_BACKWARD_STOP))
    }

    @Test
    fun `no command number is reused`() {
        // A copy-paste in the mapping would make two different buttons do the same thing, and only
        // a listener would notice.
        val numbers = DacpClient.Command.values().map { Ap2Remote.number(it) }
        assertEquals(numbers.size, numbers.distinct().size)
    }

    @Test
    fun `command plist carries the fields the sender reads`() {
        val body = Ap2Remote.command(Ap2Remote.NEXT, groupId)
        assertEquals("sendMediaRemoteCommand", body["type"])
        // A string on the wire: Apple's player parses this field as text, not as an integer.
        assertEquals("4", body["modernMediaRemoteCommand"])

        @Suppress("UNCHECKED_CAST")
        val params = body["params"] as Map<String, Any?>
        assertEquals(0L, params["kMRMediaRemoteOptionSendOptionsNumber"])
        assertEquals(true, params["kMRMediaRemoteOptionIsRedirectingCommand"])
        assertTrue(params["kMRMediaRemoteOptionCommandID"] is String)

        // The archive has to survive a round trip through our own reader, or the phone gets bytes
        // that are a plist in name only.
        val archive = params["kMRMediaRemoteOptionDestinationDeviceUIDs"] as ByteArray
        @Suppress("UNCHECKED_CAST")
        val decoded = BPlistReader.parse(archive) as Map<String, Any?>
        assertEquals(100_000L, decoded["\$version"])
        assertEquals("NSKeyedArchiver", decoded["\$archiver"])
    }

    @Test
    fun `archived destination holds the group uuid in an NSMutableArray`() {
        @Suppress("UNCHECKED_CAST")
        val decoded = BPlistReader.parse(Ap2Remote.destinationArchive(groupId)) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val objects = decoded["\$objects"] as List<Any?>
        assertEquals("\$null", objects[0])

        @Suppress("UNCHECKED_CAST")
        val array = objects[1] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val members = array["NS.objects"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        assertEquals(2L, (members[0] as Map<String, Any?>)["CF\$UID"])

        // Object 2 is the payload: the group the command is addressed to.
        assertEquals(groupId, objects[2])

        @Suppress("UNCHECKED_CAST")
        val classDef = objects[3] as Map<String, Any?>
        assertEquals("NSMutableArray", classDef["\$classname"])
        assertEquals(listOf("NSMutableArray", "NSArray", "NSObject"), classDef["\$classes"])
    }
}
