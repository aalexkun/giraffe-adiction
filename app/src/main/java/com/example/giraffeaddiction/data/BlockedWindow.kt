package com.example.giraffeaddiction.data

/**
 * A daily window in which an app is paused, as minutes of the local day. A window may cross
 * midnight (21:00 to 07:00): then it contains every minute from the start to the end of the day and
 * from the start of the day to the end.
 */
data class BlockedWindow(val startMinute: Int, val endMinute: Int) {

    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay >= startMinute && minuteOfDay < endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun encode(): String = "${fmt(startMinute)}-${fmt(endMinute)}"

    override fun toString(): String = "${fmt(startMinute)} to ${fmt(endMinute)}"

    companion object {
        fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

        fun parse(text: String): BlockedWindow? {
            val parts = text.trim().split('-')
            if (parts.size != 2) return null
            val start = parseMinute(parts[0]) ?: return null
            val end = parseMinute(parts[1]) ?: return null
            return BlockedWindow(start, end)
        }

        fun parseMinute(text: String): Int? {
            val hm = text.trim().split(':')
            if (hm.size != 2) return null
            val h = hm[0].toIntOrNull() ?: return null
            val m = hm[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }

        fun parseList(encoded: String): List<BlockedWindow> =
            encoded.split(';').mapNotNull { if (it.isBlank()) null else parse(it) }

        fun encodeList(windows: List<BlockedWindow>): String = windows.joinToString(";") { it.encode() }

        fun anyContains(windows: List<BlockedWindow>, minuteOfDay: Int): Boolean =
            windows.any { it.contains(minuteOfDay) }
    }
}
