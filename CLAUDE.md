# OpenComputers2 — orientation for AI agents

**Project type:** Spiritual port of OpenComputers — Kotlin/NeoForge mod for MC 1.21.1. Sofa project, not commercial. License: MIT (mirroring upstream).

## Stack (locked)

- **MC** 1.21.1 (deliberately 1.21.1, not 1.21.x — that's what FTB packs target right now)
- **NeoForge** 21.1.220 (chosen for Mekanism + DW20 1.21 modpack compat)
- **Kotlin for Forge (KFF)** 5.11.0 (5.x line, NOT 6.x — 6.x is a bigger MC bump)
- **Java 21** (Temurin)
- **Cobalt** 0.9.5 (Lua 5.2) + **Rhino** 1.7.15 (ES6 interpreter mode) — both jarJar'd + on `additionalRuntimeClasspath` for dev
- **Tests:** JUnit5 + Kotest assertions; SLF4J-simple for test logging; **196 tests, must stay green**

## What's actually shipped (R1 + R2 partial — 2026-04-20)

Look in `docs/14-scripting-api.md` for the user-facing surface; below is the engineering view.

### Blocks + items
- **Computer** — places, has wifi channel + power state + per-script async runner. GUI: terminal + channel + power/reset buttons.
- **Monitor** — multi-block (largest-rectangle merge); ANSI escapes, per-cell ARGB colors, three-layer rendering (HD pixel buffer → per-cell bg fills → glyph quads), touch input. Both `pollTouches()` (legacy) AND `monitor_touch` events work — touch payload now carries `(col, row, px, py, player)` for pixel-precise hit-testing. **HD pixel-buffer API** (R2-shipped): `clearPixels`, `setPixel`, `drawRect`, `drawRectOutline`, `drawLine`, `drawGradientV`, `fillCircle`, `getPixelSize` — renders below the text grid so cells overlay pixels. Pixel buffer is NBT-persisted via Deflate compression. **Implicit peripheral lease:** the first script to mutate a monitor (or any peripheral routed through it) holds it for the script's lifetime; a second script trying to mutate gets `peripheral X is held by pid=N (chunkName) -- kill it or wait` thrown as `LuaError` / `js error`. Auto-released on script kill/crash/exit. Read-only methods (`getSize`, `pollTouches`, `getPixelSize`) bypass the lease.
- **Adapter** — ID-style multipart. Small dark-grey core (6×6 voxels) at center, thin cable arms (4×4) extending toward each connected face. **Six part kinds** install on faces:
  - `inventory` (IItemHandler), `fluid` (IFluidHandler), `energy` (IEnergyStorage) — capability-backed
  - `redstone` — vanilla redstone read/write (not a NeoForge cap)
  - `block` — reads adjacent block state (id/NBT/light/redstone/hardness); also `harvest()` to break + route loot to an inventory peripheral
  - `bridge` — universal protocol shim. Routes calls through `BridgeDispatcher` → `ProtocolAdapter` (today: ZeroCore for BR/ER/turbines/energizers; CC/ID/NeoForge-cap adapters pending). Surfaces `methods()` / `call(name, args)` / `describe()` to scripts.
- **Part Settings GUI** — empty-hand right-click on installed part. Fields: `label`, `channel` (with ▼ dropdown of nearby computers' channels), `access side` (cap-backed parts only — overrides install face's opposite for sided IItemHandlers), kind-specific options (today: redstone `Inverted`).
- **Monitor channel GUI** — sneak + right-click any monitor in a group → channel field (▼ dropdown of nearby computers' channels). Channel lives on the master only; propagated to slaves via `setChannelIdForGroup` so master flips carry it forward.
- **Part install/remove** — right-click face with part item to install; sneak + empty-hand right-click on bump to remove (drops as ItemEntity arc'd at the player).

### Scripting
- **Dual VM** (Lua via Cobalt, JS via Rhino). Same surface from both: `print`, `sleep`, `fs.*`, `peripheral.*`, `colors.*`, `network.*`, `os.pullEvent/queueEvent/startTimer`. Lua-only: `json.encode/decode` (Gson-backed; JS uses native `JSON`).
- **Per-script worker thread.** `os.pullEvent` blocks the worker (server keeps ticking).
- **Multi-script per Computer (Phase 2 shipped):** one foreground + N background. Shell cmds: `bg`, `jobs`, `fg <pid>`, `kill [pid]`, `tail [pid] [-n N]`. Foreground output → terminal; background output → per-script tail buffer (200 lines, accessible via `tail`). Bg crashes auto-surface as a `[bg pid=N name] crashed: <msg>` banner in the foreground terminal — no need to know about `tail` for unhandled errors. `pcall`/`try` still suppress as expected.
- **Lua call-syntax fix (LOAD-BEARING):** peripheral methods support BOTH `m.foo(x)` and `m:foo(x)` via the `method(self) {...}` helper in `CobaltLuaHost`. Earlier wrappers misread the implicit receiver as the user's first arg under `:`-syntax, producing `tostring(table)` payloads. Regression test in `InventoryBindingTest.lua peripheral methods accept both colon-call and dot-call syntax`.
- **Event sources:** `monitor_touch`, `network_message`, `timer`. Routed via `EventDispatch.fireToChannel` walking `ChannelRegistry`.
- **Anti-dupe rule (LOAD-BEARING):** scripts only MOVE or DESTROY items/fluids/energy. No `setItem`/`drop`/`copy`/`fill` APIs. Every transferable kind has `push`/`pull`/`destroy`.

### Diagnostic + tooling
- **OC2-Debug** companion mod (`~/repos/oc2-debug`) — embedded MCP server on port 9876. CLI binary at `~/.local/bin/oc2dbg`. Tools include `list_computers`, `read_computer_console`, `write_computer_file`, `run_computer_command`, `get_inventory`, `read_monitor` (dumps the master BE's text buffer — what the BE thinks should display vs what the screen shows, useful when shader interactions hide the rendering), plus generic MC `get_block` / `set_block` / `screenshot` / `find_biome` / `teleport` / `scan_chunk` / `scan_area`.
- **`ServerLoadedComputers`** — JVM-static registry of every loaded ComputerBE. Stable reflection target for oc2-debug. Per-BE 200-line ring buffer of recent script output.

## Architectural rules (locked, from `docs/11-engineering-rules.md`)

- **A — If it's testable, test it.** Add the test in the same commit as the logic.
- **B — Abstract for testability.** `interface` seams to break Mojang-static coupling. Examples: `Peripheral`, `ScriptEnv`, `PartHost`, `NetworkAccess`, `ChannelRegistrant`.
- **C — Over-engineering OOP is OK.** OC2 is a long-lived platform. SPI from day one. Defer YAGNI.
- **D — Core code never imports `net.minecraft.*`.** Pure packages: `platform/`, `event/` (mostly), `diag/`. MC-coupled: `block/`, `client/`, `item/`, `network/`. Pre-positions for multi-version.

## Hard NO's (architectural exclusions, not deferrals)

- **Computer hosts parts.** Rejected on aesthetic grounds — Computer stays a clean terminal silhouette. Adapters are the only PartHost.
- **Holograms / custom 3D rendering.** Capped rendering complexity at "what every other mod does."
- **Storage virtualization.** OC2 is logistics orchestration, NOT storage. Items always live in real inventories.
- **Item duplication paths in the script API.** No `setItem`/`drop`/`copy`/`fill`. Only move (atomic transfer between handlers) and destroy (void).

## Where to look in code

- `block/` — Computer, Monitor, Adapter BEs and their Block classes; renderers under `client/`
- `block/parts/` — concrete Part impls (InventoryPart, RedstonePart, etc.); `PartItems` reverse-lookup; `BlockPartOps` for the harvest path
- `client/screen/` — ComputerScreen, PartConfigScreen, MonitorConfigScreen, MonitorRenderer (3-pass: HD pixel quad → cell bg fills → glyph quads). Glyph path branches per frame on `ShaderModCompat.isShaderPackActive()`: MSDF custom shader (no shader pack) vs bitmap atlas + vanilla `position_tex_color` (shader pack active). `MonitorPixelTextureCache` holds the per-master `DynamicTexture` for the HD pixel layer. `MonitorFrameCounter` dedups multi-stage Iris/Oculus passes; `ShaderModCompat` reflectively checks `IrisApi.isRenderingShadowPass` / `IrisApi.isShaderPackInUse` per frame. The bg pass is force-flushed via `BufferSource.endBatch(MONITOR_BG_FILL)` before text — same z + same bufferSource without that flush would let translucent text draw under opaque cell bgs.
- `client/AdapterRenderer.kt` — BER drawing the kind-colored bump trim (frame on outward face + ¼-voxel band wrapping the sides at mid-depth)
- `block/bridge/` — `ProtocolAdapter` SPI + `BridgeDispatcher` (soft-dep loader) + `adapters/` (one per integration, e.g. `ZeroCoreAdapter`)
- `network/` — payloads (RunCommand, SetChannel, SetMonitorChannel, UpdatePartConfig, ComputerControl, TerminalOutput, LabelPart→UpdatePartConfig)
- `platform/` — Rule-D-pure interfaces and shared infrastructure
- `platform/parts/` — Part / PartType / PartHost / CapabilityBackedPart / PartOptionsCodec
- `platform/script/` — ScriptHost / ScriptEnv / ScriptRunHandle / ScriptEvent / ScriptEventQueue / CobaltLuaHost / RhinoJSHost / LuaJson / **`PeripheralLease` (per-peripheral implicit lease) / `ScriptCallerContext` (ThreadLocal pid/chunkName for the running worker — read on the WORKER side of `onServerThread { }`, never inside)**
- `platform/network/` — NetworkInboxes, NetworkAccess (for `network.*` Lua/JS API)
- `platform/peripheral/` — interfaces scripts see (MonitorPeripheral, InventoryPeripheral, etc.)
- `event/EventDispatch.kt` — pulse events into running scripts on the right channel
- `diag/ServerLoadedComputers.kt` — the public reflection target oc2-debug uses

## Common workflows

**Run all tests:** `./gradlew test --console=plain`
**Compile only:** `./gradlew compileKotlin compileTestKotlin --console=plain`
**Launch dev client:** `./gradlew runClient --console=plain` (use `run_in_background: true`; takes 30-60s to boot). **Always `kill $(lsof -nP -i :9876 -t 2>/dev/null) 2>/dev/null` first** — OC2-Debug binds 9876 and a zombie MC instance will block startup.
**Probe in-game state:** `oc2dbg list` (shows tools), `oc2dbg call <tool> '<json>'`, `oc2dbg state`, `oc2dbg get_block X Y Z`. Don't ask Scott to do what oc2dbg can answer — read project memory `reference_oc2_debug.md` for the full pattern.

## What's NOT yet built (active gaps)

- **Crafting recipes.** Mod is creative-only until shipped. Repeatedly deferred.
- **JS event API (R2 Phase 3).** Lua has `os.pullEvent`; JS doesn't. Needs Rhino continuations.
- **More bridge adapters.** Have ZeroCore (BR/ER/turbines/energizers); CC `IPeripheral` is highest-leverage next (covers ~100+ mods). NeoForge cap fallback + ID adapter also queued.
- **CC:T-style filter requeue on `pullEvent`.** Today non-matching events are dropped.
- **Tier 2 Control Plane** (Linux VM via Sedna). Whole second tier; deferred until R1 has player feedback.
- **More driver SPI primitives** — `IPropertyReader/Writer`, `IRecipeQueueWriter`. Designed but not built.
- **R2 driver set** — AE2, Refined Storage, Botania, Create. Vanilla + Mekanism (via capabilities) work; rest deferred.

See `docs/12-followups.md` for the full roadmap.

## When you're starting a fresh session in this repo

1. Read this file (you already are).
2. Skim `docs/14-scripting-api.md` for the user-facing surface — that's what scripts can do.
3. Skim `docs/12-followups.md` for what's planned next.
4. `./gradlew test --console=plain` should be 196 green. If not, that's the first fire.
5. The user (Scott / BrewingCoder) is the sole developer; act don't ask, but check before destructive operations (per his global memory).
