package io.carreader.core.vin

/**
 * Built-in WMI (VIN positions 1-3) table for the brands common in Italy and the EU.
 * Exact 3-character WMIs are tried first, then 2-character prefixes (e.g. "JT" for Toyota).
 *
 * [Manufacturer.groupId] keys the brand module: "vag" for the Volkswagen group brands served by brands/vag
 * (VW, Audi, Skoda, Seat, Cupra); Porsche has its own "porsche" id because its diagnostic stack differs.
 * Small-volume makers whose WMI ends in '9' (the real maker code is in positions 12-14) are not resolved.
 */
object WmiTable : WmiResolver {

    private const val DE = "Germany"
    private const val IT = "Italy"
    private const val FR = "France"
    private const val ES = "Spain"
    private const val CZ = "Czech Republic"
    private const val US = "United States"
    private const val JP = "Japan"
    private const val KR = "South Korea"
    private const val UK = "United Kingdom"

    private fun m(name: String, brand: String, group: String, country: String?) = Manufacturer(name, brand, group, country)

    private val exact: Map<String, Manufacturer> = buildMap {
        fun add(wmi: String, manufacturer: Manufacturer) { put(wmi, manufacturer) }

        // Volkswagen group (brands/vag)
        val vw = "Volkswagen"
        add("WVW", m("Volkswagen AG", vw, "vag", DE))
        add("WV1", m("Volkswagen Commercial Vehicles", vw, "vag", DE))
        add("WVG", m("Volkswagen AG (SUV)", vw, "vag", DE))
        add("VWV", m("Volkswagen Spain", vw, "vag", "Spain"))
        add("3VV", m("Volkswagen Mexico (SUV)", vw, "vag", "Mexico"))
        add("1V2", m("Volkswagen USA (SUV)", vw, "vag", "United States"))
        add("WV2", m("Volkswagen Commercial Vehicles (buses and vans)", vw, "vag", DE))
        add("WV3", m("Volkswagen Commercial Vehicles (trucks)", vw, "vag", DE))
        add("1VW", m("Volkswagen of America", vw, "vag", US))
        add("3VW", m("Volkswagen de México", vw, "vag", "Mexico"))
        add("9BW", m("Volkswagen do Brasil", vw, "vag", "Brazil"))
        add("AAV", m("Volkswagen South Africa", vw, "vag", "South Africa"))
        add("WAU", m("Audi AG", "Audi", "vag", DE))
        add("WA1", m("Audi AG (SUV)", "Audi", "vag", DE))
        add("WUA", m("Audi Sport GmbH", "Audi", "vag", DE))
        add("TRU", m("Audi Hungaria", "Audi", "vag", "Hungary"))
        add("TMB", m("Škoda Auto", "Skoda", "vag", CZ))
        add("VSS", m("SEAT / Cupra", "Seat", "vag", ES))

        // Porsche: separate diagnostic stack
        add("WP0", m("Porsche AG", "Porsche", "porsche", DE))
        add("WP1", m("Porsche AG (SUV)", "Porsche", "porsche", DE))

        // Stellantis
        add("ZFA", m("Fiat (Stellantis)", "Fiat", "stellantis", IT))
        add("ZFC", m("Fiat commercial vehicles (Stellantis)", "Fiat", "stellantis", IT))
        add("9BD", m("Fiat do Brasil (Stellantis)", "Fiat", "stellantis", "Brazil"))
        add("SUF", m("Fiat Auto Poland (Stellantis)", "Fiat", "stellantis", "Poland"))
        add("NM4", m("Tofaş (Fiat, Turkey)", "Fiat", "stellantis", "Turkey"))
        add("ZAR", m("Alfa Romeo (Stellantis)", "Alfa Romeo", "stellantis", IT))
        add("ZLA", m("Lancia (Stellantis)", "Lancia", "stellantis", IT))
        add("ZAM", m("Maserati (Stellantis)", "Maserati", "stellantis", IT))
        add("ZAC", m("Jeep, built in Italy (Stellantis)", "Jeep", "stellantis", IT))
        add("1C4", m("Jeep / Chrysler (Stellantis)", "Jeep", "stellantis", US))
        add("1C6", m("Jeep / Ram trucks (Stellantis)", "Jeep", "stellantis", US))
        add("3C4", m("Jeep / Chrysler, built in Mexico (Stellantis)", "Jeep", "stellantis", "Mexico"))
        add("3C3", m("Fiat / Chrysler, built in Mexico (Stellantis)", "Fiat", "stellantis", "Mexico"))
        add("1J4", m("Jeep (Stellantis)", "Jeep", "stellantis", US))
        add("1J8", m("Jeep (Stellantis)", "Jeep", "stellantis", US))
        add("VF3", m("Peugeot (Stellantis)", "Peugeot", "stellantis", FR))
        add("VR3", m("Peugeot (Stellantis)", "Peugeot", "stellantis", FR))
        add("VF7", m("Citroën (Stellantis)", "Citroen", "stellantis", FR))
        add("VR7", m("Citroën (Stellantis)", "Citroen", "stellantis", FR))
        add("VR1", m("DS Automobiles (Stellantis)", "DS", "stellantis", FR))
        add("W0L", m("Opel / Vauxhall (Stellantis)", "Opel", "stellantis", DE))
        add("W0V", m("Opel / Vauxhall (Stellantis)", "Opel", "stellantis", DE))
        add("VXK", m("Opel / Vauxhall, built by Stellantis Spain", "Opel", "stellantis", ES))

        // Ferrari: often listed next to Fiat but an independent maker
        add("ZFF", m("Ferrari", "Ferrari", "ferrari", IT))

        // Renault group
        add("VF1", m("Renault", "Renault", "renault", FR))
        add("VF2", m("Renault", "Renault", "renault", FR))
        add("UU1", m("Dacia (Renault group)", "Dacia", "renault", "Romania"))

        // BMW group
        add("WBA", m("BMW AG", "BMW", "bmw", DE))
        add("WBS", m("BMW M GmbH", "BMW", "bmw", DE))
        add("WBY", m("BMW i", "BMW", "bmw", DE))
        add("5UX", m("BMW (SUV, United States)", "BMW", "bmw", US))
        add("WMW", m("MINI (BMW group)", "Mini", "bmw", UK))

        // Mercedes-Benz group
        add("WDB", m("Mercedes-Benz", "Mercedes-Benz", "mercedes", DE))
        add("WDD", m("Mercedes-Benz", "Mercedes-Benz", "mercedes", DE))
        add("WDC", m("Mercedes-Benz (SUV)", "Mercedes-Benz", "mercedes", DE))
        add("WDF", m("Mercedes-Benz (vans)", "Mercedes-Benz", "mercedes", DE))
        add("W1K", m("Mercedes-Benz", "Mercedes-Benz", "mercedes", DE))
        add("W1N", m("Mercedes-Benz (SUV)", "Mercedes-Benz", "mercedes", DE))
        add("W1V", m("Mercedes-Benz (vans)", "Mercedes-Benz", "mercedes", DE))
        add("4JG", m("Mercedes-Benz (SUV, United States)", "Mercedes-Benz", "mercedes", US))
        add("WME", m("smart (Mercedes-Benz group)", "smart", "mercedes", DE))

        // Ford
        add("WF0", m("Ford Germany", "Ford", "ford", DE))
        add("NM0", m("Ford Otosan", "Ford", "ford", "Turkey"))
        add("1FA", m("Ford Motor Company", "Ford", "ford", US))
        add("1FM", m("Ford Motor Company (SUV)", "Ford", "ford", US))
        add("1FT", m("Ford Motor Company (trucks)", "Ford", "ford", US))

        // Toyota (Lexus resolved before the "JT" prefix)
        add("JTH", m("Lexus (Toyota)", "Lexus", "toyota", JP))
        add("SB1", m("Toyota Motor Manufacturing UK", "Toyota", "toyota", UK))
        add("VNK", m("Toyota Motor Manufacturing France", "Toyota", "toyota", FR))
        add("NMT", m("Toyota Motor Manufacturing Turkey", "Toyota", "toyota", "Turkey"))

        // Hyundai Motor Group
        add("KMH", m("Hyundai Motor Company", "Hyundai", "hyundai-kia", KR))
        add("TMA", m("Hyundai Motor Manufacturing Czech", "Hyundai", "hyundai-kia", CZ))
        add("NLH", m("Hyundai Assan (Turkey)", "Hyundai", "hyundai-kia", "Turkey"))
        add("KNA", m("Kia Corporation", "Kia", "hyundai-kia", KR))
        add("KND", m("Kia Corporation (SUV)", "Kia", "hyundai-kia", KR))
        add("U5Y", m("Kia Slovakia", "Kia", "hyundai-kia", "Slovakia"))
        add("U6Y", m("Kia Slovakia", "Kia", "hyundai-kia", "Slovakia"))

        // Volvo
        add("YV1", m("Volvo Cars", "Volvo", "volvo", "Sweden"))
        add("YV4", m("Volvo Cars (SUV)", "Volvo", "volvo", "Sweden"))

        // Japanese makers
        add("JN1", m("Nissan", "Nissan", "nissan", JP))
        add("JN8", m("Nissan (SUV)", "Nissan", "nissan", JP))
        add("SJN", m("Nissan Motor Manufacturing UK", "Nissan", "nissan", UK))
        add("VNV", m("Nissan, built in France", "Nissan", "nissan", FR))
        add("JHM", m("Honda", "Honda", "honda", JP))
        add("SHH", m("Honda of the UK Manufacturing", "Honda", "honda", UK))
        add("SHS", m("Honda of the UK Manufacturing (SUV)", "Honda", "honda", UK))
        add("JM1", m("Mazda", "Mazda", "mazda", JP))
        add("JMZ", m("Mazda (Europe)", "Mazda", "mazda", JP))
        add("JM3", m("Mazda (SUV)", "Mazda", "mazda", JP))
        add("JSA", m("Suzuki", "Suzuki", "suzuki", JP))
        add("TSM", m("Magyar Suzuki", "Suzuki", "suzuki", "Hungary"))
        add("MA3", m("Maruti Suzuki", "Suzuki", "suzuki", "India"))
        add("JMB", m("Mitsubishi Motors (Europe)", "Mitsubishi", "mitsubishi", JP))
        add("JF1", m("Subaru", "Subaru", "subaru", JP))
        add("JF2", m("Subaru (SUV)", "Subaru", "subaru", JP))

        // Tesla
        add("5YJ", m("Tesla (Fremont)", "Tesla", "tesla", US))
        add("7SA", m("Tesla (Austin)", "Tesla", "tesla", US))
        add("LRW", m("Tesla (Shanghai)", "Tesla", "tesla", "China"))
        add("XP7", m("Tesla (Berlin)", "Tesla", "tesla", DE))

        // British
        add("SAL", m("Land Rover (JLR)", "Land Rover", "jlr", UK))
        add("SAJ", m("Jaguar (JLR)", "Jaguar", "jlr", UK))
    }

    private val prefixes: Map<String, Manufacturer> = mapOf(
        "JT" to m("Toyota Motor Corporation", "Toyota", "toyota", JP),
    )

    override fun resolve(vin: Vin): Manufacturer? = resolveWmi(vin.wmi)

    /** Lookup by WMI text (3 characters, or at least the 2-character prefix). */
    fun resolveWmi(wmi: String): Manufacturer? {
        val key = wmi.trim().uppercase()
        if (key.length >= 3) exact[key.substring(0, 3)]?.let { return it }
        return if (key.length >= 2) prefixes[key.substring(0, 2)] else null
    }

    /** Every manufacturer entry, for diagnostics screens and tests. */
    val entries: Map<String, Manufacturer> get() = exact + prefixes
}
