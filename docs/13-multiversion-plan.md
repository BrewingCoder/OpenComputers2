# 13 — Multi-version Plan

How OC2 will eventually support multiple Minecraft versions (1.21.1, 1.21.5, 1.21.10, future) from a single codebase, and what we do today to pre-position for that future without paying the structural cost yet.

## Problem statement

MC versions evolve fast. Big breaking API changes ship in point releases:
- 1.21.4: new `items/<name>.json` item-definition system
- 1.21.x: NBT API rewrite (`CompoundTag + HolderLookup` → `ValueInput/ValueOutput`)
- 1.21.10: Screen rendering rewrite (`GuiGraphics` → `GuiGraphicsExtractor`)

Modpack adoption tends to lag — FTB packs and Direwolf still target 1.21.1 in April 2026 even though 1.21.10 exists. So a real mod must ship multiple versions simultaneously, often for years.

The naive approach — branches per MC version — produces N parallel codebases, N×M bug-fix workload, N×M test surface. Death spiral.

## The solution: common core + per-version platform modules

Adopted by every successful multi-version mod (Cobblemon, JEI/REI, Create, etc.). Same shape:

```
oc2/
├── core/                     ← MC-version-AGNOSTIC. Shared by all targets.
│   ├── platform/            ← ChannelRegistry, Position, ChannelRegistrant, scheduler
│   ├── network/contracts/   ← Payload data types (no MC types)
│   ├── driver/api/          ← IInventoryReader, IPropertyReader, etc. interfaces
│   ├── vm/                  ← Lua/JS host (independent of MC entirely)
│   ├── db/                  ← SQL layer (uses sqlite-jdbc; no MC)
│   └── ports/               ← Interfaces MC must implement
│       ├── PlatformBlockEntity     ← interface — what core needs from a BE
│       ├── PlatformPlayer
│       ├── PlatformPos             ← already built as Position
│       ├── PlatformInventory
│       └── McNetworking            ← packet send/receive abstraction
│
├── mc-1.21.1/                ← MC 1.21.1 specific
│   ├── ComputerBlock121.kt          ← extends MC Block
│   ├── ComputerBlockEntity121.kt   ← extends MC BlockEntity, implements PlatformBlockEntity
│   ├── McPlatform121.kt            ← implements core's port interfaces
│   ├── adapters/                   ← MC types ↔ core types translation
│   ├── networking/                 ← MC payload wrappers ↔ core contracts
│   ├── client/                     ← MC Screen / GuiGraphics-based UI
│   └── resources/                  ← MC version-specific assets
│
├── mc-1.21.5/                ← when added: parallel structure
└── mc-1.21.10/               ← when added: GuiGraphicsExtractor-based UI
```

**The single architectural rule:** *no file in `core/` ever imports `net.minecraft.*`.* Captured as Rule D in [`11-engineering-rules.md`](11-engineering-rules.md).

## What we already have right (free wins)

The Rule B refactor work (testability seams) IS the same structural pattern as the multi-version split:
- `ChannelRegistrant` interface — registry doesn't know about BlockEntity
- `Position` value type — no `BlockPos` leakage into core
- `ChannelRegistry` is pure logic, depends on only `ChannelRegistrant` + `Position`

These are already "core-shaped" — they'd move to `core/platform/` in the split with no changes.

## What's MC-coupled today (would move to `mc-1.21.1/`)

- `ComputerBlock`, `ComputerBlockEntity` — extend MC classes, would become `Computer121`-style
- `ModBlocks`, `ModBlockEntities`, `ModItems`, `ModTabs` — registration is MC-specific per version
- `ComputerScreen`, `ClientHandler` — GUI heavily MC-coupled
- `SetChannelPayload`, `OC2Payloads` — packet wire format is MC-specific; the *data* is core
- Resources (`assets/`, `data/`) — most are version-portable, but item-definition JSONs (1.21.4+) aren't

## When to execute the split

**NOT YET.** The trigger is "we have a concrete second-version target." Premature splitting freezes the port interfaces before we know what they need to support.

Specific signals to act:
- A modpack we want to support upgrades to 1.21.10 (or whatever next version)
- A second user runs into "hey I'm on 1.21.5, can you support that?"
- The 1.21.10 GuiGraphicsExtractor API has shipped enough docs/example mods that we can read it confidently

When ANY of those happens, we execute the split as a single 1-2 week refactor:
1. Create `core/` and `mc-1.21.1/` Gradle subprojects
2. Move existing files according to the table above
3. Define port interfaces by extracting them from current MC-touching code's actual usage
4. Wire core ↔ mc-1.21.1 via service-loader / NeoForge event bus
5. Verify build matrix (`./gradlew :mc-1.21.1:build`)
6. Add `mc-1.21.5/` (or whatever) parallel implementation
7. Verify both build and run

## What we do today (the prep work)

1. **Apply Rule D to every new file.** No `net.minecraft.*` imports in core packages (`platform/`, `network/contracts/`, future `driver/api/`, `vm/`, `db/`).
2. **Use platform value types.** When core needs to refer to a position, use `Position`, not `BlockPos`. When core needs an item, use a future `PlatformItemStack`, not MC's `ItemStack`.
3. **Define interfaces at every boundary.** When MC code needs to communicate something to core, the channel is an interface. This is what we did with `ChannelRegistrant`.
4. **Don't refactor existing MC-touching code.** It's fine in its current packages — it's already where it would be in `mc-1.21.1/`. The `block/` and `client/` packages will become the contents of the per-version module.
5. **Capture port interface candidates as we encounter them.** When we feel the need for a new abstraction (e.g., "the VM needs to send a packet"), make the seam now even though we're single-module. That's the future port interface.

## Estimated cost when we do split

- **Architectural design**: ~3-5 days (define ports, prototype `mc-1.21.1/` implementation)
- **Code migration**: ~2-3 days (move files, fix imports, verify nothing broke)
- **Add second version**: ~3-5 days (implement ports against new MC APIs, fix incompatibilities)
- **Total for first multi-version capable build**: ~2 weeks calendar

That's a small fraction of the cost of maintaining N parallel branches forever.

## Reference: how other mods do this

- **Cobblemon** uses Architectury (multi-loader plugin, also handles multi-version inside one loader)
- **JEI/REI** uses per-version modules with shared API jar
- **Create** uses Architectury + custom build setup
- **Modrinth's mods** typically Architectury

For a NeoForge-only mod (which OC2 is — see [`08-never-list.md`](08-never-list.md)), we don't need Architectury. Simple Gradle subprojects + a small services-locator pattern is enough.

## Related rules

- **Rule A** (test everything) — easier when core is pure logic
- **Rule B** (abstract for testability) — same seams serve cross-version
- **Rule C** (over-engineering OK for platforms) — the multi-version structure IS the over-engineering, paying off later
- **Rule D** (no MC in core) — the discipline that makes the split possible without rewrites
