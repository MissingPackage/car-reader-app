package io.carreader.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.carreader.app.R
import io.carreader.brands.vag.VagBrandModule
import io.carreader.brands.vag.VagCounters
import io.carreader.brands.vag.VagEcus
import io.carreader.core.mileage.CounterReader
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.guard.MutationKind
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcDescriptions
import io.carreader.core.obd2.StandardPids
import io.carreader.core.uds.FailureTypes
import java.util.Locale

/**
 * A string resource and its arguments; an argument may itself be a [TextRes]. Plain data, so the mapping from
 * core text to resources is testable on the JVM; [CoreText.resolve] turns it into text in the UI.
 */
data class TextRes(@StringRes val id: Int, val args: List<Any> = emptyList())

/**
 * Localizes the English text the core and brand modules produce for the UI (guard checks, refusal reasons,
 * confirmation texts, scan notes and errors, readiness monitors, connection and transport messages, standard PID
 * names, VAG control unit names, UDS failure types). Keys are used where the core has them (PID keys, ECU codes,
 * failure-type bytes); otherwise the exact English text. Unknown text is shown as is: the core may add more later.
 * DTC descriptions (hundreds of texts) and OBDb data item names stay in their original language.
 */
object CoreText {
    @Composable
    fun resolve(text: TextRes): String {
        val args = text.args.map { if (it is TextRes) resolve(it) else it }
        return if (args.isEmpty()) stringResource(text.id) else stringResource(text.id, *args.toTypedArray())
    }

    // ------------------------------------------------------------ connection, transport and link messages

    private class Rule(pattern: String, val build: (List<String>) -> TextRes) {
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
    }

    private fun exact(text: String, @StringRes id: Int) = Rule(Regex.escape(text)) { TextRes(id) }

    /** A nested message (the cause after "Connection lost: "): localized when known, else as is. */
    private fun nested(text: String): Any = messageRes(text) ?: text

    private val messageRules = listOf(
        exact("Initializing adapter", R.string.msg_initializing),
        Rule("""Connection lost: (.*)""") { TextRes(R.string.msg_connection_lost, listOf(nested(it[1]))) },
        Rule("""No answer from the car for (\d+) consecutive rounds \(ignition off or adapter lost\?\)""") {
            TextRes(R.string.msg_no_answer_rounds, listOf(it[1]))
        },
        exact("Connection failed", R.string.msg_connection_failed),
        exact("Not connected", R.string.msg_not_connected),
        exact("The BLE adapter did not answer (timeout)", R.string.msg_ble_timeout),
        Rule("""BLE connection failed \(GATT status (-?\d+)\)""") { TextRes(R.string.msg_ble_failed_status, listOf(it[1])) },
        Rule("""BLE service discovery failed \(status (-?\d+)\)""") { TextRes(R.string.msg_ble_discovery_failed, listOf(it[1])) },
        exact("The BLE adapter disconnected", R.string.msg_ble_disconnected),
        Rule("""The BLE adapter disconnected \(GATT status (-?\d+)\)""") { TextRes(R.string.msg_ble_disconnected_status, listOf(it[1])) },
        exact("BLE connection closed", R.string.msg_ble_closed),
        Rule("""BLE write failed \(status (-?\d+)\)""") { TextRes(R.string.msg_ble_write_failed, listOf(it[1])) },
        exact("No serial (UART) service found on this BLE device: is it an OBD adapter?", R.string.msg_ble_no_uart),
        exact("This phone could not open a BLE connection", R.string.msg_ble_cannot_open),
        Rule("""BLE scan failed \(error (-?\d+)\)""") { TextRes(R.string.msg_ble_scan_failed, listOf(it[1])) },
        Rule("""Cannot reach the Wi-Fi adapter at (\S+) \((.*)\)""") { TextRes(R.string.msg_wifi_unreachable, listOf(it[1], it[2])) },
        exact("The Wi-Fi adapter closed the connection", R.string.msg_wifi_closed),
        Rule("""Wi-Fi read failed: (.*)""") { TextRes(R.string.msg_wifi_read_failed, listOf(it[1])) },
        Rule("""Wi-Fi write failed: (.*)""") { TextRes(R.string.msg_wifi_write_failed, listOf(it[1])) },
        Rule("""Bluetooth connection to (.+?) failed \((.*)\)""") { TextRes(R.string.msg_bt_failed, listOf(it[1], it[2])) },
        exact("The Bluetooth adapter closed the connection", R.string.msg_bt_closed),
        Rule("""Bluetooth read failed: (.*)""") { TextRes(R.string.msg_bt_read_failed, listOf(it[1])) },
        Rule("""Bluetooth write failed: (.*)""") { TextRes(R.string.msg_bt_write_failed, listOf(it[1])) },
        exact("Bluetooth is not available on this phone", R.string.msg_bt_unavailable),
        exact("Bluetooth permission missing: allow it in the app settings", R.string.msg_bt_permission),
        Rule("""No answer from the adapter to (\S+)""") { TextRes(R.string.msg_adapter_no_answer, listOf(it[1])) },
        Rule("""The adapter does not answer cleanly \(no prompt after (\d+) attempts\): reconnect it""") {
            TextRes(R.string.msg_adapter_not_clean, listOf(it[1]))
        },
    )

    private fun match(rules: List<Rule>, text: String): TextRes? {
        for (rule in rules) {
            val m = rule.regex.matchEntire(text) ?: continue
            return rule.build(m.groupValues)
        }
        return null
    }

    /** Connection, transport and adapter messages (connection steps, failure and loss reasons, load errors). */
    fun messageRes(text: String): TextRes? = match(messageRules, text)

    @Composable
    fun message(text: String): String = messageRes(text)?.let { resolve(it) } ?: text

    // ------------------------------------------------------------ module scan

    private val scanErrorRules = listOf(
        exact("Scan failed", R.string.scan_error_failed),
        Rule("""(\S+) control units are not supported yet""") { TextRes(R.string.scan_error_unsupported_protocol, listOf(it[1])) },
        Rule("""Probe failed: (.*)""") { TextRes(R.string.scan_error_probe, listOf(nested(it[1]))) },
        Rule("""Identification failed: (.*)""") { TextRes(R.string.scan_error_identification, listOf(nested(it[1]))) },
        Rule("""DTC read failed: (.*)""") { TextRes(R.string.scan_error_dtcs, listOf(nested(it[1]))) },
        exact(VagBrandModule.TP20_NOTE, R.string.note_vag_tp20),
    )

    /** The core joins several scan errors of one control unit with "; ". */
    private val scanErrorSeparator = Regex("; (?=Identification failed: |DTC read failed: )")

    fun scanErrorRes(text: String): List<Any> =
        text.split(scanErrorSeparator).map { part -> match(scanErrorRules, part) ?: messageRes(part) ?: part }

    /** The error attached to one control unit of a module scan. */
    @Composable
    fun scanError(text: String): String =
        scanErrorRes(text).map { if (it is TextRes) resolve(it) else it.toString() }.joinToString("; ")

    private val identificationLabels = mapOf(
        "Part number" to R.string.ecu_id_part_number,
        "Software version" to R.string.ecu_id_software_version,
        "Hardware number" to R.string.ecu_id_hardware_number,
        "Hardware version" to R.string.ecu_id_hardware_version,
        "System name" to R.string.ecu_id_system_name,
        "Serial number" to R.string.ecu_id_serial_number,
        "Coding" to R.string.ecu_id_coding,
    )

    /** Labels of the identification values a brand module reads on scan ("Part number" ← F187). */
    @Composable
    fun identificationLabel(label: String): String = identificationLabels[label]?.let { stringResource(it) } ?: label

    // ------------------------------------------------------------ control unit names

    private val vagEcuNames = mapOf(
        "01" to R.string.ecu_vag_01, "02" to R.string.ecu_vag_02, "03" to R.string.ecu_vag_03,
        "08" to R.string.ecu_vag_08, "09" to R.string.ecu_vag_09, "13" to R.string.ecu_vag_13,
        "15" to R.string.ecu_vag_15, "16" to R.string.ecu_vag_16, "17" to R.string.ecu_vag_17,
        "19" to R.string.ecu_vag_19, "22" to R.string.ecu_vag_22, "42" to R.string.ecu_vag_42,
        "44" to R.string.ecu_vag_44, "52" to R.string.ecu_vag_52, "5F" to R.string.ecu_vag_5f,
        "75" to R.string.ecu_vag_75, "76" to R.string.ecu_vag_76, "8C" to R.string.ecu_vag_8c,
        "C6" to R.string.ecu_vag_c6, "?742" to R.string.ecu_vag_742,
    )

    /** VAG control unit names by address code; only for the units of [VagEcus] (same code and English name). */
    fun ecuNameRes(code: String, englishName: String): TextRes? {
        if (VagEcus.all.none { it.code == code && it.name == englishName }) return null
        return vagEcuNames[code]?.let(::TextRes)
    }

    @Composable
    fun ecuName(ecu: EcuDescriptor): String = ecuNameRes(ecu.code, ecu.name)?.let { resolve(it) } ?: ecu.name

    /** "01 Motore": code and localized name, as in the module list and screen titles. */
    @Composable
    fun ecuTitle(ecu: EcuDescriptor): String = "${ecu.code} ${ecuName(ecu)}"

    private val ecuLabelPattern = Regex("""(\S+) (.+)""")

    /** An "01 Engine" label the core attaches to DTCs and values read from a control unit. */
    @Composable
    fun ecuLabel(label: String): String {
        val m = ecuLabelPattern.matchEntire(label) ?: return label
        val res = ecuNameRes(m.groupValues[1], m.groupValues[2]) ?: return label
        return "${m.groupValues[1]} ${resolve(res)}"
    }

    // ------------------------------------------------------------ mileage check

    private val counterNotes = mapOf(
        VagCounters.SNAPSHOT_NOTE to R.string.mileage_note_snapshot,
        VagCounters.UNITS_NOTE to R.string.mileage_note_units,
    )

    /** A brand catalog's note on counters it cannot read; unknown notes stay in English. */
    fun counterNoteRes(text: String): TextRes? = counterNotes[text]?.let(::TextRes)

    @Composable
    fun counterNote(text: String): String = counterNoteRes(text)?.let { resolve(it) } ?: text

    private val obdModulePattern = Regex(Regex.escape(CounterReader.OBD_MODULE) + """ \((\S+)\)""")

    /** The unit a counter came from: "01 Motore", or "OBD-II standard (7E8)". */
    @Composable
    fun counterModule(module: String): String {
        obdModulePattern.matchEntire(module)?.let { return stringResource(R.string.mileage_obd_module, it.groupValues[1]) }
        return ecuLabel(module)
    }

    // ------------------------------------------------------------ standard PIDs

    private val pidNames = mapOf(
        0x01 to R.string.pid_01, 0x03 to R.string.pid_03, 0x04 to R.string.pid_04, 0x05 to R.string.pid_05,
        0x06 to R.string.pid_06, 0x07 to R.string.pid_07, 0x08 to R.string.pid_08, 0x09 to R.string.pid_09,
        0x0A to R.string.pid_0a, 0x0B to R.string.pid_0b, 0x0C to R.string.pid_0c, 0x0D to R.string.pid_0d,
        0x0E to R.string.pid_0e, 0x0F to R.string.pid_0f, 0x10 to R.string.pid_10, 0x11 to R.string.pid_11,
        0x1C to R.string.pid_1c, 0x1F to R.string.pid_1f, 0x21 to R.string.pid_21, 0x22 to R.string.pid_22,
        0x23 to R.string.pid_23, 0x2C to R.string.pid_2c, 0x2D to R.string.pid_2d, 0x2E to R.string.pid_2e,
        0x2F to R.string.pid_2f, 0x30 to R.string.pid_30, 0x31 to R.string.pid_31, 0x33 to R.string.pid_33,
        0x42 to R.string.pid_42, 0x43 to R.string.pid_43, 0x44 to R.string.pid_44, 0x45 to R.string.pid_45,
        0x46 to R.string.pid_46, 0x47 to R.string.pid_47, 0x49 to R.string.pid_49, 0x4A to R.string.pid_4a,
        0x4C to R.string.pid_4c, 0x4D to R.string.pid_4d, 0x4E to R.string.pid_4e, 0x51 to R.string.pid_51,
        0x52 to R.string.pid_52, 0x5C to R.string.pid_5c, 0x5E to R.string.pid_5e, 0x61 to R.string.pid_61,
        0x62 to R.string.pid_62, 0x63 to R.string.pid_63, 0x7F to R.string.pid_7f, 0xA6 to R.string.pid_a6,
    )

    /**
     * Name of a standard service 01 PID by its key ("01.0C"), as used by [io.carreader.core.obd2.LiveValue.key]
     * and [io.carreader.core.obd2.PidDefinition.key]; null for anything that is not one of the core's StandardPids.
     */
    fun pidNameRes(key: String): TextRes? {
        val pid = key.removePrefix("01.").takeIf { it.length == 2 && key.startsWith("01.") }?.toIntOrNull(16) ?: return null
        if (StandardPids.byPid(pid)?.key != key) return null
        return when (pid) {
            // Indexes as text: they are sensor numbers, not quantities to pluralize.
            in 0x24..0x2B -> TextRes(R.string.pid_o2_lambda, listOf("${pid - 0x23}"))
            // 3C bank 1 sensor 1, 3D bank 2 sensor 1, 3E bank 1 sensor 2, 3F bank 2 sensor 2.
            in 0x3C..0x3F -> TextRes(R.string.pid_catalyst_temp, listOf("${1 + (pid - 0x3C) % 2}", "${1 + (pid - 0x3C) / 2}"))
            else -> pidNames[pid]?.let(::TextRes)
        }
    }

    /** Localized PID name for a value or definition with this [key]; [fallback] (the core's name) otherwise. */
    @Composable
    fun pidName(key: String, fallback: String): String = pidNameRes(key)?.let { resolve(it) } ?: fallback

    // ------------------------------------------------------------ fault codes

    /** UDS failure-type byte (third DTC byte), mirroring the core's FailureTypes table; null when unknown. */
    fun failureTypeRes(ftb: Int): TextRes? {
        val v = ftb and 0xFF
        exactFailureType(v)?.let { return TextRes(it) }
        val category = failureCategory(v shr 4) ?: return null
        return TextRes(R.string.ft_subtype, listOf(TextRes(category), "0x%02X".format(Locale.ROOT, v)))
    }

    @Composable
    fun failureType(ftb: Int?): String? = ftb?.let(::failureTypeRes)?.let { resolve(it) }

    /**
     * The DTC description without the English failure-type suffix the brand module appends (" — circuit open"):
     * the card shows the failure type on its own, localized. The description itself stays English.
     */
    fun dtcDescription(dtc: Dtc): String? {
        val suffix = dtc.failureType?.takeIf { it != 0 }?.let(FailureTypes::describe)
        val own = dtc.description?.let { d ->
            when {
                suffix == null -> d
                d == suffix -> null
                else -> d.removeSuffix(" — $suffix")
            }
        }
        return own?.takeIf { it.isNotBlank() } ?: DtcDescriptions.describe(dtc.code)
    }

    @StringRes
    private fun exactFailureType(v: Int): Int? = when (v) {
        0x00 -> R.string.ft_00
        0x01 -> R.string.ft_01
        0x02 -> R.string.ft_02
        0x03 -> R.string.ft_03
        0x04 -> R.string.ft_04
        0x05 -> R.string.ft_05
        0x06 -> R.string.ft_06
        0x07 -> R.string.ft_07
        0x08 -> R.string.ft_08
        0x09 -> R.string.ft_09
        0x11 -> R.string.ft_11
        0x12 -> R.string.ft_12
        0x13 -> R.string.ft_13
        0x14 -> R.string.ft_14
        0x15 -> R.string.ft_15
        0x16 -> R.string.ft_16
        0x17 -> R.string.ft_17
        0x18 -> R.string.ft_18
        0x19 -> R.string.ft_19
        0x1A -> R.string.ft_1a
        0x1B -> R.string.ft_1b
        0x1C -> R.string.ft_1c
        0x1D -> R.string.ft_1d
        0x1E -> R.string.ft_1e
        0x1F -> R.string.ft_1f
        0x21 -> R.string.ft_21
        0x22 -> R.string.ft_22
        0x23 -> R.string.ft_23
        0x24 -> R.string.ft_24
        0x25 -> R.string.ft_25
        0x26 -> R.string.ft_26
        0x27 -> R.string.ft_27
        0x28 -> R.string.ft_28
        0x29 -> R.string.ft_29
        0x2A -> R.string.ft_2a
        0x2B -> R.string.ft_2b
        0x2F -> R.string.ft_2f
        0x31 -> R.string.ft_31
        0x32 -> R.string.ft_32
        0x33 -> R.string.ft_33
        0x34 -> R.string.ft_34
        0x35 -> R.string.ft_35
        0x36 -> R.string.ft_36
        0x37 -> R.string.ft_37
        0x38 -> R.string.ft_38
        0x39 -> R.string.ft_39
        0x3A -> R.string.ft_3a
        0x41 -> R.string.ft_41
        0x42 -> R.string.ft_42
        0x43 -> R.string.ft_43
        0x44 -> R.string.ft_44
        0x45 -> R.string.ft_45
        0x46 -> R.string.ft_46
        0x47 -> R.string.ft_47
        0x48 -> R.string.ft_48
        0x49 -> R.string.ft_49
        0x4A -> R.string.ft_4a
        0x4B -> R.string.ft_4b
        0x51 -> R.string.ft_51
        0x52 -> R.string.ft_52
        0x53 -> R.string.ft_53
        0x54 -> R.string.ft_54
        0x55 -> R.string.ft_55
        0x56 -> R.string.ft_56
        0x57 -> R.string.ft_57
        0x61 -> R.string.ft_61
        0x62 -> R.string.ft_62
        0x63 -> R.string.ft_63
        0x64 -> R.string.ft_64
        0x65 -> R.string.ft_65
        0x66 -> R.string.ft_66
        0x67 -> R.string.ft_67
        0x68 -> R.string.ft_68
        0x71 -> R.string.ft_71
        0x72 -> R.string.ft_72
        0x73 -> R.string.ft_73
        0x74 -> R.string.ft_74
        0x75 -> R.string.ft_75
        0x76 -> R.string.ft_76
        0x77 -> R.string.ft_77
        0x78 -> R.string.ft_78
        0x79 -> R.string.ft_79
        0x7A -> R.string.ft_7a
        0x7B -> R.string.ft_7b
        0x81 -> R.string.ft_81
        0x82 -> R.string.ft_82
        0x83 -> R.string.ft_83
        0x84 -> R.string.ft_84
        0x85 -> R.string.ft_85
        0x86 -> R.string.ft_86
        0x87 -> R.string.ft_87
        0x88 -> R.string.ft_88
        0x8F -> R.string.ft_8f
        0x91 -> R.string.ft_91
        0x92 -> R.string.ft_92
        0x93 -> R.string.ft_93
        0x94 -> R.string.ft_94
        0x95 -> R.string.ft_95
        0x96 -> R.string.ft_96
        0x97 -> R.string.ft_97
        0x98 -> R.string.ft_98
        else -> null
    }

    @StringRes
    private fun failureCategory(high: Int): Int? = when (high) {
        0x1 -> R.string.ft_cat_1
        0x2 -> R.string.ft_cat_2
        0x3 -> R.string.ft_cat_3
        0x4 -> R.string.ft_cat_4
        0x5 -> R.string.ft_cat_5
        0x6 -> R.string.ft_cat_6
        0x7 -> R.string.ft_cat_7
        0x8 -> R.string.ft_cat_8
        0x9 -> R.string.ft_cat_9
        0xF -> R.string.ft_cat_f
        else -> null
    }

    // ------------------------------------------------------------ guard

    private val moving = Regex("""Vehicle is moving \((\d+) km/h\)""")
    private val lowBattery = Regex("""Battery voltage too low \(([\d.]+) V\)""")
    private val engineRunning = Regex("""Engine running \((\d+) rpm\).*""")
    private val batteryCheck = Regex("""Battery voltage ≥ ([\d.]+) V""")
    private val certifiedAdapter = Regex("""This operation requires a certified adapter \(OBDLink, vLinker\); found (.*)""")
    private val notVerified = Regex("""(.+?): could not be verified \((.*)\)""", RegexOption.DOT_MATCHES_ALL)

    @Composable
    fun checkName(name: String): String = when {
        name == "Vehicle stationary" -> stringResource(R.string.check_stationary)
        name == "Engine off" -> stringResource(R.string.check_engine_off)
        name == "Adapter suitable" -> stringResource(R.string.check_adapter)
        batteryCheck.matches(name) -> stringResource(R.string.check_battery, batteryCheck.find(name)!!.groupValues[1])
        else -> name
    }

    @Composable
    fun reason(text: String): String {
        moving.matchEntire(text)?.let { return stringResource(R.string.reason_moving, it.groupValues[1]) }
        lowBattery.matchEntire(text)?.let { return stringResource(R.string.reason_low_battery, it.groupValues[1]) }
        engineRunning.matchEntire(text)?.let { return stringResource(R.string.reason_engine_running, it.groupValues[1]) }
        certifiedAdapter.matchEntire(text)?.let { return stringResource(R.string.reason_certified_adapter, it.groupValues[1]) }
        notVerified.matchEntire(text)?.let {
            return stringResource(R.string.reason_not_verified, checkName(it.groupValues[1]), message(it.groupValues[2]))
        }
        return when {
            text.startsWith("Operations that modify the vehicle are disabled") -> stringResource(R.string.reason_disabled)
            text == "Cancelled by user" -> stringResource(R.string.reason_cancelled)
            text.startsWith("Vehicle speed unavailable") -> stringResource(R.string.reason_speed_unknown)
            text == "Battery voltage unavailable" -> stringResource(R.string.reason_battery_unknown)
            text.startsWith("Engine speed unavailable: make sure") -> stringResource(R.string.reason_rpm_unknown_warn)
            text.startsWith("Engine speed unavailable") -> stringResource(R.string.reason_rpm_unknown)
            text == "Adapter not initialized" -> stringResource(R.string.reason_adapter_missing)
            else -> message(text)
        }
    }

    @Composable
    fun kind(kind: MutationKind): String = when (kind) {
        MutationKind.CLEAR_EMISSION_DTC -> stringResource(R.string.kind_clear_emission)
        MutationKind.CLEAR_ECU_DTC -> stringResource(R.string.kind_clear_ecu)
        MutationKind.WRITE_DATA -> stringResource(R.string.kind_write)
        MutationKind.ROUTINE -> stringResource(R.string.kind_routine)
    }

    /** Target label of a mutation request, as shown in the confirmation dialog. */
    @Composable
    fun target(label: String): String {
        if (label == "All emission ECUs") return stringResource(R.string.confirm_target_all_emission)
        // "01 Engine (7E0)": the control unit of a module clear.
        val m = ecuTarget.matchEntire(label) ?: return label
        val res = ecuNameRes(m.groupValues[1], m.groupValues[2]) ?: return label
        return "${m.groupValues[1]} ${resolve(res)} (${m.groupValues[3]})"
    }

    private val ecuTarget = Regex("""(\S+) (.+) \(([0-9A-F]+)\)""")

    /** Description of a mutation request, as shown in the confirmation dialog. */
    @Composable
    fun description(text: String): String = when (text) {
        "Clear stored, pending fault codes and freeze frames; turns off the MIL; resets readiness monitors" ->
            stringResource(R.string.confirm_desc_clear_emission)
        "Clear the fault memory of this control module" -> stringResource(R.string.confirm_desc_clear_ecu)
        else -> text
    }

    /** Notes a brand module adds to a finished module scan. */
    @Composable
    fun brandNote(text: String): String = when (text) {
        VagBrandModule.TP20_NOTE -> stringResource(R.string.note_vag_tp20)
        VagBrandModule.KLINE_NOTE -> stringResource(R.string.note_vag_kline)
        else -> text
    }

    /** Readiness monitor names (SAE J1979 service 01 PID 01, as named by the core's Readiness decoder). */
    @Composable
    fun monitor(name: String): String = when (name) {
        "Misfire" -> stringResource(R.string.monitor_misfire)
        "Fuel system" -> stringResource(R.string.monitor_fuel_system)
        "Comprehensive components" -> stringResource(R.string.monitor_components)
        "Catalyst" -> stringResource(R.string.monitor_catalyst)
        "Heated catalyst" -> stringResource(R.string.monitor_heated_catalyst)
        "Evaporative system" -> stringResource(R.string.monitor_evap)
        "Secondary air system" -> stringResource(R.string.monitor_secondary_air)
        "Gasoline particulate filter" -> stringResource(R.string.monitor_gpf)
        "Oxygen sensor" -> stringResource(R.string.monitor_o2_sensor)
        "Oxygen sensor heater" -> stringResource(R.string.monitor_o2_heater)
        "EGR / VVT system" -> stringResource(R.string.monitor_egr_vvt)
        "NMHC catalyst" -> stringResource(R.string.monitor_nmhc_catalyst)
        "NOx / SCR aftertreatment" -> stringResource(R.string.monitor_nox_scr)
        "Boost pressure" -> stringResource(R.string.monitor_boost)
        "Exhaust gas sensor" -> stringResource(R.string.monitor_exhaust_sensor)
        "Particulate filter" -> stringResource(R.string.monitor_pm_filter)
        else -> name
    }
}
