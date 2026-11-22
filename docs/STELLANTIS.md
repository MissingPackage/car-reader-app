# Stellantis module (brands/stellantis)

Read-only phase 2 for Fiat, Abarth, Alfa Romeo, Lancia, Jeep (FCA architecture) and Peugeot, Citroën, DS,
Opel/Vauxhall (PSA architecture). One `BrandModule` (`StellantisBrandModule`, id `stellantis`) on the shared
`UdsBrandModule` scan, with one `StellantisPlatform` adapter per electrical architecture:

| Platform | Bus | Units probed (request → response) | Measurements |
|---|---|---|---|
| PSA (`PsaPlatform`) | CAN 11-bit | 6A8→688 engine, 752→652 BSI, 6A9→689 gearbox, 6B5→695 power steering, 74D→64D gear selector, 6B4→694 HV battery, 6A2→682 electric powertrain, 6A6→686 electric motor | OBDb Peugeot 208, 308, 3008, e-208 |
| FCA 29-bit (`FcaPlatform.extended`) | CAN 29-bit | 18DA10F1 engine, 18DA18F1 transmission, 18DA40F1 BCM, 18DA30F1 chassis, 18DAC7F1 tire/body unit (replies 18DAF1xx) | OBDb Fiat 500, 500X, Panda, Jeep Renegade, Compass, Cherokee, Grand Cherokee |
| FCA 11-bit (`FcaPlatform.standard`) | CAN 11-bit | 7E0 engine, 7E1 transmission, 740→4C0 and 742→4C2 tire pressure, 763→4E3 lighting, 7AB→52B telematics | OBDb Jeep Grand Cherokee, Wrangler, Gladiator |

Every address comes from the OBDb signal sets (request header and "rax"). Units no source names (ABS/ESP,
airbag, instrument cluster, ...) are not probed. Identification uses ISO 14229-1 DIDs only (F187, F18A, F18C,
F190, F191, F194, F195, F197); a unit that lacks one answers 0x31 and the value is skipped.

## Platform choice

- 29-bit bus → FCA. PSA cars diagnose on 11-bit CAN.
- 11-bit bus with a PSA-group WMI (VF3, VR3, VF7, VR7, VR1, VXK, W0V) → PSA.
- 11-bit bus with a North American WMI (1C4, 1C6, 1J4, 1J8, 3C3, 3C4) → FCA 11-bit units only (measurements from
  the Jeep tables, also shown on Ram, Dodge and Chrysler models of those WMIs). PSA ids are
  never sent there: on those cars the OBD port can sit on a production bus, where foreign ids are not harmless.
- 11-bit bus with another FCA-brand WMI → PSA (recent Jeep Avenger, Fiat 600, Lancia Ypsilon... are PSA-based),
  marked uncertain: older FCA-platform cars on 11-bit CAN use addresses no source names, so a silent scan gets
  `UNKNOWN_UNITS_NOTE` instead of the gateway note.
- No VIN on 11-bit CAN → nothing is probed (`NO_VIN_NOTE`): each platform's ids could land on the other's
  production bus.
- Accepted risk: a non-North-American FCA-brand car on 11-bit CAN (WMIs ZFA, 9BD, NM4, SUF, ...) gets the PSA
  ids. Recent cars of those brands sold in Europe are PSA-based, and older European FCA cars diagnose on
  29-bit CAN in the OBDb data; a pre-PSA 11-bit FCA car will answer nothing and get `UNKNOWN_UNITS_NOTE`.
- W0L (Opel/Vauxhall of the General Motors era) is not claimed: different architecture. A module forced onto a
  car it does not support never blames a gateway.

## Measurements when the exact model is unknown

The VIN does not tell the model reliably in a way this app can source, and the session does not know the
model year, so the platform's tables are merged (`ObdbSignalTable.merged`). Rows with the same DID, bit layout,
scaling, unit and enumeration collapse into one. When kept rows read overlapping bits of the same DID with
different layouts, each stays and its name gets its table's model, plus its model years when the variants come
from the same table ("Battery voltage, BCM (Fiat 500, up to 2019)"). Rows that still share a name get their DID.
So a value decoded with another model's layout is always labelled as such; the user has to pick the row that
matches the car.

OBDb `dbgfilter` (model years for which a row is not yet verified) is not applied: the year is unknown. OBDb
`fcm1` (manual flow control) is not needed: the driver sets explicit flow control for every physical 11-bit id
outside 7E0-7E7 and the adapter's automatic flow control handles 29-bit 18DAF1xx replies.

## Secure Gateway

FCA fitted a Secure Gateway Module to most vehicles from model year 2018: basic reads pass without
authentication, clearing DTCs and active tests need an AutoAuth-registered tool (ALLDATA, Bosch, NEXIQ
documentation). Tool vendors describe recent PSA-platform cars as blocking unauthenticated requests; no source
gives the per-model dates or how the gateway answers. The module therefore reports what it observes:

- `ACCESS_DENIED`: a unit other than the OBD emission units answered its presence probe or its DTC read with
  0x33 (securityAccessDenied) or 0x34 (authenticationRequired) → note `GATEWAY_DENIED_NOTE`. Refused single
  identification DIDs do not count (units often lock the VIN or serial); the probe DID F187 does, since it is
  the first request a unit gets.
- `ONLY_OPEN_UNITS_ANSWERED`: standard OBD-II answered but no brand unit did (at least 3 probed) → note
  `GATEWAY_SILENT_NOTE`, which also names the other causes (ignition, unknown addresses).

The heuristic lives in core (`SecureGatewayCheck`) so other brands can reuse it.

## Simulator

`sim` `StellantisScenarios`: `peugeot308()` (PSA, 11-bit), `fiat500x()` (FCA, 29-bit, extended-session
measurements) and `fiat500x(secureGateway = true)` (BCM answers 0x33), `citroenGatewayBlocked()` (OBD-II only).
Part numbers and serials are made up; DID layouts follow the bundled tables.

## Known gaps

- Mileage check (`StellantisCounters`): PSA engine 6A8/D49C, gearbox 6A9/D802 and the e-208 electric units'
  D49C. OBDb verifies these rows for some model years only (`dbgfilter`), which `UNVERIFIED_NOTE` tells the
  user; with no instrument-cluster source there is no dashboard value, so the worst verdict is DIFFERENCES.
  The PSA engine is read both at 6A8 and as the OBD-II responder 7E8, so the analyzer counts it twice.
  FCA: no brand counter (Fiat rows are `dbg`; the Jeep 7E0/043B row comes from Jeep tables while the same
  WMIs cover Ram, Dodge and Chrysler).
- PSA session 0xC0 measurements (e.g. PSA engine RPM D400) are not read: the read-only gate opens only the
  default and extended sessions.
- Pre-2010 PSA/FCA cars using KWP2000 on CAN or K-line: not covered.
- DTC descriptions are the generic SAE texts; manufacturer codes show the category and failure type only.
