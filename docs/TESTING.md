# Field testing guide (0.1.x)

## What the build does

| Area | Status |
|---|---|
| Adapter connection: Bluetooth classic (OBDLink MX+, vLinker), BLE clones, Wi-Fi (192.168.0.10:35000) | built, untested on hardware |
| Phase 1, any EU car since 2001/2004: VIN, protocol, live data (60 PIDs), stored/pending/permanent DTCs, freeze frame, readiness | tested on the simulator |
| Phase 2, VW group (MQB / MLB-evo, e.g. Passat B8 2015+, Q5 FY 2017+): control-module scan, identification, per-module DTCs, OBDb measurements | tested on the simulator |
| Older VW group cars using VW TP2.0 (Passat B6/B7, early Q5 8R) | not supported: the app says so after the scan |
| Mileage check: dashboard mileage vs the copies in engine, gearbox and ABS units, mileage stamps of past events, gearbox drive time / engine hours and average speed (VW group from OBDb rows; OBD-II PIDs A6 and 7F on any car) | tested on the simulator, including a rolled-back Passat demo |
| Clear DTCs (OBD service 04, UDS 0x14) | off by default; enable in Settings; guarded |
| Writes, coding, adaptations, routines | not in this build |

## Before the first drive

1. Install the APK (allow "install unknown apps" for the browser or file manager).
2. Open the app and try "Demo: VW Passat" to see every screen without a car.
3. Pair a Bluetooth classic adapter in Android settings first; BLE adapters are found with the in-app search.

## On the car

1. Ignition on (engine off or running, car parked).
2. Connect, open Vehicle, then Fault codes, Live data, Readiness, Control modules.
3. Open Mileage check and note what it shows next to the dashboard reading.
4. Open Diagnostics log and share it, even when everything works. Failed attempts are kept in the same log.

## What to report

The shared log plus: car model and year, adapter brand and model, phone model and Android version, and what
was on screen when something went wrong.

## Known limits

- Mileage check: an indication, not proof. Only identifiers published in OBDb are read; mileage stamps stored
  with UDS fault codes, and airbag/gateway/door units, are not read (no public layout). Differences up to
  max(100 km, 1 %) are treated as normal. "Possible rolled-back odometer" needs two or more units above the
  dashboard; one unit alone (possibly a used replacement) is shown as "some values differ". Stamps above the
  readable range (e.g. ABS stamps capped at 200 000 km by OBDb) are not compared. Every row shows its source (OBDb table and header/DID, or J1979 PID).

- The UI is in English with an Italian translation (it follows the phone language). DTC descriptions are
  English only; manufacturer-specific DTCs show their
SAE category and failure type only.
- The adapter is classified as certified only when it identifies as an STN chip (OBDLink); clones are fine for
  reading.
- Absent control modules cost about one second each during the scan.
