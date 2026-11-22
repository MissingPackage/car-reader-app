# Car Reader

Multi-brand OBD diagnostics for Android, built on a shared Kotlin Multiplatform core.
Reference adapter: OBDLink MX+ (STN chip). Any ELM327-compatible adapter works for standard OBD-II.

## Architecture: ports and adapters

```
app (Android, Compose)          UI, Android transports (Bluetooth SPP, BLE, Wi-Fi), settings, dialogs
  │ uses VehicleSession (facade port)
core (KMP: jvm + android)
  session/   VehicleSession implementation: composes everything below
  guard/     WriteGuard + GatedLink: the single gate for every mutating request
  mileage/   CounterCatalog port (one per brand), CounterReader, MileageAnalyzer: cross-unit mileage check
  brand/     BrandModule port, BrandRegistry, UdsBrandModule (reusable base for UDS brands)
  obd2/      SAE J1979 services, PID registry, DTC decoding
  uds/       ISO 14229 client (read services + guarded clear)
  vin/       VIN parsing, WMI → manufacturer
  link/      DiagnosticLink port + ELM327/STN driver (AT commands, CAN framing, multi-frame)
  transport/ Transport port (raw bytes)
brands/vag  Volkswagen group module (VW, Audi, Skoda, Seat, Cupra)
brands/stellantis  Stellantis module: PSA and FCA platform adapters (Fiat, Alfa Romeo, Lancia, Jeep, Abarth,
            Peugeot, Citroën, DS, Opel/Vauxhall), see docs/STELLANTIS.md
sim         In-process ELM327 + ECU simulator (tests and the app's demo mode)
```

Dependencies point inward: brands and the app depend on `core` ports, never the reverse.
A new brand is a new `BrandModule` (usually extending `UdsBrandModule`) registered in `BrandRegistry`.
A brand joins the mileage check by returning a `CounterCatalog` from `BrandModule.counterCatalog`: a list of
sourced counters (dashboard total, other units' totals, event stamps, operating time). Reading and comparison
are shared.

## Safety

Every request whose service id can change ECU state (0x04, 0x11, 0x14, 0x27, 0x2E, 0x31, ...) is refused by
`GatedLink` unless it runs inside `WriteGuard.execute`, which requires: the setting enabled (off by default),
vehicle stationary, engine off, battery ≥ 12 V, a suitable adapter, and an explicit user confirmation.
Every attempt is audited. The app never offers remapping, emission-system changes, odometer or immobilizer
functions.

## Status

Version 0.1.4: phase 1 (standard OBD-II), phase 2 for the Volkswagen group and the Stellantis group
([docs/STELLANTIS.md](docs/STELLANTIS.md)), and a read-only mileage check that compares the dashboard with
the other control units. See
[docs/TESTING.md](docs/TESTING.md) for what is covered and how to field-test, and
[docs/screenshots](docs/screenshots) for every screen.

Tests run against `sim`, an in-process ELM327/STN adapter and vehicle simulator (VW Passat B8, Audi Q5 FY,
Peugeot 308, Fiat 500X with and without a secure gateway, a gateway-blocked Citroën C4, a K-line petrol car,
a clone adapter), plus a timing-faithful adapter model for the driver.

## Data sources

VW group measurements are derived from [OBDb](https://github.com/OBDb) (CC BY-SA 4.0), see
[brands/vag/ATTRIBUTION.md](brands/vag/ATTRIBUTION.md); Stellantis measurements and unit addresses likewise, see
[brands/stellantis/ATTRIBUTION.md](brands/stellantis/ATTRIBUTION.md).

## Build

JDK 17 and the Android SDK (platform 35). `./gradlew :app:assembleDebug` builds the debug APK;
`./gradlew :core:jvmTest :sim:jvmTest :brands:vag:jvmTest :brands:stellantis:jvmTest :app:testDebugUnitTest` runs the tests on the JVM
(app screens are rendered with Paparazzi). Debug builds are signed with the committed `app/debug.keystore`, so
new builds install over old ones.
