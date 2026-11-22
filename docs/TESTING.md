# Field testing guide (0.1.x)

## What the build does

| Area | Status |
|---|---|
| Adapter connection: Bluetooth classic (OBDLink MX+, vLinker), BLE clones, Wi-Fi (192.168.0.10:35000) | built, untested on hardware |
| Phase 1, any EU car since 2001/2004: VIN, protocol, live data (60 PIDs), stored/pending/permanent DTCs, freeze frame, readiness | tested on the simulator |
| Phase 2, VW group (MQB / MLB-evo, e.g. Passat B8 2015+, Q5 FY 2017+): control-module scan, identification, per-module DTCs, OBDb measurements | tested on the simulator |
| Older VW group cars using VW TP2.0 (Passat B6/B7, early Q5 8R) | not supported: the app says so after the scan |
| Clear DTCs (OBD service 04, UDS 0x14) | off by default; enable in Settings; guarded |
| Writes, coding, adaptations, routines | not in this build |

## Before the first drive

1. Install the APK (allow "install unknown apps" for the browser or file manager).
2. Open the app and try "Demo: VW Passat" to see every screen without a car.
3. Pair a Bluetooth classic adapter in Android settings first; BLE adapters are found with the in-app search.

## On the car

1. Ignition on (engine off or running, car parked).
2. Connect, open Vehicle, then Fault codes, Live data, Readiness, Control modules.
3. Open Diagnostics log and share it, even when everything works. Failed attempts are kept in the same log.

## What to report

The shared log plus: car model and year, adapter brand and model, phone model and Android version, and what
was on screen when something went wrong.

## Known limits

- DTC descriptions are in English (Italian UI labels them as such); manufacturer-specific codes show their
  SAE category and failure type only.
- The adapter is classified as certified only when it identifies as an STN chip (OBDLink); clones are fine for
  reading.
- Absent control modules cost about one second each during the scan.
