# OpenComputers2

A Kotlin/NeoForge platform for in-world programmable computers in Minecraft 1.21+.

Spiritual successor to [OpenComputers](https://github.com/MightyPirates/OpenComputers) by Florian "Sangar" Nücke et al. — built fresh, not ported. Original OC code (MIT + CC0) is referenced extensively for design, asset inspiration, and patterns; OC2 is its own implementation.

## Pitch

> **A logistics-orchestration platform for modded Minecraft.** Programmable computers (Lua + JS), wireless device adapters, a k8s-style control plane for cross-base coordination. Players write the automation; we ship the platform.

## Status

**Pre-alpha.** v0.0.1 is the smoke-test scaffold — proves the build chain works and the mod loads in Minecraft. No actual platform features yet.

## Quickstart (developers)

```bash
./gradlew runClient   # builds + launches MC with OC2 loaded
./gradlew build       # builds the mod jar
```

Requires:
- JDK 21
- IntelliJ IDEA (recommended) with the Minecraft Development plugin

## Architecture

Full design in [`docs/`](./docs):
- [`docs/00-overview.md`](docs/00-overview.md) — TL;DR of the whole system
- [`docs/01-platform-vs-software.md`](docs/01-platform-vs-software.md) — the core scope discipline
- [`docs/02-tier1-local.md`](docs/02-tier1-local.md) — Computer + Adapter + Cartridges + channel discovery
- [`docs/03-tier2-control-plane.md`](docs/03-tier2-control-plane.md) — k8s-inspired global orchestration
- [`docs/04-vms-lua-js.md`](docs/04-vms-lua-js.md) — dual VM support
- [`docs/05-drivers.md`](docs/05-drivers.md) — compiled driver SPI for world interaction
- [`docs/06-database.md`](docs/06-database.md) — SQL API per script
- [`docs/07-graphics.md`](docs/07-graphics.md) — screen API, widgets ship as scripts
- [`docs/08-never-list.md`](docs/08-never-list.md) — what we will never do, and why
- [`docs/09-future.md`](docs/09-future.md) — drones, range enhancers, sample programs
- [`docs/10-references.md`](docs/10-references.md) — OC, ID, Isy's IM, Sandalle's reactor controller

## License

MIT. Honors and inherits from the original OpenComputers MIT code license.

## Credits

- Florian "Sangar" Nücke and the MightyPirates OpenComputers contributors — the original mod that inspired this entire project
- thedarkcolour — Kotlin for Forge / NeoForge, KotlinModdingSkeleton, the foundation that makes this practical in Kotlin
- Cyclops (Integrated Dynamics) — driver-architecture patterns for world interaction
- Sandalle, Kasra-G, Orangeninja1 — Big/Extreme Reactors CC scripts that defined the monitoring/control use case
- Isy — Space Engineers Inventory Manager that defined the logistics use case
