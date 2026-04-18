# 02 — Tier 1: Local Automation

The R1 (release 1) physical model. Single-block computers, wireless adapters, channel-based discovery, no cabling.

## Components

### Computer (block)
- **Single block** — not multi-block
- Has a **wifi channel** assignment (player-configurable via right-click GUI)
- Hosts one VM (Lua or JS) and runs one script per script-file at a time
- Persistent state (filesystem on its "disk", DB) survives world saves and chunk reloads
- Provides the script with the platform API (channel.devices, inventory, db, screen, etc.)

### Adapter (item, attached to a tile entity)
- Right-click on a target tile entity (chest, furnace, machine) to attach
- Has a **wifi channel** assignment (separate from the host TE)
- Has **1-2 cartridge slots** (slot count = tier; tier-1 adapter has 1 slot, tier-2 has 2)
- An adapter alone does nothing. Cartridges define what it can do.

### Cartridges (items, slot into adapters)
Compiled capabilities. Each cartridge type knows how to interact with a category of TE state:

| Cartridge | Capability |
|---|---|
| `InventoryReader` | List contents of an inventory TE |
| `InventoryWriter` | Insert items into an inventory TE |
| `RedstoneIO` | Read/write redstone signals on this block |
| `PropertyReader` | Read named properties on a TE (`temperature`, `progress`, `rpm`, etc.) |
| `PropertyWriter` | Set named properties (where the driver allows it) |
| `RecipeQueueWriter` | Queue a recipe on a craft/smelt-capable TE |
| `FluidReader` / `FluidWriter` | (R1 minimal) cauldrons, vanilla water |
| `Screen Linker` | (utility) snap multi-block screen assemblies together |

Cartridges are mod-shipped (compiled Kotlin); future R2 will add cartridges that know about modded TEs (AE2 inventory, RS network, Mekanism gas, etc.).

### Screen (block)
- Renders dynamic content for the player to see
- Tiered: Standard (character grid) and HD (vector primitives)
- Multi-block assembly via Screen Linker tool: place N×M screens adjacent, link them, the API treats them as one virtual canvas
- Touch input via `screen.on_click(handler)`

### Keyboard (block)
- Keyboard input device
- Player approaches and interacts; keystrokes route to the bound computer
- Required for stdin / interactive shells

## Channel mechanics

### Pairing
- Each Computer has a channel (e.g., `A1`, `factory-east`, anything player picks)
- Each Adapter has a channel
- Adapters with channel matching a Computer **automatically** appear in that Computer's device registry on next refresh tick (no scan, no manual link)
- Channels are **per-user scoped** — Player Alice's channel A1 is invisible to Player Bob's channel A1. No cross-talk on shared servers.

### Wireless range
- Default 32 blocks (configurable per-server)
- An Adapter on a matching channel that's outside range still appears in the registry but in `OUT_OF_RANGE` state
- `OUT_OF_RANGE` devices: visible to script (player can debug "ah, I need to move it closer or build a range enhancer"), but operations no-op / return error
- In-range is recomputed at registration, on heartbeat tick, and on adapter movement (piston push)

### Channel reassignment
- Adapter channel changed → drops from old registry immediately, re-registers on new channel within one refresh tick

## Discovery is registry-only

**This is a hard architectural rule. NEVER scan the world.** Adapters publish themselves into the channel registry on registration / heartbeat. Computers read the registry. No iterating chunks. No AABB queries. No spatial sweeps at script call time.

```lua
-- script-facing API
local devices = channel.devices()                    -- all devices on my channel (in or out of range)
local reachable = channel.reachable_devices()        -- only IN_RANGE subset
local invs = channel.devices_with("InventoryReader") -- filter by cartridge capability
```

Performance is O(channel registry size). Typically tiny. World-chunk scans are forbidden because they're laggy at scale and the laggy-mod era is over.

## Why no cabling

OpenComputers had cables. They were notorious for:
- Pathfinding lag (every cable update queries connection state)
- Visual clutter
- Player frustration at "why won't this work" debugging
- Massive complexity in the mod's networking code

Channels solve the same problem (peripheral-to-computer association) without any of that. Wireless is gameplay-friendly, performance-friendly, and code-friendly. **No cabling, ever.**

## Out for v1 (R1)

- Robots, drones (mobile entities — R2/R3)
- Microcontrollers, servers, racks (Tier-2 territory or skipped)
- 3D printer, geolyzer, motion sensors (deferred / never)
- Holograms (architectural NEVER — see `08-never-list.md`)
- Adapter cables (architectural NEVER — see above)
- Range enhancer modules (R2+, architecture supports)
