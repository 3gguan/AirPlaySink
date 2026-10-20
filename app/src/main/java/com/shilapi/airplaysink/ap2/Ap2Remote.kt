package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.dacp.DacpClient
import java.util.UUID

/**
 * AirPlay 2's own remote control: `sendMediaRemoteCommand` on the encrypted event connection.
 *
 * This is the path shairport-sync uses (its `remote/remote.c`), and unlike the media HID experiment
 * in [Ap2MediaHid] it needs no HID device, no display and no MFi identity — only the event channel
 * every AirPlay 2 session already opens. The command numbers are shairport-sync's `simple_command_t`
 * values, which its header documents as "the command number used in the actual AP2 remote control
 * command".
 *
 * The message carries a `params` dictionary that names the AirPlay *group* the command is meant for.
 * That value is an NSKeyedArchiver archive — a plist inside a plist — which is why [destinationArchive]
 * exists rather than a plain string field.
 */
object Ap2Remote {

    /*
     * `simple_command_t` in shairport-sync's remote/remote.h. The enum values are the wire numbers,
     * not arbitrary ids: 0 play, 1 pause, 2 play/pause, 3 stop, 4 next, 5 previous.
     */
    const val PLAY = 0
    const val PAUSE = 1
    const val PLAY_PAUSE = 2
    const val STOP = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /*
     * Seeking is a scan, not a step: start it, then stop it. These four are the last of
     * shairport-sync's `simple_command_t`, the range its header documents as "the command number
     * used in the actual AP2 remote control command", so unlike the numbers past 11 they are
     * known-good wire values. 8/9 forward, 10/11 backward.
     *
     * An earlier version sent the pair Apple's `MRCommand` calls `kMRGoBackFifteenSeconds` (12) and
     * `kMRSkipFifteenSeconds` (13), guessing the decode from that enum. Measured 2026-10-20 on the
     * iPhone: every one was acknowledged with `RTSP/1.0 200` and nothing at all happened — eight
     * presses, no movement. Acknowledgement on this channel means "received", not "executed", so a
     * 200 is not evidence the number is understood; the four below are.
     */
    const val SCAN_FORWARD = 8
    const val SCAN_FORWARD_STOP = 9
    const val SCAN_BACKWARD = 10
    const val SCAN_BACKWARD_STOP = 11

    fun number(command: DacpClient.Command): Int? = when (command) {
        DacpClient.Command.PLAY -> PLAY
        DacpClient.Command.PAUSE -> PAUSE
        DacpClient.Command.PLAY_PAUSE -> PLAY_PAUSE
        DacpClient.Command.STOP -> STOP
        DacpClient.Command.NEXT -> NEXT
        DacpClient.Command.PREVIOUS -> PREVIOUS
        DacpClient.Command.SEEK_FORWARD -> SCAN_FORWARD
        DacpClient.Command.SEEK_FORWARD_STOP -> SCAN_FORWARD_STOP
        DacpClient.Command.SEEK_BACKWARD -> SCAN_BACKWARD
        DacpClient.Command.SEEK_BACKWARD_STOP -> SCAN_BACKWARD_STOP
    }

    fun label(number: Int): String = when (number) {
        PLAY -> "play"
        PAUSE -> "pause"
        PLAY_PAUSE -> "play/pause"
        STOP -> "stop"
        NEXT -> "next"
        PREVIOUS -> "previous"
        SCAN_FORWARD -> "scan forward"
        SCAN_FORWARD_STOP -> "stop scanning forward"
        SCAN_BACKWARD -> "scan backward"
        SCAN_BACKWARD_STOP -> "stop scanning backward"
        else -> "command $number"
    }

    /** The whole `POST /command` body. [groupId] is the session's `groupUUID`. */
    fun command(number: Int, groupId: String): Map<String, Any?> = linkedMapOf(
        "type" to "sendMediaRemoteCommand",
        // A string, not a number: Apple's player reads this field as text.
        "modernMediaRemoteCommand" to number.toString(),
        "params" to linkedMapOf<String, Any?>(
            "kMRMediaRemoteOptionSendOptionsNumber" to 0L,
            "kMRMediaRemoteOptionCommandID" to UUID.randomUUID().toString(),
            "kMRMediaRemoteOptionIsRedirectingCommand" to true,
            "kMRMediaRemoteOptionDestinationDeviceUIDs" to destinationArchive(groupId),
        ),
    )

    /**
     * `kMRMediaRemoteOptionDestinationDeviceUIDs`: an archived `NSMutableArray` holding one string.
     *
     * Rebuilt from shairport-sync's `prepareNSKeyedArchiver`: the object table is fixed (`$null`,
     * the array, the group UUID, the class description), so only the UUID varies. The bytes are a
     * binary plist nested inside the outer one's data field.
     */
    fun destinationArchive(groupId: String): ByteArray = BPlist.write(
        linkedMapOf<String, Any?>(
            "\$version" to 100_000L,
            "\$archiver" to "NSKeyedArchiver",
            "\$top" to linkedMapOf<String, Any?>(
                "root" to linkedMapOf<String, Any?>("CF\$UID" to 1L),
            ),
            "\$objects" to listOf(
                "\$null",
                linkedMapOf<String, Any?>(
                    "NS.objects" to listOf(linkedMapOf<String, Any?>("CF\$UID" to 2L)),
                    "\$class" to linkedMapOf<String, Any?>("CF\$UID" to 3L),
                ),
                groupId,
                linkedMapOf<String, Any?>(
                    "\$classname" to "NSMutableArray",
                    "\$classes" to listOf("NSMutableArray", "NSArray", "NSObject"),
                ),
            ),
        ),
    )
}
