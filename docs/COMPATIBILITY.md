# Compatibility - AuctionHousePro

Current development source snapshot: **1.1.1-SNAPSHOT**, verified **2026-10-05**. Target range: **Paper 1.20.6-26.2**. The API floor remains **Paper 1.20.6**, `api-version: '1.20.6'`, with **Java 21 bytecode**. Paper 26.1+ uses Java 25. This snapshot has no new tag or GitHub Release asset.

Exact tested artifact: `AuctionHousePro-1.1.1-SNAPSHOT.jar`

```text
SHA-256: 1bd7ecc2aa5cb4e5ab4808fa8c7f57ac0d1baa2d5f3609f7a7958ccfbc7c7702
```

This project has no configured unit suite; its build passed. The configured nine-project suites executed **426 unit tests** in total.

| Minecraft | Paper build | Java | Suite groups passed | Failed |
| --- | ---: | ---: | ---: | ---: |
| 1.20.6 | 151 | 21 | 124 | 0 |
| 1.21.11 | 132 | 21 | 124 | 0 |
| 26.2 | 130 | 25 | 124 | 0 |

The **124 groups per run / 372 total** cover all nine plugins together. They include aggregate menu/language assertions and reused domain regressions, not 372 independent gameplay scenarios or a per-project count. Only these three runtime versions were executed for these exact hashes; the target range does not imply every intermediate version was retested.

Current runtime coverage includes plugin startup/API metadata, actual independent classloader protocol-1 links, both-side provider/configuration gates, invalid reload snapshot retention, staged YAML parsing and durable UUID language synchronization. The original three plugins additionally exercise standard localized item names; the six newer plugins include their existing compact domain/inventory/database regressions. PlayerBusiness read-only membership lookup and optional AuctionHousePro/SmartNPCWorkers benefits use actual plugin services with fixture actors. See the product check labels and exact scope in [verification.json](verification.json).

Actors are offline proxies, not connected Minecraft clients. No current claim is made for rendered UI/resource packs, full linked dungeon-party fights, actual third-party territory/licensed-provider combinations, production economy/MySQL matrices, OS-level crash recovery, plain Spigot, Folia or Forge/Fabric. Suite observations are best-effort and live-only; owning plugins retain their durable financial receipts.

Product integration rules: [SUITE-INTEGRATIONS.md](SUITE-INTEGRATIONS.md), [SUITE-LINKS.md](SUITE-LINKS.md), [INTEGRATIONS.md](INTEGRATIONS.md). Configuration: [CONFIGURATION.md](CONFIGURATION.md). Recovery: [RECOVERY.md](RECOVERY.md).

Historical evidence is preserved in [COMPATIBILITY-RELEASE-1.1.0.md](COMPATIBILITY-RELEASE-1.1.0.md). Those records apply only to their own artifact hashes.
