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
  brand/     BrandModule port, BrandRegistry, UdsBrandModule (reusable base for UDS brands)
  obd2/      SAE J1979 services, PID registry, DTC decoding
  uds/       ISO 14229 client (read services + guarded clear)
  vin/       VIN parsing, WMI → manufacturer
  link/      DiagnosticLink port + ELM327/STN driver (AT commands, CAN framing, multi-frame)
  transport/ Transport port (raw bytes)
brands/vag  Volkswagen group module (VW, Audi, Skoda, Seat, Cupra)
sim         In-process ELM327 + ECU simulator (tests and the app's demo mode)
```

Dependencies point inward: brands and the app depend on `core` ports, never the reverse.
A new brand is a new `BrandModule` (usually extending `UdsBrandModule`) registered in `BrandRegistry`.

## Safety

Every request whose service id can change ECU state (0x04, 0x11, 0x14, 0x27, 0x2E, 0x31, ...) is refused by
`GatedLink` unless it runs inside `WriteGuard.execute`, which requires: the setting enabled (off by default),
vehicle stationary, engine off, battery ≥ 12 V, a suitable adapter, and an explicit user confirmation.
Every attempt is audited. The app never offers remapping, emission-system changes, odometer or immobilizer
functions.

## Build

JDK 17 and the Android SDK (platform 35). `./gradlew :app:assembleDebug` builds the debug APK;
`./gradlew jvmTest` runs the core, brand and simulator tests on the JVM.
