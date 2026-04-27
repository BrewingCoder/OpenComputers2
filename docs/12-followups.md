# 12 — Followups (deferred work, captured before forgotten)

Open work items not yet started. Each entry includes the why so future-us doesn't have to re-derive context.

## NeoForge GameTest integration tests

**Status:** stubbed and removed pending research
**Priority:** medium (Rule A applies — once we have BlockEntity-level features that are hard to unit-test, gametests become the right answer)

### Background
NeoForge 1.21+ rewrote the GameTest framework from the simple `@GameTest`-annotation model into a data-driven registry system:

- `Registries.TEST_ENVIRONMENT` — `TestEnvironmentDefinition` entries (setup contexts)
- `Registries.TEST_FUNCTION` — `Consumer<GameTestHelper>` entries (the actual test code)
- `Registries.TEST_INSTANCE` — `GameTestInstance` entries (typically `FunctionGameTestInstance` wrapping a function ref + `TestData`)

`RegisterGameTestsEvent` (mod bus) exposes:
- `registerEnvironment(name, definition) → Holder<...>`
- `registerTest(name, instance)` and `registerTest(name, factory, testData)`

But — as observed during the original 1.21.10 (NeoForge 26.1.1.1-beta) attempt — there is no public method on the event for registering a `Consumer<GameTestHelper>` into `Registries.TEST_FUNCTION`. The function registry is data-loaded from JSON files at `data/<modid>/test_function/<name>.json` referencing class methods, which is a different mental model than "annotate a function and it Just Works."

**Re-evaluate on 1.21.1:** since retargeting to MC 1.21.1 + NeoForge 21.1.51 (April 2026), the older `@GameTest` + `@GameTestHolder` annotation pattern IS available (confirmed in NeoForge 21.1.x sources). This may be the lower-friction path on our current target. Try this first when picking up the gametest work.

### What's needed to land this
1. **First-try: use `@GameTestHolder("oc2")` + `@GameTest` annotations** — should work on 1.21.1 with no data-driven ceremony. Tests register automatically via NeoForge's annotation scan.
2. **If that fails (or we ever upgrade to 1.21.10+):** find a canonical example — gametest in JustDireThings (`feature/26.1` branch) or another current Kotlin/NeoForge mod actually shipping tests.
2. **Decide the registration pattern**: data-driven JSON pointing to a registered code class, OR a NeoForge helper I haven't found yet, OR a deeper hook into the dynamic registry loader.
3. **Set up the structure file** at `data/oc2/structures/empty.snbt` (5x5x5 empty box for our v0 tests).
4. **Write three baseline tests:**
   - smoke test (always passes — proves pipeline)
   - placing a Computer registers it with `ChannelRegistry`
   - NBT round-trip: place computer with channel "alpha", save world, reload, channel still "alpha"
5. **Wire `./gradlew runGameTestServer` into the standard "before you commit" gate** alongside `test`.

### Until this lands
- Unit tests (Rule A) cover everything that doesn't touch MC's static state — that's most of the platform layer
- Manual playtest covers BE behavior in `runClient`
- Acceptable gap until a feature lands that NEEDS gametests to validate (e.g., right-click GUI, channel reassignment from in-world UI)

### Why not retroactively use `@GameTest` annotations
Some NeoForge mods may still use the old annotation pattern via a backward-compat shim, but I haven't confirmed. Worth checking when we revisit. If it works, it's the lower-friction path and we use it.

---

## MSDF terminal text rendering — SHIPPED

Atlas + MSDF shader + `MsdfTerminalRenderer` shipped in R2. Original goal:
vector-quality text at any size for the in-world Monitor and the GUI Terminal.

### Shader-pack compatibility — SHIPPED (2026-04-19, revised approach)
The naïve MSDF approach fails under Iris/Oculus + Complementary etc. — shader
packs wrap our custom MSDF shader through their pipeline and produce wrong
output (typically black).

**Initial attempt (abandoned):** bake MSDF into a private offscreen FBO and
render the result as a vanilla `position_tex_color` quad. This worked in
theory but the FBO bake never produced visible pixels when invoked from
inside `BlockEntityRenderer.render()` — depth/blend state leaked, and bake
ordering against the BE render pass was fragile. Reverted; `MonitorFboCache`/
`MonitorFboBaker` deleted.

**Final approach (CC:Tweaked-aligned):** ship two glyph paths and pick one
per frame.
1. **MSDF path** (`renderTextMsdf`) when no shader pack is loaded — high
   quality vector glyphs via our custom shader.
2. **Bitmap path** (`renderTextBitmap`) when a shader pack is loaded — 5×8
   Spleen atlas (`textures/font/terminal_atlas.png`, 80×128, 16×16 grid)
   sampled through vanilla `position_tex_color` so the shader pack sees
   ordinary in-world geometry.

Plus the three CC-pattern pieces, all in `MonitorRenderer` /
`ShaderModCompat` / `MonitorFrameCounter`:
- **Frame dedup** — `MonitorFrameCounter` ensures one render per visible
  frame even when Iris's gbuffer + deferred composite stages each call
  `BlockEntityRenderer.render()` for the same BE.
- **Shadow-pass detection** — `IrisApi.isRenderingShadowPass()` (reflective)
  early-returns so monitor text isn't baked into the shadow map.
- **Fog push** — `RenderSystem.setShaderFogStart(1e4f)` for the duration of
  our draw so deferred composites don't darken the text to invisibility.

`ShaderModCompat.isShaderPackActive()` is checked **per frame** via
`IrisApi.isShaderPackInUse()` — Iris's shader-toggle hotkey flips this
without a world reload, so caching at startup would lock us in the wrong
path until next launch.

### Render-order trap (fixed 2026-04-20)
The bg-fill and text passes both submit translucent quads at z=0 to the same
`MultiBufferSource`. Without explicit ordering the dispatcher chose to flush
text **before** bg fills, so opaque cell bgs occluded their own glyphs. Fix:
force-flush the bg buffer with
`(bufferSource as BufferSource).endBatch(MONITOR_BG_FILL)` before submitting
text. Caught only after building a "corporate KPI" reactor dashboard whose
opaque card backgrounds suddenly hid every label. See `MonitorRenderer.render()`.

### Followups
- **Per-cell-density tuning**: `PX_PER_CELL = 12` is a magic number; could be
  derived from camera distance to avoid the full-res NBT pixel buffer when
  the monitor is far from the camera.

---

## HD pixel-buffer monitor mode — SHIPPED (2026-04-20)

Adds a pixel layer below the cell-text grid so scripts can draw real
graphics: gauges, gradients, buttons, mini-charts. The cell text grid still
overlays on top, so existing text-mode scripts keep working.

**Surface:** `getPixelSize`, `clearPixels`, `setPixel`, `drawRect`,
`drawRectOutline`, `drawLine` (Bresenham), `drawGradientV`, `fillCircle`.
Same surface from Lua and JS. Density: 12 px per cell, so an 80×27 cell
group is 960×324 px.

**Pipeline:**
- Pixel buffer is `IntArray` of ARGB on the master `MonitorBlockEntity`.
  NBT-persisted via `Deflater` compression (drops ~95% on typical content
  thanks to long runs of the same color).
- Renderer Pass 0 uploads the buffer to a per-master `DynamicTexture`
  (`MonitorPixelTextureCache`, content-hashed so we skip uploads when
  unchanged) and draws it as a single textured quad covering the surface
  via `position_tex_color` — shader-pack-safe by construction.
- Cell text + bg fills layer on top in passes 1–2 (see render-order trap
  above).

**Touch events** now carry pixel coords too:
`monitor_touch col row px py player`. Scripts can do pixel-precise
hit-testing on rendered buttons. `pollTouches()` returns the same fields.

### Followups
- **Touch via simulate_right_click** — oc2dbg's `simulate_right_click` doesn't
  reach `PlayerInteractEvent.RightClickBlock`; observed during reactor.lua
  dashboard testing. Real player input works; programmatic does not.
  Investigate alternative path (direct `PlayerInteractEvent.post`?).

---

## Implicit peripheral lease — SHIPPED (2026-04-20)

Prevents two scripts from stomping on the same peripheral concurrently. The
first script to mutate a peripheral implicitly leases it; second script gets
`peripheral X is held by pid=N (chunkName) -- kill it or wait` thrown as a
script error. Auto-released on script kill / crash / normal exit.

**Pieces:**
- `ScriptCallerContext` — ThreadLocal holding `(pid, chunkName)`. Set by
  `ScriptRunHandle` when a worker thread starts; cleared on exit.
- `PeripheralLease` — `ConcurrentHashMap<Any, Holder>` keyed by peripheral
  identity. `acquireOrThrow(peripheral)` is no-op when no script context is
  active (tests, server-side direct calls), grants/keeps the lease for the
  caller, throws `PeripheralLockException` otherwise.
- Kotlin → Lua: `CobaltLuaHost.method()` catches `PeripheralLockException`
  and rethrows as `LuaError`. JS host has the matching catch.

**Trap caught the hard way:** the lease check MUST happen on the script
worker thread, not inside `onServerThread { }`. The marshal hops to the
server thread where `ScriptCallerContext` is null, so the check silently
no-ops. Today every mutating method on `MonitorBlockEntity` does
`lease(); onServerThread { ... }` — lease BEFORE marshal.

Read-only methods (`getSize`, `pollTouches`, `getPixelSize`, etc.) skip
the lease check intentionally — many readers, one writer.

### Followups
- **Extend to other mutating peripherals.** `EnergyPart`, `FluidPart`,
  `InventoryPart`, `RedstonePart`, `BlockPart.harvest`, `BridgePart.call`
  should all `lease()` before mutation. Currently scoped to `Monitor` only.
- **Cobalt LuaString is Latin-1.** Non-ASCII chars in Kotlin error messages
  become `?` after the Cobalt conversion (LuaString.valueOf uses ISO-8859-1).
  Em-dashes were the visible victim — replaced with `--` in
  `PeripheralLease`. Real fix needs a UTF-8-aware terminal renderer; defer.

---

## Other followups

(Add new items here as they accrue.)

### Server-side single registration verification → unit test
The Rule A audit on server-only registration logic in `ComputerBlockEntity.registryShouldTrack` isn't directly unit-testable because it depends on `BlockEntity.level.isClientSide`. Either (a) extract a `Side` interface and inject, OR (b) cover via gametest once that lands. Defer until we hit a related bug.

### Position math edge cases
`Position.distanceSqTo` and `isWithin` are tested implicitly by future range-check code, but should have direct tests added when range-aware adapter logic lands.

### Terminal: clipboard paste support
The `ComputerScreen` accepts `charTyped` one keystroke at a time but doesn't handle Ctrl-V/Cmd-V pastes. Hard blocker for typing real Lua scripts in-game (workaround during R1: write files via host FS, then `run`). Implement by overriding `keyPressed` to detect `KEY_V` + `Screen.hasControlDown()` (Win/Linux) or `Screen.hasShiftDown()`+meta on macOS, calling `Minecraft.getInstance().keyboardHandler.clipboard`, splitting on newlines into the buffer. Likely also want Ctrl-C / Cmd-C to copy current input.

### Terminal: command history (up/down arrow)
Bash-style. Persist last N commands per-session (in-memory at first; later: ROM `.history` file). Arrow keys cycle. Prerequisite for any non-trivial in-terminal scripting.

### Terminal: scrollback / PageUp-PageDown
Currently caps at 256 lines and oldest drops off. Real shells need a scroll buffer with PageUp/PageDown navigation. Scope creep until the terminal becomes a daily-driver UI; defer to R1 week 4 polish.

---

## Bridge dispatcher — universal peripheral adapter

**Status:** SPI + dispatcher + ZeroCore adapter SHIPPED (2026-04-19).
Other adapters (CC, ID, NeoForge caps) deferred.

### Shipped
- `BridgePart` — universal `bridge` Part kind, install on adapter face touching anything scriptable
- `ProtocolAdapter` SPI in `block/bridge/`
- `BridgeDispatcher` — soft-dep aware loader via ModList + Class.forName isolation
- `ZeroCoreAdapter` — pure-reflection wrapper for Big/Extreme Reactors, Turbines, Energizers (no compile-time dep on ZeroCore)
- `BridgePeripheral` interface with `methods()`, `call(name, args)`, `describe()` self-introspection
- Lua + JS host wrappers
- Scott confirmed: "I don't want 30 different mods to support this" → all adapters bundled in OC2 core (NOT companion jars), gated by `ModList.isLoaded` per-adapter

### Pending adapters (sorted by leverage)
1. **CC `IPeripheral`** — covers ~100+ mods in any pack with CC:Tweaked installed (Mek, Botania, Create, Advanced Peripherals, etc.). `IComputerAccess` stub is solved territory; lift the pattern from CC mod-compat addons. **Highest leverage; do next.**
2. **NeoForge cap fallback** — `IItemHandler`/`IFluidHandler`/`IEnergyStorage`. Overlaps existing typed parts but lets the bridge gracefully expose generic blocks.
3. **Integrated Dynamics `INetwork`** — for ID cables. Read aspects, drive writers, variables.
4. **OC1 `ManagedPeripheral`** — historical, low priority.

### Pending polish
- **`call()` value marshalling**: `Object[]` → `List<*>` works for primitives + Map, but raw arrays come back as `[Ljava.lang.Object;@hash` strings. Add `Array<*>` case in `toLuaValue` / `javaToJs`.
- **Optional unwrapping**: ZeroCore returns `Optional<Fluid>` etc. Currently passes through as `"Optional.empty"` strings. Unwrap in `ZeroCoreAdapter.call`.

---

## Cooperative scheduling (R2)

**Status:** Phase 1 + 2 SHIPPED (2026-04-18). Phase 3 (JS event support) deferred.

### Phase 1 — shipped
- `os.pullEvent([filter])` blocks the worker thread on a per-script event queue
- `os.queueEvent(name, ...)` enqueues an event into the calling script's queue
- `os.startTimer(secs)` returns a timer id; fires `"timer", id` event when due
- Event sources: `monitor_touch`, `network_message`, `timer`
- Routing via `EventDispatch.fireToChannel` walks `ChannelRegistry` for computers on the channel and offers into each running script's queue
- Filter drops non-matching events (CC:T-style requeue is Phase 3)

### Phase 2 — shipped
- Multi-script per Computer via separate worker threads (NOT Lua coroutines —
  the original plan was coroutines, but a thread-per-script model ships in
  much less code and is enough for the player-visible UX)
- `BeScriptRunner` holds one foreground + a list of background handles
- New shell commands: `bg <file>`, `jobs`, `fg <pid>`, `kill <pid>`
- Foreground script output goes to the terminal; background output drained
  and dropped (per-bg buffer is the next followup below)

### Phase 3 — partially shipped

**Shipped (2026-04-19):**
- **Per-background log viewer** — `tail [pid] [-n N]` shell command reads the
  per-script tail buffer (200 lines, captures every `print`). Bg scripts that
  crash also surface a `[bg pid=N name] crashed: <msg>` banner in the
  foreground terminal — analogous to an unhandled-exception print to stderr.
- **Kill all scripts on host BE removal** — `BeScriptRunner.killAll()` runs
  in `ComputerBlockEntity.setRemoved` so bg scripts can't keep poking external
  mods after the world is being torn down (was producing NPE log spam from
  ZeroCore et al).

**Still deferred:**
- **JS `os.pullEvent`** — Rhino has continuations support but it's a mode
  switch with implications for the rest of the JS host. Worth doing once
  someone actually writes a JS automation script that needs events.
- **CC:T-style filter requeue** — currently `os.pullEvent("x")` drops
  intermediate non-matching events. CC:T queues them for the next
  unfiltered pull. Worth fixing if a script breaks because of the difference.
- **Lua coroutine model (true cooperative scheduling)** — the original
  R2 design. The current thread-per-script model wastes a thread per
  background script. Coroutines would scale to dozens of bg scripts at
  the cost of a meaningful refactor. Defer until someone actually runs
  20+ scripts on one computer.

---

## Monitor multi-block — channel + UI

**Shipped (2026-04-19):**
- Master-only `ChannelRegistry` registration (was 1-per-block, now 1-per-group)
- `setChannelIdForGroup` propagates channel onto every block in the group so a
  master flip carries it forward
- `MonitorConfigScreen` — sneak + right-click any monitor to set channel,
  matches `PartConfigScreen` pattern with nearby-channel ▼ dropdown
- `SetMonitorChannelPayload` — server validates 16-block range, resolves master
  from clicked block

### Still pending
- **`Lua peripheral` syntax-fix tests**: the `m.foo(x)` vs `m:foo(x)` bug
  shipped today (CobaltLuaHost `method(self) {...}` helper) needs explicit
  unit tests for both call styles to lock the contract.
- **Bake out of render() pass**: see MSDF section above.

---

## Tier-2 Control Plane — incremental delivery

**Shipped:**
- 2026-04-26: docs/03 design (Linux VM block; k8s = userspace)
- 2026-04-26: Sedna scaffolding — `R5Board` + 64 MB RAM ticking on the BE
- 2026-04-27: Disk image plumbing — sparse 256 MB virtio-blk per BE,
  UART16550A standard-output + host-side capture ring,
  `platform/vm/{ControlPlaneDisk,ConsoleCapture,ControlPlaneVm}`. 14 new tests.
- 2026-04-27: Singleton-per-player ownership registry —
  `platform/control/ControlPlaneRegistry` + `OC2ServerContext` integration
  rejects a second placement; Cloud Card item still pending.
- 2026-04-27: Power-state toggle — sneak right-click flips ON/OFF and
  closes the live VM on power-off; `tick()` is a no-op while powered off.
- 2026-04-27: Peripheral binding — `peripheral.find("controlplane")` from a
  Computer on the same channel; Lua + JS surfaces (`cycles`, `isPowered`,
  `togglePower`, `consoleTail`, `consoleClear`, `diskCapacity`, `describe`).
- 2026-04-27: Boot path — `ControlPlaneBoot.loadBytes` writes a firmware
  image into RAM at `defaultProgramStart`, plus a 16-byte RV64 stub that
  proves end-to-end execution by writing a single byte to UART16550A and
  jumping to itself.
- 2026-04-27: VM snapshot/restore via Ceres — `ControlPlaneVm.snapshot()`
  round-trips `R5Board` state through `BinarySerialization`. Needed
  `SednaSerializerRegistration` to register Sedna's hand-rolled serializers
  (R5CPUSerializer fills the immutable `cpu` field in place) AND set
  `li.cil.ceres.disableCodeGen=true` because Ceres' CompiledSerializer
  uses `sun.misc.Unsafe.defineAnonymousClass` (removed in Java 17+).
- 2026-04-27: Chunk-unload pause/resume — BE writes the snapshot to
  `<world>/oc2/vm-snapshots/<id>.snap` on `setRemoved` + `togglePower`-off,
  restores on next `bootVm`. Atomic `.tmp` + rename via `Files.move` so a
  mid-write crash leaves either the previous good snapshot or no file. A
  failed read or restore logs and falls through to a fresh boot — corrupt
  snapshots can never brick the block. **Supersedes the "chunk-unload pause/
  resume" section below.**

### Next — kernel + initramfs (no firmware = no boot)

The plumbing is there but the VM has nothing to execute. Without firmware
the CPU spins on illegal-instruction traps; the cycle counter still ticks,
which is the proof-of-life signal but not a usable computer. To make the
console produce real output and the disk hold a real filesystem we need:

- A **RV64GC Linux kernel** (vmlinux, ~5–10 MB) configured with virtio-blk,
  virtio-console, ext4, and minimal drivers. Buildroot or Yocto are the
  obvious cross-compile environments.
- A **tiny initramfs** (cpio.gz, ~2–5 MB) with busybox-static `/init`,
  fsck, mke2fs, and a small login shell. First-boot script formats the
  blank virtio-blk disk to ext4 if it isn't already.
- **Resource bundling**: ship both binaries under
  `src/main/resources/assets/oc2/vm/{vmlinux,initramfs.cpio.gz}`.
- **Loader path**: write the kernel into the `R5Board` flash device at
  `FLASH_ADDRESS` (or copy directly into RAM at `ramBase`) and set boot
  args via `R5Board.setBootArguments(...)` (e.g. `console=ttyS0
  root=/dev/vda rw`).
- **Licensing**: kernel is GPL-2; we'd ship config + source pointers
  alongside the binary to comply. Busybox same dance. Document in the
  repo so redistribution stays clean.

Substantial engineering (cross-compile toolchain, build scripts, CI to
keep binaries reproducible) — intentionally split from the plumbing
commits so each lands clean.

### Then — virtio chardev (`/dev/oc2net`) + Cloud Card

With a booting kernel that can talk to virtio devices, next is the OC2
channel bridge. Sedna doesn't ship a virtio-chardev (only console/block/
net/9p), so we'd subclass `AbstractVirtIODevice` ourselves. Then write
`oc2net.ko` that talks to it, and the host-side `BridgeDispatcher` that
routes packets between the VM and the OC2 channel network.

### Then — 9P host mount

`VirtIOFileSystemDevice` + `HostFileSystem` are already in Sedna. Wire
both in and decide where on the host filesystem the `/mnt/host` shared
dirs live (probably `<world>/oc2/host-share/`).

### Then — singleton-per-player + Cloud Card item

Once the bridge works, the `OC2ControlPlaneRegistry` becomes load-bearing
(rejects placement if the player already owns one). Cloud Card is a new
Tier-1 part item that opts a Computer into being routed by the VM.

### ~~Then — chunk-unload pause/resume~~ — SHIPPED 2026-04-27

See the Shipped list above. Snapshot lives at
`<world>/oc2/vm-snapshots/<id>.snap` (paired with the disk image),
written via `ControlPlaneSnapshotStore` on chunk unload + power-off.
