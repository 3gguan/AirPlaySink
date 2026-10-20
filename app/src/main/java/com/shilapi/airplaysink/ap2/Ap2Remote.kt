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

    fun number(command: DacpClient.Command): Int? = when (command) {
        DacpClient.Command.PLAY -> PLAY
        DacpClient.Command.PAUSE -> PAUSE
        DacpClient.Command.PLAY_PAUSE -> PLAY_PAUSE
        DacpClient.Command.STOP -> STOP
        DacpClient.Command.NEXT -> NEXT
        DacpClient.Command.PREVIOUS -> PREVIOUS
    }

    fun label(number: Int): String = when (number) {
        PLAY -> "play"
        PAUSE -> "pause"
        PLAY_PAUSE -> "play/pause"
        STOP -> "stop"
        NEXT -> "next"
        PREVIOUS -> "previous"
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
