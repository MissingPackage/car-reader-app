package io.carreader.app

import io.carreader.app.transport.BleUartProfile
import io.carreader.app.transport.BleUartProfile.PROPERTY_NOTIFY
import io.carreader.app.transport.BleUartProfile.PROPERTY_WRITE
import io.carreader.app.transport.BleUartProfile.PROPERTY_WRITE_NO_RESPONSE
import io.carreader.app.transport.BleUartProfile.short
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.DeviceKind
import io.carreader.app.transport.DeviceRanking
import io.carreader.app.transport.GattCharInfo
import io.carreader.app.transport.GattServiceInfo
import io.carreader.app.transport.TcpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class TransportLogicTest {
    private val genericAccess = GattServiceInfo(short(0x1800), listOf(GattCharInfo(short(0x2A00), 0x02)))

    @Test
    fun picksFff0Profile() {
        val services = listOf(
            genericAccess,
            GattServiceInfo(
                short(0xFFF0),
                listOf(GattCharInfo(short(0xFFF1), PROPERTY_NOTIFY), GattCharInfo(short(0xFFF2), PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE)),
            ),
        )
        val sel = BleUartProfile.select(services)!!
        assertEquals(short(0xFFF1), sel.notify)
        assertEquals(short(0xFFF2), sel.write)
    }

    @Test
    fun picksFfe0SingleCharacteristic() {
        val services = listOf(GattServiceInfo(short(0xFFE0), listOf(GattCharInfo(short(0xFFE1), PROPERTY_NOTIFY or PROPERTY_WRITE_NO_RESPONSE))))
        val sel = BleUartProfile.select(services)!!
        assertEquals(sel.notify, sel.write)
    }

    @Test
    fun picksNordicUart() {
        val svc = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val rx = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val tx = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val sel = BleUartProfile.select(listOf(GattServiceInfo(svc, listOf(GattCharInfo(rx, PROPERTY_WRITE), GattCharInfo(tx, PROPERTY_NOTIFY)))))!!
        assertEquals(tx, sel.notify)
        assertEquals(rx, sel.write)
    }

    @Test
    fun fallsBackToFirstUsableServiceSkippingStandardOnes() {
        val custom = UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2")
        val c1 = UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F")
        val services = listOf(
            genericAccess,
            GattServiceInfo(short(0x180F), listOf(GattCharInfo(short(0x2A19), PROPERTY_NOTIFY))), // battery: ignored
            GattServiceInfo(custom, listOf(GattCharInfo(c1, PROPERTY_NOTIFY or PROPERTY_WRITE))),
        )
        val sel = BleUartProfile.select(services)!!
        assertEquals(custom, sel.service)
        assertEquals(c1, sel.write)
    }

    @Test
    fun noUartServiceGivesNull() {
        assertNull(BleUartProfile.select(listOf(genericAccess)))
    }

    @Test
    fun obdNamesRankFirst() {
        assertTrue(DeviceRanking.looksLikeObd("OBDLink MX+ 29384"))
        assertTrue(DeviceRanking.looksLikeObd("vLinker MC-IOS"))
        assertTrue(DeviceRanking.looksLikeObd("V-LINK"))
        assertTrue(DeviceRanking.looksLikeObd("OBDII"))
        assertTrue(DeviceRanking.looksLikeObd("ELM327 v1.5"))
        assertFalse(DeviceRanking.looksLikeObd("JBL Flip 5"))
        val ranked = DeviceRanking.rank(
            listOf(
                DeviceEntry("JBL Flip 5", "1", DeviceKind.CLASSIC),
                DeviceEntry(null, "2", DeviceKind.BLE),
                DeviceEntry("OBDLink MX+", "3", DeviceKind.CLASSIC),
                DeviceEntry("Android-Vlink", "4", DeviceKind.CLASSIC),
            ),
        )
        assertEquals(listOf("4", "3", "1", "2"), ranked.map { it.address })
    }

    @Test
    fun tcpTransportRoundTrip() = runBlocking {
        ServerSocket(0).use { server ->
            val echo = thread {
                server.accept().use { client ->
                    val buf = ByteArray(64)
                    val n = client.getInputStream().read(buf)
                    client.getOutputStream().write(buf.copyOf(n) + ">".toByteArray())
                    client.getOutputStream().flush()
                    Thread.sleep(200)
                }
            }
            val t = TcpTransport("127.0.0.1", server.localPort)
            t.connect()
            assertArrayEquals(ByteArray(0), t.read(50))
            t.write("ATZ\r".toByteArray())
            var got = ByteArray(0)
            while (!String(got).endsWith(">")) got += t.read(1000)
            assertEquals("ATZ\r>", String(got))
            t.close()
            echo.join()
        }
    }
}
