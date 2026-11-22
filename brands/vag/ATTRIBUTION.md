# Attribution

## OBDb signal sets

The VAG live-data tables in `src/commonMain/kotlin/io/carreader/brands/vag/data/ObdbVolkswagenPassat.kt` and
`ObdbAudiQ5.kt` are derived from the community database [OBDb](https://github.com/OBDb):

| Table | Source repository | File | Commit |
|---|---|---|---|
| `ObdbVolkswagenPassat` | https://github.com/OBDb/Volkswagen-Passat | `signalsets/v3/default.json` | `06ae8c9fc20a9bc658e7a91fa07491994d0efd50` |
| `ObdbAudiQ5` | https://github.com/OBDb/Audi-Q5 | `signalsets/v3/default.json` | `a5e9ec247b42831654af1b2c0b43a39c009711c0` |

Retrieved 2026-11-22. Licensed under [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
(CC BY-SA 4.0). The derived tables are distributed under the same license.

Changes made: only Mode 0x22 signals on 11-bit CAN headers are kept; signals OBDb flags as `dbg` (still under
investigation) and signals on 29-bit headers are left out; each signal is flattened to one text line
(header, DID, session, model-year filter, id, group, name and the `fmt` decoding fields). Names are unchanged.

Regenerate with `tools/generate_obdb_table.py` (usage in its header).

The decoder (`io.carreader.core.brand.SignalFormat`) follows the field semantics of the OBDb reference
implementation in https://github.com/OBDb/.schemas (`python/can/signals.py`); no code was copied.
