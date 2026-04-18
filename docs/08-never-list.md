# 08 — The NEVER List

Architectural exclusions, not deferrals. These are decisions made once and never revisited because reopening them invites the failure modes they're designed to prevent.

## NEVER: Holograms or custom rendering beyond standard models + screens

No 2D overlays, no 3D holograms, no AR-style world annotations, no custom shaders, no particle-system visualization, no anything that requires non-standard rendering pipelines.

**Why:** Custom rendering is the #1 source of lag in mods. Original OC's Hologram Projector was beloved but perpetually performance-constrained. Modern MC's chunk system makes the cost worse. We cap rendering complexity at "what every other mod does" — block models + GUI screens — and stay out of the lag-monster category.

**What this excludes specifically:**
- Hologram blocks
- Floating UI elements anchored in the world
- Custom particle effects
- 3D charts or visualizations
- Map/minimap overlays
- World-space text labels (use a Screen)

If a feature would require touching MC's world renderer beyond what BlockEntityRenderer can already do, it doesn't ship.

## NEVER: Storage virtualization

OC2 is logistics, not storage. Items always live in real inventories somewhere. We never invent abstract item pools, never let scripts "withdraw" without a physical source, never "store" without a physical destination.

**Why:** AE2 and Refined Storage are storage. They're excellent at it. Building another would be redundant, scope-doubling, and would make us compete with mods we should integrate with. By staying logistics-only:
- We coexist cleanly with AE2/RS in any modpack
- AE2/RS players use OC2 to orchestrate their existing storage
- Vanilla players orchestrate chests
- Both groups use the SAME scripts (the driver layer hides the difference)

**What this excludes specifically:**
- Item compression / abstract item registries
- "Cells" or "drives" that contain items virtually
- Registry of items independent of physical location
- "Crystal" or "ME" style storage networks
- Item duplication via compression

The script API verbs reflect this: `inventory.transfer(from, to, item, count)` requires both source AND destination. There is no `inventory.summon(item, count)`.

## NEVER: Cabling

No item conduits, no network cables, no adapter cables, no bundled cables, no wireless modems disguised as cables. The wireless channel system replaces all of it.

**Why:** Cabling was OpenComputers' biggest UX pain point and a major source of complexity in the mod's networking code. Path-finding lag, visual clutter, "why isn't this connected" debugging, and the complexity of cable update propagation were all costs paid for what is essentially a peripheral-to-computer association mechanism. Channels solve the same problem with zero of those costs.

**What this excludes specifically:**
- Any "cable" item or block
- Network bus blocks
- "Connection" requirements between physically adjacent computers/peripherals
- Attempts to recreate the OC cable mechanic "with improvements"

If players miss the visual-pipe aesthetic from other mods, they install Pipez or Functional Storage alongside OC2 and use those for visual flair while OC2 handles the logic wirelessly.

## NEVER: Spatial scanning at script call time

Discovery is registry-only. Adapters publish themselves into the channel registry on registration / heartbeat. Computers read the registry. **No iterating world chunks, no AABB queries against tile entities, no spatial sweeps when a script asks "what's around me."**

**Why:** World-iterating queries are O(loaded chunks × entities per chunk). On a server with multiple bases and lots of automation, the cost grows linearly with world size. Even one such call per tick per script becomes lag-monster territory at scale. The registry is O(channel members) — bounded by what the player explicitly opted in.

**What this excludes specifically:**
- `inventory.find_nearest(item)` style APIs
- `world.scan(radius, predicate)` style APIs
- "Auto-discover all chests in 32 blocks" features
- Anything that walks the world without explicit player opt-in via channel registration

## NEVER: Built-in higher-level features that should be scripts

KPI dashboards, autocrafters, ore processors, sorters, reactor controllers, farm orchestrators — none of these ship in the platform mod. They ship as separate downloadable sample/widget packs.

**Why:** Built-in features become defaults. Defaults get used because they exist. Player creativity atrophies. Mod authors burn out trying to maintain ever-more "official" features that would be better as scripts. The platform stays small; the community grows.

**What this excludes specifically:**
- A built-in autocrafter recipe controller
- A built-in sorter algorithm
- A built-in reactor PID controller
- A built-in dashboard widget library
- A built-in "hello world" tutorial that runs in-game

All of these CAN ship — just as separate sample mods, not built into the platform.

## NEVER: Ship script code in the platform mod

The platform mod contains compiled Kotlin only. No bundled Lua/JS files (other than the absolute minimal BIOS + boot needed to load the player's first script). Reference scripts ship in separate sample-pack mods.

**Why:** Same reason as the previous rule, plus: when the platform ships scripts, every platform update can break those scripts; when scripts live outside, they version independently and the platform stays scriptless and small.

## NEVER: Multi-loader (Forge + Fabric + NeoForge)

OC2 targets NeoForge only. Architectury rewrites are explicitly out of scope.

**Why:** This is a sofa project. Maintaining one loader is enough. The audience for OC2 (modded MC players who want programmable computers) overlaps heavily with NeoForge content-pack users; Fabric audience is performance-focused and less likely to want a heavy platform mod. If demand ever materializes for Fabric support, that's a future fork's problem.

## NEVER: Any feature that requires disabling SIP, root access, or out-of-game system manipulation

The platform stays inside Minecraft. No system-level integrations, no host filesystem access (beyond OC2's own data dir), no shell command execution from scripts, no arbitrary network connections (HTTP card has rate limits + permission gating).

**Why:** Player safety, server admin sanity, security, and avoiding the "mod that takes down servers" reputation.
