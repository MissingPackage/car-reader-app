package io.carreader.app

import io.carreader.app.ui.CoreText
import io.carreader.app.ui.TextRes
import io.carreader.brands.vag.VagBrandModule
import io.carreader.brands.vag.VagEcus
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcDescriptions
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.StandardPids
import io.carreader.core.uds.FailureTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The app-side localization of core text: keyed tables must cover everything the core can produce. */
class CoreTextTest {
    @Test
    fun everyStandardPidHasALocalizedName() {
        val missing = StandardPids.all.filter { CoreText.pidNameRes(it.key) == null }.map { "${it.key} ${it.name}" }
        assertEquals("PIDs without a localized name", emptyList<String>(), missing)
        assertEquals(TextRes(R.string.pid_0c), CoreText.pidNameRes("01.0C"))
        assertEquals(TextRes(R.string.pid_o2_lambda, listOf("3")), CoreText.pidNameRes("01.26"))
        assertEquals(TextRes(R.string.pid_catalyst_temp, listOf("2", "1")), CoreText.pidNameRes("01.3D"))
        assertEquals(TextRes(R.string.pid_catalyst_temp, listOf("1", "2")), CoreText.pidNameRes("01.3E"))
        // Brand (OBDb) data items and unknown PIDs keep their own name.
        assertNull(CoreText.pidNameRes("01.FF"))
        assertNull(CoreText.pidNameRes("22.F40C"))
    }

    @Test
    fun everyVagControlUnitHasALocalizedName() {
        val missing = VagEcus.all.filter { CoreText.ecuNameRes(it.code, it.name) == null }.map { "${it.code} ${it.name}" }
        assertEquals("VAG control units without a localized name", emptyList<String>(), missing)
        assertEquals(TextRes(R.string.ecu_vag_01), CoreText.ecuNameRes("01", "Engine"))
        // Same code from another brand with another name: not translated as a VAG unit.
        assertNull(CoreText.ecuNameRes("01", "Powertrain"))
    }

    @Test
    fun everyFailureTypeOfTheCoreIsLocalized() {
        for (ftb in 0..0xFF) {
            val core = FailureTypes.describe(ftb)
            val app = CoreText.failureTypeRes(ftb)
            if (core == null) assertNull("0x%02X".format(ftb), app) else assertNotNull("0x%02X $core".format(ftb), app)
        }
        assertEquals(TextRes(R.string.ft_13), CoreText.failureTypeRes(0x13))
        assertEquals(TextRes(R.string.ft_subtype, listOf(TextRes(R.string.ft_cat_1), "0x10")), CoreText.failureTypeRes(0x10))
    }

    @Test
    fun connectionAndTransportMessagesAreMapped() {
        assertEquals(TextRes(R.string.msg_initializing), CoreText.messageRes("Initializing adapter"))
        assertEquals(
            TextRes(R.string.msg_connection_lost, listOf(TextRes(R.string.msg_ble_disconnected))),
            CoreText.messageRes("Connection lost: The BLE adapter disconnected"),
        )
        assertEquals(
            TextRes(R.string.msg_connection_lost, listOf("something new")),
            CoreText.messageRes("Connection lost: something new"),
        )
        assertEquals(
            TextRes(R.string.msg_no_answer_rounds, listOf("5")),
            CoreText.messageRes("No answer from the car for 5 consecutive rounds (ignition off or adapter lost?)"),
        )
        assertEquals(TextRes(R.string.msg_ble_timeout), CoreText.messageRes("The BLE adapter did not answer (timeout)"))
        assertEquals(
            TextRes(R.string.msg_wifi_unreachable, listOf("192.168.0.10:35000", "connect timed out")),
            CoreText.messageRes("Cannot reach the Wi-Fi adapter at 192.168.0.10:35000 (connect timed out)"),
        )
        assertEquals(
            TextRes(R.string.msg_bt_failed, listOf("OBDII", "secure SPP: read failed; insecure SPP: read failed")),
            CoreText.messageRes("Bluetooth connection to OBDII failed (secure SPP: read failed; insecure SPP: read failed)"),
        )
        assertNull(CoreText.messageRes("Something the app does not know"))
    }

    @Test
    fun scanErrorsAreSplitAndMapped() {
        assertEquals(listOf(TextRes(R.string.scan_error_failed)), CoreText.scanErrorRes("Scan failed"))
        assertEquals(listOf(TextRes(R.string.note_vag_tp20)), CoreText.scanErrorRes(VagBrandModule.TP20_NOTE))
        assertEquals(
            listOf(
                TextRes(R.string.scan_error_identification, listOf("timeout; retry")),
                TextRes(R.string.scan_error_dtcs, listOf(TextRes(R.string.msg_ble_closed))),
            ),
            CoreText.scanErrorRes("Identification failed: timeout; retry; DTC read failed: BLE connection closed"),
        )
        assertEquals(listOf(TextRes(R.string.scan_error_unsupported_protocol, listOf("KWP2000"))), CoreText.scanErrorRes("KWP2000 control units are not supported yet"))
    }

    @Test
    fun dtcDescriptionDropsTheEnglishFailureTypeSuffix() {
        val ft = FailureTypes.describe(0x13)!!
        val ecu = Dtc("P0101", DtcSource.ECU, description = "Mass air flow circuit range/performance — $ft", failureType = 0x13)
        assertEquals("Mass air flow circuit range/performance", CoreText.dtcDescription(ecu))
        val onlyFt = Dtc("U1234", DtcSource.ECU, description = ft, failureType = 0x13)
        assertEquals(DtcDescriptions.describe("U1234"), CoreText.dtcDescription(onlyFt))
        val obd = Dtc("P0300", DtcSource.STORED, description = "Random/multiple cylinder misfire detected")
        assertEquals("Random/multiple cylinder misfire detected", CoreText.dtcDescription(obd))
    }
}
