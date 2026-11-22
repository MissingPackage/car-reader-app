# Implementation contracts (batch 1)

These names and constructors are fixed so modules can be built in parallel. Ports live in `core` (see the
interfaces under `core/src/commonMain/kotlin/io/carreader/core`).

| Class | Module / package | Implements | Constructor |
|---|---|---|---|
| `ElmLink` | core `io.carreader.core.link.elm` | `DiagnosticLink` | `ElmLink(transport: Transport, log: TrafficLog = TrafficLog.None, clock: () -> Long = { 0L })` |
| `Obd2Service` | core `io.carreader.core.obd2` | `Obd2Port` | `Obd2Service(link: DiagnosticLink)` |
| `StandardPids` | core `io.carreader.core.obd2` | object | `all: List<PidDefinition>`, `byPid(pid: Int): PidDefinition?` |
| `DtcDescriptions` | core `io.carreader.core.obd2` | object | `describe(code: String): String?` (generic SAE codes) |
| `WmiTable` | core `io.carreader.core.vin` | `WmiResolver` (object) | — |
| `DefaultUdsClient` | core `io.carreader.core.uds` | `UdsClient` | `DefaultUdsClient(link: DiagnosticLink)` |
| `FailureTypes` | core `io.carreader.core.uds` | object | `describe(ftb: Int): String?` |
| `UdsBrandModule` | core `io.carreader.core.brand` | abstract `BrandModule` | reusable base for UDS brands |
| `VagBrandModule` | brands/vag `io.carreader.brands.vag` | `UdsBrandModule` | `VagBrandModule()` |
| `SimTransport` | sim `io.carreader.sim` | `Transport` | `SimTransport(scenario: SimScenario)` |
| `Scenarios` | sim `io.carreader.sim` | object | `passatB8()`, `audiQ5()`, `genericPetrol()`, `kLineLegacy()`, `cloneAdapter()` → `SimScenario` |
| `DefaultVehicleSession` | core `io.carreader.core.session` | `VehicleSession` | `DefaultVehicleSession(transport: Transport, brands: BrandRegistry, policy: GuardPolicy, confirmation: ConfirmationPort, audit: AuditLog, traffic: TrafficLog, clock: () -> Long)` |

## VAG UDS addresses (11-bit CAN, MQB / MLB-evo)

Response id = request id + 0x6A, except engine and transmission (OBD range).

| Code | ECU | Request | Response |
|---|---|---|---|
| 01 | Engine | 0x7E0 | 0x7E8 |
| 02 | Transmission | 0x7E1 | 0x7E9 |
| 03 | Brakes (ABS/ESC) | 0x713 | 0x77D |
| 08 | Air conditioning | 0x746 | 0x7B0 |
| 09 | Central electronics | 0x70E | 0x778 |
| 13 | Adaptive cruise control | 0x757 | 0x7C1 |
| 15 | Airbag | 0x715 | 0x77F |
| 16 | Steering column electronics | 0x70C | 0x776 |
| 17 | Instruments | 0x714 | 0x77E |
| 19 | Gateway | 0x710 | 0x77A |
| 42 | Door electronics, driver | 0x74A | 0x7B4 |
| 44 | Steering assist | 0x712 | 0x77C |
| 52 | Door electronics, passenger | 0x74B | 0x7B5 |
| 5F | Information electronics | 0x773 | 0x7DD |
| 76 | Park assist | 0x70A | 0x774 |

Community-sourced; verify against OBDb data where possible and on the vehicle.

## UDS identification DIDs read on scan

F187 spare part number, F189 software version, F191 hardware number, F1A3 hardware version,
F197 system name, F18C serial number, F190 VIN. VAG also: 0600 coding (read only).

## ELM327 conventions the driver relies on

Headers on (`ATH1`), spaces optional on parse, ISO-TP reassembly done in the driver from raw CAN frames.
For physical requests outside 0x7E0-0x7E7 the driver must set flow control explicitly:
`ATFCSH <request id>`, `ATFCSD 30 00 00`, `ATFCSM1`, and filter receive with `ATCRA <response id>`.
The simulator rejects a multi-frame exchange whose flow control header is wrong, like a real ECU would.
