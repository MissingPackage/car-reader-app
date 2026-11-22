package io.carreader.core.obd2

/**
 * Short English descriptions of common generic (SAE J2012) trouble codes, written for this app.
 * Manufacturer-specific codes (P1xxx, most B1/C1/U1 ...) have no text here: use [categoryOf] for those.
 */
object DtcDescriptions {

    /** Text for a generic code such as "P0301", or null when the code is not in the table. */
    fun describe(code: String): String? = table[code.trim().uppercase()]

    /** [describe] with a [categoryOf] fallback, never null for a well-formed code. */
    fun describeOrCategory(code: String): String? = describe(code) ?: categoryOf(code)

    /**
     * The SAE category of a code, e.g. "Powertrain – generic (SAE): ignition system or misfire" or
     * "Network – manufacturer-specific". Null for malformed codes.
     */
    fun categoryOf(code: String): String? {
        val c = code.trim().uppercase()
        if (DtcCodec.parse(c) == null) return null
        val system = when (c[0]) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> return null
        }
        val generic = when (c[0]) {
            'P' -> when (c[1]) {
                '0', '2' -> true
                '1' -> false
                else -> c[2] >= '4' // P30xx-P33xx manufacturer, P34xx-P39xx generic
            }
            else -> c[1] == '0' || c[1] == '3'
        }
        val kind = if (generic) "generic (SAE)" else "manufacturer-specific"
        val area = if (c[0] == 'P' && c[1] == '0') powertrainArea(c[2]) else null
        return if (area != null) "$system – $kind: $area" else "$system – $kind"
    }

    private fun powertrainArea(digit: Char): String? = when (digit) {
        '0' -> "fuel and air metering, auxiliary emission controls"
        '1', '2' -> "fuel and air metering"
        '3' -> "ignition system or misfire"
        '4' -> "auxiliary emission controls"
        '5' -> "vehicle speed, idle control and auxiliary inputs"
        '6' -> "control module and auxiliary outputs"
        '7', '8', '9' -> "transmission"
        'A' -> "hybrid propulsion"
        else -> null
    }

    private val table: Map<String, String> by lazy { build() }

    private fun build(): Map<String, String> {
        val m = HashMap<String, String>(800)
        fun put(code: String, text: String) { m[code] = text }
        fun p(n: Int) = "P" + n.toString(16).uppercase().padStart(4, '0')

        // --- Camshaft timing, O2 heater control, turbo, fuel pressure (P00xx)
        for ((offset, bank) in listOf(0x0010 to 1, 0x0020 to 2)) {
            put(p(offset), "Camshaft position actuator \"A\" circuit (bank $bank)")
            put(p(offset + 1), "Camshaft timing \"A\" over-advanced or system performance (bank $bank)")
            put(p(offset + 2), "Camshaft timing \"A\" over-retarded (bank $bank)")
            put(p(offset + 3), "Camshaft position actuator \"B\" circuit (bank $bank)")
            put(p(offset + 4), "Camshaft timing \"B\" over-advanced or system performance (bank $bank)")
            put(p(offset + 5), "Camshaft timing \"B\" over-retarded (bank $bank)")
        }
        put("P0016", "Crankshaft / camshaft position correlation (bank 1, sensor A)")
        put("P0017", "Crankshaft / camshaft position correlation (bank 1, sensor B)")
        put("P0018", "Crankshaft / camshaft position correlation (bank 2, sensor A)")
        put("P0019", "Crankshaft / camshaft position correlation (bank 2, sensor B)")
        for ((base, loc) in listOf(0x0030 to "bank 1 sensor 1", 0x0036 to "bank 1 sensor 2", 0x0050 to "bank 2 sensor 1", 0x0056 to "bank 2 sensor 2")) {
            put(p(base), "Oxygen sensor heater control circuit ($loc)")
            put(p(base + 1), "Oxygen sensor heater control circuit low ($loc)")
            put(p(base + 2), "Oxygen sensor heater control circuit high ($loc)")
        }
        put("P0033", "Turbocharger bypass valve control circuit")
        put("P0045", "Turbocharger boost control \"A\" circuit / open")
        put("P0046", "Turbocharger boost control \"A\" circuit range / performance")
        put("P0047", "Turbocharger boost control \"A\" circuit low")
        put("P0048", "Turbocharger boost control \"A\" circuit high")
        put("P0068", "Manifold pressure / mass air flow does not match throttle position")
        put("P0069", "Manifold pressure and barometric pressure do not agree")
        put("P0087", "Fuel rail / system pressure too low")
        put("P0088", "Fuel rail / system pressure too high")
        put("P0089", "Fuel pressure regulator performance")
        put("P0090", "Fuel pressure regulator control circuit")
        put("P0093", "Fuel system large leak detected")

        // --- Air and fuel sensors (P01xx)
        fun sensorGroup(base: Int, sensor: String, withIntermittent: Boolean = true) {
            put(p(base), "$sensor circuit")
            put(p(base + 1), "$sensor circuit range / performance")
            put(p(base + 2), "$sensor circuit low")
            put(p(base + 3), "$sensor circuit high")
            if (withIntermittent) put(p(base + 4), "$sensor circuit intermittent")
        }
        sensorGroup(0x0100, "Mass air flow (MAF) sensor \"A\"")
        sensorGroup(0x0105, "Manifold absolute pressure / barometric pressure sensor")
        sensorGroup(0x0110, "Intake air temperature sensor 1")
        sensorGroup(0x0115, "Engine coolant temperature sensor 1")
        sensorGroup(0x0120, "Throttle / pedal position sensor \"A\"")
        put("P0125", "Coolant temperature too low for closed-loop fuel control")
        put("P0126", "Coolant temperature too low for stable operation")
        put("P0127", "Intake air temperature too high")
        put("P0128", "Coolant thermostat: coolant stays below regulating temperature")

        // O2 sensors: six codes per sensor position.
        val o2 = listOf(
            0x0130 to "bank 1 sensor 1", 0x0136 to "bank 1 sensor 2", 0x0142 to "bank 1 sensor 3",
            0x0150 to "bank 2 sensor 1", 0x0156 to "bank 2 sensor 2", 0x0162 to "bank 2 sensor 3",
        )
        // Codes are hexadecimal in form but consecutive in decimal (P0136..P0141): step in decimal digits.
        for ((base, loc) in o2) {
            val texts = listOf(
                "Oxygen sensor circuit ($loc)",
                "Oxygen sensor circuit low voltage ($loc)",
                "Oxygen sensor circuit high voltage ($loc)",
                "Oxygen sensor slow response ($loc)",
                "Oxygen sensor no activity detected ($loc)",
                "Oxygen sensor heater circuit ($loc)",
            )
            val start = base.toString(16).toInt() // e.g. 0x0136 -> 136
            texts.forEachIndexed { i, t -> put("P0" + (start + i).toString().padStart(3, '0'), t) }
        }
        put("P0170", "Fuel trim (bank 1)")
        put("P0171", "System too lean (bank 1)")
        put("P0172", "System too rich (bank 1)")
        put("P0173", "Fuel trim (bank 2)")
        put("P0174", "System too lean (bank 2)")
        put("P0175", "System too rich (bank 2)")
        put("P0176", "Fuel composition sensor circuit")
        put("P0180", "Fuel temperature sensor \"A\" circuit")
        sensorGroup(0x0190, "Fuel rail pressure sensor \"A\"")
        put("P0195", "Engine oil temperature sensor circuit")
        put("P0196", "Engine oil temperature sensor range / performance")
        put("P0197", "Engine oil temperature sensor low")
        put("P0198", "Engine oil temperature sensor high")

        // --- Injectors (P02xx)
        put("P0200", "Injector circuit / open")
        for (cyl in 1..12) put("P02" + (cyl).toString().padStart(2, '0'), "Injector circuit / open, cylinder $cyl")
        put("P0217", "Engine coolant over-temperature")
        put("P0218", "Transmission fluid over-temperature")
        put("P0219", "Engine over-speed condition")
        sensorGroup(0x0220, "Throttle / pedal position sensor \"B\"")
        put("P0230", "Fuel pump primary circuit")
        put("P0234", "Turbocharger / supercharger \"A\" overboost")
        put("P0235", "Turbocharger boost sensor \"A\" circuit")
        put("P0236", "Turbocharger boost sensor \"A\" circuit range / performance")
        put("P0237", "Turbocharger boost sensor \"A\" circuit low")
        put("P0238", "Turbocharger boost sensor \"A\" circuit high")
        put("P0243", "Turbocharger wastegate solenoid \"A\"")
        put("P0251", "Injection pump fuel metering control \"A\" (cam / rotor / injector)")
        put("P0263", "Cylinder 1 contribution / balance")
        put("P0299", "Turbocharger / supercharger \"A\" underboost")

        // --- Misfire, knock, crank/cam, ignition coils (P03xx)
        put("P0300", "Random / multiple cylinder misfire detected")
        for (cyl in 1..12) put("P03" + cyl.toString().padStart(2, '0'), "Cylinder $cyl misfire detected")
        put("P0313", "Misfire detected with low fuel level")
        put("P0314", "Single cylinder misfire (cylinder not specified)")
        put("P0315", "Crankshaft position system variation not learned")
        put("P0316", "Misfire detected on start-up (first 1000 revolutions)")
        sensorGroup(0x0325, "Knock sensor 1 (bank 1 or single sensor)")
        sensorGroup(0x0330, "Knock sensor 2 (bank 2)")
        sensorGroup(0x0335, "Crankshaft position sensor \"A\"")
        sensorGroup(0x0340, "Camshaft position sensor \"A\" (bank 1 or single sensor)")
        sensorGroup(0x0345, "Camshaft position sensor \"A\" (bank 2)")
        put("P0350", "Ignition coil primary / secondary circuit")
        val coils = "ABCDEFGHIJKL"
        for (n in 1..12) put("P03" + (50 + n).toString(), "Ignition coil \"${coils[n - 1]}\" primary / secondary circuit")
        put("P0365", "Camshaft position sensor \"B\" circuit (bank 1)")
        put("P0366", "Camshaft position sensor \"B\" circuit range / performance (bank 1)")
        put("P0380", "Glow plug / heater circuit \"A\"")
        put("P0381", "Glow plug / heater indicator circuit")
        put("P0382", "Glow plug / heater circuit \"B\"")
        put("P0390", "Camshaft position sensor \"B\" circuit (bank 2)")

        // --- EGR, secondary air, catalyst, EVAP (P04xx)
        put("P0400", "EGR flow")
        put("P0401", "EGR flow insufficient")
        put("P0402", "EGR flow excessive")
        put("P0403", "EGR control circuit")
        put("P0404", "EGR control circuit range / performance")
        put("P0405", "EGR sensor \"A\" circuit low")
        put("P0406", "EGR sensor \"A\" circuit high")
        put("P0407", "EGR sensor \"B\" circuit low")
        put("P0408", "EGR sensor \"B\" circuit high")
        put("P0409", "EGR sensor \"A\" circuit")
        put("P0410", "Secondary air injection system")
        put("P0411", "Secondary air injection: incorrect flow detected")
        put("P0420", "Catalyst efficiency below threshold (bank 1)")
        put("P0421", "Warm-up catalyst efficiency below threshold (bank 1)")
        put("P0430", "Catalyst efficiency below threshold (bank 2)")
        put("P0431", "Warm-up catalyst efficiency below threshold (bank 2)")
        put("P0440", "Evaporative emission (EVAP) system")
        put("P0441", "EVAP system incorrect purge flow")
        put("P0442", "EVAP system small leak detected")
        put("P0443", "EVAP purge control valve circuit")
        put("P0444", "EVAP purge control valve circuit open")
        put("P0445", "EVAP purge control valve circuit shorted")
        put("P0446", "EVAP vent control circuit")
        put("P0447", "EVAP vent control circuit open")
        put("P0448", "EVAP vent control circuit shorted")
        put("P0449", "EVAP vent valve / solenoid circuit")
        sensorGroup(0x0450, "EVAP pressure sensor")
        put("P0455", "EVAP system large leak detected")
        put("P0456", "EVAP system very small leak detected")
        put("P0457", "EVAP system leak detected (fuel cap loose or missing)")
        put("P0460", "Fuel level sensor \"A\" circuit")
        put("P0461", "Fuel level sensor \"A\" circuit range / performance")
        put("P0462", "Fuel level sensor \"A\" circuit low")
        put("P0463", "Fuel level sensor \"A\" circuit high")
        put("P0470", "Exhaust pressure sensor \"A\" circuit")
        put("P0471", "Exhaust pressure sensor \"A\" circuit range / performance")
        put("P0480", "Cooling fan 1 control circuit")
        put("P0481", "Cooling fan 2 control circuit")
        put("P0491", "Secondary air injection system insufficient flow (bank 1)")
        put("P0492", "Secondary air injection system insufficient flow (bank 2)")

        // --- Speed, idle, auxiliary inputs (P05xx)
        put("P0500", "Vehicle speed sensor \"A\"")
        put("P0501", "Vehicle speed sensor \"A\" range / performance")
        put("P0502", "Vehicle speed sensor \"A\" circuit low")
        put("P0503", "Vehicle speed sensor \"A\" intermittent / erratic / high")
        put("P0505", "Idle air control system")
        put("P0506", "Idle speed lower than expected")
        put("P0507", "Idle speed higher than expected")
        sensorGroup(0x0520, "Engine oil pressure sensor / switch", withIntermittent = false)
        put("P0524", "Engine oil pressure too low")
        put("P0530", "A/C refrigerant pressure sensor \"A\" circuit")
        put("P0532", "A/C refrigerant pressure sensor \"A\" circuit low")
        put("P0533", "A/C refrigerant pressure sensor \"A\" circuit high")
        put("P0560", "System voltage")
        put("P0562", "System voltage low")
        put("P0563", "System voltage high")
        put("P0571", "Brake switch \"A\" circuit")

        // --- Control module and outputs (P06xx)
        put("P0600", "Serial communication link")
        put("P0601", "Control module memory checksum error")
        put("P0602", "Control module programming error")
        put("P0603", "Control module keep-alive memory (KAM) error")
        put("P0604", "Control module random access memory (RAM) error")
        put("P0605", "Control module read-only memory (ROM) error")
        put("P0606", "Control module processor fault")
        put("P0607", "Control module performance")
        put("P0627", "Fuel pump \"A\" control circuit / open")
        put("P0641", "Sensor reference voltage \"A\" circuit / open")
        put("P0645", "A/C clutch relay control circuit")
        put("P0651", "Sensor reference voltage \"B\" circuit / open")
        put("P0670", "Glow plug control module circuit")
        for (cyl in 1..12) put("P06" + (70 + cyl).toString(), "Glow plug circuit, cylinder $cyl")
        put("P0683", "Glow plug control module to engine control module communication circuit")
        put("P0684", "Glow plug control module to engine control module communication range / performance")

        // --- Transmission (P07xx), the most frequent generic ones
        put("P0700", "Transmission control system (MIL request from the TCM)")
        put("P0705", "Transmission range sensor \"A\" circuit")
        put("P0715", "Input / turbine speed sensor \"A\" circuit")
        put("P0720", "Output speed sensor circuit")
        put("P0730", "Incorrect gear ratio")
        put("P0740", "Torque converter clutch solenoid circuit / open")
        put("P0741", "Torque converter clutch solenoid performance or stuck off")

        // --- Hybrid
        put("P0A80", "Replace hybrid battery pack")

        // --- Frequent P2xxx generic codes
        put("P2002", "Particulate filter efficiency below threshold (bank 1)")
        put("P2003", "Particulate filter efficiency below threshold (bank 2)")
        put("P2004", "Intake manifold runner control stuck open (bank 1)")
        put("P2008", "Intake manifold runner control circuit / open (bank 1)")
        put("P2015", "Intake manifold runner position sensor / switch range / performance (bank 1)")
        put("P203F", "Reductant (AdBlue / DEF) level too low")
        put("P2047", "Reductant injector circuit / open (bank 1, unit 1)")
        put("P2096", "Post-catalyst fuel trim system too lean (bank 1)")
        put("P2097", "Post-catalyst fuel trim system too rich (bank 1)")
        put("P2098", "Post-catalyst fuel trim system too lean (bank 2)")
        put("P2099", "Post-catalyst fuel trim system too rich (bank 2)")
        put("P20E8", "Reductant pressure too low")
        put("P20EE", "SCR NOx catalyst efficiency below threshold (bank 1)")
        put("P2100", "Throttle actuator \"A\" control motor circuit / open")
        put("P2101", "Throttle actuator \"A\" control motor circuit range / performance")
        put("P2111", "Throttle actuator \"A\" control system stuck open")
        put("P2112", "Throttle actuator \"A\" control system stuck closed")
        put("P2119", "Throttle actuator \"A\" control throttle body range / performance")
        put("P2122", "Throttle / pedal position sensor \"D\" circuit low")
        put("P2123", "Throttle / pedal position sensor \"D\" circuit high")
        put("P2127", "Throttle / pedal position sensor \"E\" circuit low")
        put("P2128", "Throttle / pedal position sensor \"E\" circuit high")
        put("P2135", "Throttle / pedal position sensor \"A\" / \"B\" voltage correlation")
        put("P2138", "Throttle / pedal position sensor \"D\" / \"E\" voltage correlation")
        put("P2177", "System too lean off idle (bank 1)")
        put("P2178", "System too rich off idle (bank 1)")
        put("P2179", "System too lean off idle (bank 2)")
        put("P2180", "System too rich off idle (bank 2)")
        put("P2181", "Cooling system performance")
        put("P2187", "System too lean at idle (bank 1)")
        put("P2188", "System too rich at idle (bank 1)")
        put("P2189", "System too lean at idle (bank 2)")
        put("P2190", "System too rich at idle (bank 2)")
        put("P2195", "Oxygen sensor signal stuck lean (bank 1 sensor 1)")
        put("P2196", "Oxygen sensor signal stuck rich (bank 1 sensor 1)")
        put("P2197", "Oxygen sensor signal stuck lean (bank 2 sensor 1)")
        put("P2198", "Oxygen sensor signal stuck rich (bank 2 sensor 1)")
        put("P2200", "NOx sensor circuit (bank 1 sensor 1)")
        put("P2201", "NOx sensor circuit range / performance (bank 1 sensor 1)")
        put("P2202", "NOx sensor circuit low (bank 1 sensor 1)")
        put("P2203", "NOx sensor circuit high (bank 1 sensor 1)")
        put("P2263", "Turbocharger / supercharger boost system performance")
        put("P2270", "Oxygen sensor signal stuck lean (bank 1 sensor 2)")
        put("P2271", "Oxygen sensor signal stuck rich (bank 1 sensor 2)")
        put("P2272", "Oxygen sensor signal stuck lean (bank 2 sensor 2)")
        put("P2273", "Oxygen sensor signal stuck rich (bank 2 sensor 2)")
        put("P2279", "Intake air system leak")
        put("P2293", "Fuel pressure regulator 2 performance")
        put("P242F", "Particulate filter restriction: ash accumulation")
        put("P2452", "Particulate filter differential pressure sensor \"A\" circuit")
        put("P2453", "Particulate filter differential pressure sensor \"A\" range / performance")
        put("P2454", "Particulate filter differential pressure sensor \"A\" circuit low")
        put("P2455", "Particulate filter differential pressure sensor \"A\" circuit high")
        put("P2456", "Particulate filter differential pressure sensor \"A\" intermittent / erratic")
        put("P2457", "EGR cooling system performance")
        put("P2458", "Particulate filter regeneration duration")
        put("P2459", "Particulate filter regeneration frequency")
        put("P2463", "Particulate filter restriction: soot accumulation")
        put("P2BAD", "NOx emissions exceeded, root cause unknown")

        // --- Network (U0xxx)
        put("U0001", "High-speed CAN communication bus")
        put("U0073", "Control module communication bus \"A\" off")
        put("U0100", "Lost communication with engine control module (ECM / PCM \"A\")")
        put("U0101", "Lost communication with transmission control module (TCM)")
        put("U0102", "Lost communication with transfer case control module")
        put("U0121", "Lost communication with anti-lock brake system (ABS) module")
        put("U0122", "Lost communication with vehicle dynamics control module")
        put("U0126", "Lost communication with steering angle sensor module")
        put("U0131", "Lost communication with power steering control module")
        put("U0140", "Lost communication with body control module (BCM)")
        put("U0151", "Lost communication with restraints (airbag) control module")
        put("U0155", "Lost communication with instrument panel cluster")
        put("U0164", "Lost communication with HVAC control module")
        put("U0167", "Lost communication with immobilizer control module")
        put("U0184", "Lost communication with radio")
        put("U0401", "Invalid data received from engine control module (ECM / PCM \"A\")")
        put("U0402", "Invalid data received from transmission control module (TCM)")
        put("U0415", "Invalid data received from anti-lock brake system (ABS) module")
        return m
    }
}
