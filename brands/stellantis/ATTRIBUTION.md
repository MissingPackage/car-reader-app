# Attribution

## OBDb signal sets

The Stellantis live-data tables in `src/commonMain/kotlin/io/carreader/brands/stellantis/data/` are derived from
the community database [OBDb](https://github.com/OBDb), © OBDb contributors:

| Table | Source repository | File | Commit |
|---|---|---|---|
| `ObdbPeugeot208` | https://github.com/OBDb/Peugeot-208 | `signalsets/v3/default.json` | `44fca26d9959373ca342a2132d4da07ae111fdd8` |
| `ObdbPeugeot308` | https://github.com/OBDb/Peugeot-308 | `signalsets/v3/default.json` | `dcd2725346b67312c31def82eeabd585faf39991` |
| `ObdbPeugeot3008` | https://github.com/OBDb/Peugeot-3008 | `signalsets/v3/default.json` | `b76c38cb09c0ca90e8e250f405c42451c5213241` |
| `ObdbPeugeotE208` | https://github.com/OBDb/Peugeot-e-208 | `signalsets/v3/default.json` | `d8d879861505a91c29d299aaf4940394d2698d76` |
| `ObdbFiat500` | https://github.com/OBDb/Fiat-500 | `signalsets/v3/default.json` | `4a2825f052a0d5860e6f9c5115809a8a4651d7fd` |
| `ObdbFiat500X` | https://github.com/OBDb/Fiat-500X | `signalsets/v3/default.json` | `76f175cbdee46bf30b1c239eb6b2bc9f9f7988c9` |
| `ObdbFiatPanda` | https://github.com/OBDb/Fiat-Panda | `signalsets/v3/default.json` | `1b8d1cf9b3b4e9645920433a47790b62398eadcf` |
| `ObdbJeepRenegade` | https://github.com/OBDb/Jeep-Renegade | `signalsets/v3/default.json` | `9f6ed8983dfff0a3383d74ba5c231727a6c47fad` |
| `ObdbJeepCherokee` | https://github.com/OBDb/Jeep-Cherokee | `signalsets/v3/default.json` | `beb08723f88fd24fe3e277500a86813665e59fec` |
| `ObdbJeepCompass` | https://github.com/OBDb/Jeep-Compass | `signalsets/v3/default.json` | `cec886a83e63bc06a56051495ccdf7763b731452` |
| `ObdbJeepGrandCherokee` | https://github.com/OBDb/Jeep-Grand-Cherokee | `signalsets/v3/default.json` | `caace7b14f6d6690038d41cceb1936b33d24aab3` |
| `ObdbJeepWrangler` | https://github.com/OBDb/Jeep-Wrangler | `signalsets/v3/default.json` | `22e60a75375ad816b348f38faf238193b340706b` |
| `ObdbJeepGladiator` | https://github.com/OBDb/Jeep-Gladiator | `signalsets/v3/default.json` | `b370a452dd787a2fb1ec8d9d9c8c3ede4feb1958` |

Retrieved 2026-11-22. Licensed under [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
(CC BY-SA 4.0). The derived tables are distributed under the same license.

Changes made: only Mode 0x22 signals are kept; signals OBDb flags as `dbg` (still under investigation) and
signals that need a diagnostic session other than default or extended (PSA session 0xC0) are left out; each
signal is flattened to one text line (request and response id, DID, session, model-year filter, id, group,
name and the `fmt` decoding fields), with 29-bit ids written in full (`DA10` → `18DA10F1`). Names are unchanged,
except that when two tables decode the same DID differently the app appends the model name to tell them apart.

The ECU addresses of the PSA and FCA catalogs (`PsaPlatform`, `FcaPlatform`) are the request headers and
response ids ("rax") used in these signal sets.

Other OBDb Stellantis repositories checked on 2026-11-22 had no verified Mode 0x22 signals yet (Peugeot 2008,
508, 5008; Citroën C3, C4, Berlingo; DS 7; Opel Corsa; Jeep Avenger; Fiat 500e; Alfa Romeo Giulia, Tonale;
Abarth 500, 595), so they are not bundled.

Regenerate with `tools/obdb/generate_obdb_table.py` (usage in its header).
