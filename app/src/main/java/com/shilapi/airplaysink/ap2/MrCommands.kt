package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.util.Codec

/**
 * Decodes `POST /command {type: updateMRSupportedCommands}`, the message in which the iPhone tells
 * the receiver what the *currently playing app* can and cannot be asked to do.
 *
 * The list is the answer to a question the buttons keep raising: a scan command (8/9, 10/11) is
 * accepted with `RTSP/1.0 200` whether or not the app behind it can scrub, so a press that does
 * nothing looks identical to a press that worked. The sender publishes the difference up front, per
 * command, including `CanBeControlledByScrubbing` — for a receiver that cannot see the screen, that
 * flag is the only way to know that the button is being ignored on purpose rather than lost.
 *
 * The payload is nested twice: the plist carries an array of **hex-encoded** bplists, one per
 * command, each a small dictionary of `kMRMediaRemoteCommandInfo*` keys. Both layers are decoded
 * here so the caller gets plain numbers.
 */
internal object MrCommands {

    private const val TYPE = "updateMRSupportedCommands"

    /**
     * Key names as they actually appear on the wire.
     *
     * The `kMRMediaRemoteCommandInfo*` spelling is what Apple's headers call these, but the sender
     * sends the short form (`kCommandInfoCommandKey`) and nests the two flags inside an options
     * dictionary. Reading the header names off the wire found nothing at all, which is why every
     * app looked like it supported zero commands.
     */
    private const val COMMAND_KEY = "kCommandInfoCommandKey"
    private const val ENABLED_KEY = "kCommandInfoEnabledKey"
    private const val OPTIONS_KEY = "kCommandInfoOptionsKey"
    private const val SCRUBBING_KEY = "kMRMediaRemoteCommandInfoCanBeControlledByScrubbingKey"
    private const val POSITION_KEY = "kMRMediaRemoteCommandInfoSupportsReferencePosition"

    /** Older / longer spellings, kept because the two namespaces overlap between iOS versions. */
    private val COMMAND_ALIASES = listOf(COMMAND_KEY, "kMRMediaRemoteCommandInfoCommandKey")
    private val ENABLED_ALIASES = listOf(ENABLED_KEY, "kMRMediaRemoteCommandInfoEnabledKey")
    private val SCRUBBING_ALIASES = listOf(
        SCRUBBING_KEY,
        "kMRMediaRemoteCommandInfoCanBeControlledByScrubbing",
    )
    private val POSITION_ALIASES = listOf(POSITION_KEY, "${POSITION_KEY}Key")

    /** The numbers this receiver can send, so the summary reads as buttons rather than integers. */
    private val NAMES = linkedMapOf(
        0 to "play",
        1 to "pause",
        2 to "play/pause",
        3 to "stop",
        4 to "next",
        5 to "previous",
        8 to "ff",
        9 to "ff-stop",
        10 to "rew",
        11 to "rew-stop",
    )

    /**
     * What the app said about itself.
     *
     * [commands] holds the enabled command numbers only; a command the app lists as disabled is
     * indistinguishable from one it never mentioned, and both mean "do not bother".
     */
    data class Report(
        val commands: Set<Int>,
        val scrubbing: Boolean,
        val position: Boolean,
    ) {
        fun describe(): String {
            val named = NAMES.filterKeys { it in commands }.values.toList()
            val rest = commands.count { it !in NAMES }
            val list = buildString {
                append(named.joinToString(" "))
                if (rest > 0) {
                    if (isNotEmpty()) append(' ')
                    append("+$rest other")
                }
                if (isEmpty()) append("none")
            }
            return "app lists ${commands.size} commands [$list]; " +
                "scrubbing=${if (scrubbing) "yes" else "no"}, " +
                "position=${if (position) "yes" else "no"}"
        }

        /** True when the app never claimed it can be scrubbed — i.e. a scan press will do nothing. */
        val scrubbingUnlikely: Boolean get() = !scrubbing && 8 !in commands && 10 !in commands
    }

    /** Null when [plist] is not one of these messages, or carries nothing that can be read. */
    fun read(plist: Any?): Report? {
        val root = plist as? Map<*, *> ?: return null
        if (root["type"] != TYPE) return null
        val params = root["params"] as? Map<*, *> ?: return null
        val entries = params["mrSupportedCommandsFromSender"] as? List<*> ?: return null

        val commands = mutableSetOf<Int>()
        var scrubbing = false
        var position = false
        for (entry in entries) {
            val decoded = decodeEntry(entry) ?: continue
            val options = decoded[OPTIONS_KEY] as? Map<*, *>
            val number = (firstOf(decoded, COMMAND_ALIASES) as? Number)?.toInt() ?: continue
            if (firstOf(decoded, ENABLED_ALIASES) == false) continue
            commands += number
            if (flag(decoded, options, SCRUBBING_ALIASES)) scrubbing = true
            if (flag(decoded, options, POSITION_ALIASES)) position = true
        }
        return Report(commands, scrubbing, position)
    }

    /** One entry is a bplist, sometimes handed over as binary data and sometimes as a hex string. */
    private fun decodeEntry(entry: Any?): Map<*, *>? {
        val bytes = when (entry) {
            is ByteArray -> entry
            is String -> runCatching { Codec.fromHex(entry) }.getOrNull()
            else -> null
        } ?: return null
        return runCatching { BPlistReader.parse(bytes) }.getOrNull() as? Map<*, *>
    }

    private fun firstOf(map: Map<*, *>, names: List<String>): Any? {
        for (name in names) map[name]?.let { return it }
        return null
    }

    /** Reads a boolean that may sit on the entry itself or inside its options dictionary. */
    private fun flag(map: Map<*, *>, options: Map<*, *>?, names: List<String>): Boolean =
        firstOf(map, names) == true || (options != null && firstOf(options, names) == true)
}
