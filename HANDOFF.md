# Handoff

**Objective:** a multi-brand OBD app from one adapter. **Current:** v0.1.1 (English-only UI; repo content is English only) pre-release (2026-11-22): phase 1
(standard OBD-II) + phase 2 for the VW group, field test pending on a VW Passat and an Audi Q5.
**Gap:** no real-adapter or real-car evidence yet; everything is verified on the simulator and a timing model.

## State
- main e144d82; release https://github.com/MissingPackage/car-reader-app/releases/tag/v0.1.0
  (APK sha256 ad10a1cc4521fbf3da4a7224b735c320c092a47ea54066383583114a46a18038, signed with app/debug.keystore).
- 251 JVM tests green (core, sim, brands/vag, app incl. 26 Paparazzi renders). Lint 0 errors.
- Each batch had an adversarial review; all critical and important findings fixed.

## Next (in order)
1. Collect the tester's shared logs (Passat, Q5). Check: adapter classification, protocol, VAG scan presence,
   0x78 handling, BLE GATT layout if BLE.
2. Fix what the real logs show; add their traces as simulator scenarios (regression from real data).
3. Follow-ups: extended-session caching for VAG live reads (2 ignored tests in UdsReviewTest), KWP2000/VW TP2.0 transport for pre-MQB cars (needs an EcuAddress transport field),
   Paparazzi goldens are not committed (verify only works locally).
4. Phase 3 (writes) only after real-car evidence: per-brand gateway research (VW SFD) first.

## Fog
- Whether vLinker answers `STI` (decides "certified").
- Real model years of the test cars (OBDb variants are year-filtered).
