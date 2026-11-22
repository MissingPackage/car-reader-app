package io.carreader.core.obd2.review

import io.carreader.core.link.BusProtocol
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.FakeLink
import io.carreader.core.obd2.Obd2Service
import io.carreader.core.obd2.StandardPids
import io.carreader.core.util.toHex
import io.carreader.core.vin.WmiTable
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Obd2ReviewTest {
    private fun ascii(text: String): String = text.encodeToByteArray().toHex()

    /** R1: engine has no freeze frame (0000), TCU has one. */
    @Test
    fun r1_freezeFrameFromSecondEcu() = runTest {
        val link = FakeLink()
            .on("02 02 00", 0x7E8, "42 02 00 00 00")
            .on("02 02 00", 0x7E9, "42 02 00 07 00")
        val ff = Obd2Service(link).freezeFrame()
        assertNotNull(ff, "TCU freeze frame lost because engine answered 0000 first")
    }

    /** R2: K-line 09 00 with message-number byte (J1979 non-CAN format, to be verified on a trace). */
    @Test
    fun r2_kLineInfoTypeBitmapWithSequenceByte() = runTest {
        val link = FakeLink(BusProtocol.ISO_9141_2)
            .on("09 00", 0x10, "49 00 01 55 00 00 00")
            .on("09 02", 0x10, "49 02 01 00 00 00 " + ascii("W"))
            .on("09 02", 0x10, "49 02 02 " + ascii("VWZZ"))
            .on("09 02", 0x10, "49 02 03 " + ascii("Z3CZ"))
            .on("09 02", 0x10, "49 02 04 " + ascii("JE12"))
            .on("09 02", 0x10, "49 02 05 " + ascii("3456"))
        val id = Obd2Service(link).identification()
        assertEquals("WVWZZZ3CZJE123456", id.vin, "sent=${link.sent}")
    }

    /** R3: uncoded engine ECU answers an all-zero VIN, TCU the real one. */
    @Test
    fun r3_zeroVinPreferredOverRealOne() = runTest {
        val link = FakeLink()
            .on("09 02", 0x7E8, "49 02 01 " + ascii("00000000000000000"))
            .on("09 02", 0x7E9, "49 02 01 " + ascii("WVWZZZ3CZJE123456"))
        assertEquals("WVWZZZ3CZJE123456", Obd2Service(link).identification().vin)
    }

    /** R4: CAN ECU that omits the 09 02 count byte. */
    @Test
    fun r4_canVinWithoutCountByte() = runTest {
        val link = FakeLink().on("09 02", 0x7E8, "49 02 " + ascii("WVWZZZ3CZJE123456"))
        assertEquals("WVWZZZ3CZJE123456", Obd2Service(link).identification().vin)
    }

    /** R5: common VW SUV / Spain WMIs. */
    @Test
    fun r5_vwWmis() {
        for (w in listOf("WVG", "VWV", "3VV")) assertEquals("vag", WmiTable.resolveWmi(w)?.groupId, w)
    }

    /** R6: fuzz every service with malformed answers on CAN, K-line and unknown bus: no exception. */
    @Test
    fun r6_fuzzNoCrash() = runTest {
        val rnd = Random(1979)
        val requests = mutableListOf("01 00", "01 20", "01 01", "03", "07", "0A", "02 02 00", "02 00 00", "04",
            "09 00", "09 02", "09 04", "09 06", "09 0A")
        for (p in StandardPids.all) requests += "01 " + p.pid.toString(16).padStart(2, '0')
        for (p in StandardPids.all) requests += "02 " + p.pid.toString(16).padStart(2, '0') + " 00"
        val failures = ArrayList<String>()
        for (bus in listOf(BusProtocol.CAN_11_500, BusProtocol.KWP_FAST, null)) {
            repeat(400) { iter ->
                val link = FakeLink(bus)
                for (req in requests) {
                    val reqBytes = req.split(" ").map { it.toInt(16) }
                    repeat(rnd.nextInt(0, 4)) { e ->
                        val len = rnd.nextInt(1, 12)
                        val bytes = IntArray(len) { i ->
                            when {
                                i == 0 -> reqBytes[0] + 0x40
                                i < reqBytes.size && rnd.nextInt(4) != 0 -> reqBytes[i]
                                else -> rnd.nextInt(256)
                            }
                        }
                        link.on(req, if (rnd.nextBoolean()) 0x7E8 + e else null, bytes.joinToString(" ") { it.toString(16).padStart(2, '0') })
                    }
                }
                val s = Obd2Service(link)
                try {
                    s.supportedPids(); s.readiness(); s.freezeFrame(); s.identification()
                    for (src in DtcSource.entries) s.readDtcs(src)
                    for (p in StandardPids.all) s.readPid(p)
                } catch (t: Throwable) {
                    failures += "bus=$bus iter=$iter: $t"
                }
            }
        }
        assertEquals(emptyList(), failures.take(5))
    }
}
