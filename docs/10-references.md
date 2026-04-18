# 10 — References

The mods, scripts, and codebases that informed OC2's design. Crib sources and design forcing-functions.

## Cribs (in `~/repos/_reference/`)

### OpenComputers — the parent
**Repo:** https://github.com/MightyPirates/OpenComputers
**License:** MIT (code) + CC0 (assets) — gift-tier permissive
**Original author:** Florian "Sangar" Nücke + many contributors
**Status:** last branch `master-MC1.16` (project effectively dormant)

The original Minecraft programmable-computer mod that defined the genre. OC2 is a spiritual successor in Kotlin, MIT-licensed in honor of the original. We freely reference OC's code and assets. Credit prominently in README + manual.

What we crib: block premise (Computer/Screen/Keyboard), syscall surface ideas, OS concepts (filesystem layout, peripheral abstraction). What we explicitly do NOT inherit: cabling, holograms, scope creep into a kitchen-sink mod.

### Kotlin for Forge — the language adapter
**Repo:** https://github.com/thedarkcolour/KotlinForForge
**License:** LGPL-2.1
**Author:** thedarkcolour

Makes Kotlin work cleanly with NeoForge's mod loader. Required dependency at runtime. Reference for "how Kotlin actually plugs into NeoForge."

### KotlinModdingSkeleton — our scaffold base
**Repo:** https://github.com/thedarkcolour/KotlinModdingSkeleton (branch `26.1-neoforge`)
**Author:** thedarkcolour

The official "clone-to-start" Kotlin mod template. Our v0.0.1 scaffold derives from this.

### Future-MC — production Kotlin patterns
**Repo:** https://github.com/thedarkcolour/Future-MC
**License:** see repo
**Author:** thedarkcolour

Real production Kotlin mod, mid-scale. Reference for blocks/items/recipes/registry/data gen patterns in Kotlin.

### tgbridge — modern small Kotlin NeoForge example
**Repo:** https://github.com/vanutp/tgbridge
**Author:** vanutp

Telegram bridge mod, recent (April 2026). Good for "what does idiomatic 2026 Kotlin+NF look like" in a small focused codebase.

### (Deferred) Cobblemon — heavyweight Kotlin reference
**Repo:** https://gitlab.com/cable-mc/cobblemon (real dev location; GitHub mirror is sparse)
**License:** MPL-2.0
**Status:** very active, ~278 MB

Massive Kotlin codebase. Useful as a "how do you organize a giant Kotlin mod" reference if we ever need it. Skipping clone for now (overkill for current needs).

## Forcing functions (use cases that defined the SPI)

### Integrated Dynamics — driver-architecture inspiration
**Repo:** https://github.com/CyclopsMC/IntegratedDynamics
**License:** MIT
**Status:** active on `master-26`

Modded MC's reference for open-architecture world interaction. The driver registry pattern in OC2's [`05-drivers.md`](05-drivers.md) is shaped by ID's parts/networks/aspects model. We didn't take Path B (depend on ID) — we took Path A (build our own driver layer with an SPI clean enough that someone could write an ID driver later).

### Isy's Inventory Manager (Space Engineers) — logistics use case
**Repo:** https://github.com/dorimanx/Isys-Inventory-Manager
**Steam guide:** https://steamcommunity.com/sharedfiles/filedetails/?id=1226261795

The canonical "base inventory management" script in SE. Tracing it through OC2's planned API revealed one missing primitive: `IRecipeQueueWriter` (queue a recipe on a crafting/assembler block). Now in the SPI plan.

The OC2-Quartermaster sample mod will be the OC2-equivalent of Isy's IM.

### Sandalle's Big Reactors Controller (CC) — monitoring/control use case
**Repo:** https://github.com/sandalle/minecraft_bigreactor_control
**Variants:** https://github.com/Kasra-G/ReactorController, https://github.com/Orangeninja1/ReactorControl

The reactor control script everyone playing modded MC writes. Tracing it through OC2's API revealed the most-load-bearing missing SPI primitive: **`IPropertyReader` / `IPropertyWriter`** — generic named-property bag access. Most mods don't expose just inventories; they expose properties (`temperature`, `progress`, `fuel_level`, `power`, `active`, `control_rod_insertion`, `rpm`, `mode`). Without this SPI, scripts can read inventories and almost nothing else. Now in the SPI plan.

The OC2-ReactorOps sample mod will be the OC2-equivalent.

## Design philosophy references (not code, but informed the architecture)

- **Kubernetes** — service discovery, control plane / worker node split, manifest-driven desired state, replicas, kubelet, ConfigMaps. The Tier-2 Control Plane's vocabulary is borrowed verbatim because the audience already speaks it.
- **Linux device drivers / OS theory** — driver registry pattern, capability advertisement, generic interface dispatch
- **k8s CSI (Container Storage Interface)** — pluggable storage backends behind a stable interface; same pattern for our world-interaction drivers

## Reference layout

```
~/repos/_reference/
├── OpenComputers/             ← MightyPirates, MIT+CC0
├── KotlinForForge/            ← thedarkcolour, LGPL-2.1
├── KotlinModdingSkeleton/     ← thedarkcolour, scaffold base
├── Future-MC/                 ← thedarkcolour, production Kotlin
└── tgbridge/                  ← vanutp, small modern Kotlin NF
```

All read-only crib sources. We never compile against them, never depend on them at runtime. They're library shelves, not dependencies.
