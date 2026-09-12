package com.dylandos.iptv.ultimate.player.timeshift

import org.junit.Assert.*
import org.junit.Test

class TsClockTest {
    private fun packet(base: Long): ByteArray = ByteArray(188).apply {
        this[0] = 0x47; this[3] = 0x30; this[4] = 7; this[5] = 0x10
        this[6] = (base shr 25).toByte(); this[7] = (base shr 17).toByte()
        this[8] = (base shr 9).toByte(); this[9] = (base shr 1).toByte()
        this[10] = ((base and 1) shl 7).toByte()
    }
    @Test fun decodesUnsignedClock() {
        for (base in listOf(0L, 90L, 900_000L, (1L shl 33) - 1)) {
            assertEquals(base / 90, TsClock.pcrMs(packet(base)))
        }
    }
    @Test fun rejectsMissingAndInvalidAdaptation() {
        assertNull(TsClock.pcrMs(ByteArray(5)))
        assertNull(TsClock.pcrMs(packet(90).apply { this[3] = 0x10 }))
        assertNull(TsClock.pcrMs(packet(90).apply { this[4] = 6 }))
        assertNull(TsClock.pcrMs(packet(90).apply { this[4] = 184.toByte() }))
        assertNull(TsClock.pcrMs(packet(90).apply { this[5] = 0 }))
    }
    @Test fun identifiesOnlyPatPid() {
        assertTrue(TsClock.isPat(packet(0).apply { this[1] = 0x40 }))
        assertFalse(TsClock.isPat(packet(0).apply { this[2] = 1 }))
        assertFalse(TsClock.isPat(packet(0).apply { this[1] = 1 }))
    }
}
