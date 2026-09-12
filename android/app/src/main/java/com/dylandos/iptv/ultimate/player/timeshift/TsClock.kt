package com.dylandos.iptv.ultimate.player.timeshift

/** MPEG-TS PCR base, in milliseconds. Null for packets without a valid PCR. */
object TsClock {
    fun pcrMs(packet: ByteArray): Long? {
        if (packet.size != 188 || (packet[0].toInt() and 255) != 0x47) return null
        if ((packet[3].toInt() and 0x20) == 0 || (packet[4].toInt() and 255) < 7 || (packet[4].toInt() and 255) > 183 || (packet[5].toInt() and 0x10) == 0) return null
        fun b(i: Int) = packet[i].toLong() and 255
        val base = (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
        return base / 90
    }
    fun isPat(packet: ByteArray): Boolean = packet.size == 188 &&
        (packet[0].toInt() and 255) == 0x47 && (packet[1].toInt() and 0x1f) == 0 && packet[2].toInt() == 0
}
