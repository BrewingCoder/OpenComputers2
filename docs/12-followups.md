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

## MSDF terminal text rendering — R2

**Status:** deferred to R2 (post-platform-basics)
**Priority:** medium-high — visual quality of THE central UI element

### Why we need it
v0 ships with **Spleen 5×8** baked into a bitmap atlas, blitted via our custom `TerminalRenderer`. It's crisp at native size but is a true pixel font — looks like a 1990s terminal emulator. Acceptable for v0 / R1.

For "modern terminal emulator" quality (think Terminal.app at 15pt SF Mono — anti-aliased, subpixel-clean, scalable to any size), we need MSDF (Multi-channel Signed Distance Field) text rendering. This is the technique used by Rocket League, Forza, and other AAA games for in-game UI text.

### What MSDF gives us
- **Vector-quality text** baked into a single small bitmap — render at any pixel size with no quality loss
- **Sharp at small sizes** (matches what Claude Code shows in Terminal.app)
- **Smooth at large sizes** (no aliasing on giant text)
- **GPU-friendly** — single texture, custom shader, one quad per glyph
- Works inside MC's GL context — no need to escape MC's framebuffer

### The build
1. **Generate MSDF atlas** from JetBrains Mono TTF using [msdfgen](https://github.com/Chlumsky/msdfgen) (CLI tool) or [msdf-atlas-gen](https://github.com/Chlumsky/msdf-atlas-gen) (preferred — handles whole atlas in one shot)
2. **Custom shader pair** (vertex + fragment) — fragment computes signed distance from MSDF, applies smoothstep edge
3. **Register shader** with NeoForge's `RenderType` system — `RenderType.create(...)` with our custom shader
4. **New `MsdfTerminalRenderer` class** — drop-in replacement for `TerminalRenderer`. Same API surface; players don't notice.

### Why deferred to R2
- v0 needs to ship with a working terminal first; Spleen does that
- MSDF is ~1-2 weeks of focused work (shader debug, atlas tooling, integration)
- The R1 platform features (VM scheduler, driver SPI, channel registry maturity) are higher leverage than text crispness
- Spleen is "good enough" for the entire R1 use case
- When R2 lands, swap in MsdfTerminalRenderer — zero impact on player scripts that use the screen API

### When to act
Trigger: after R1 platform basics ship and we start showing the mod to other players. The text-quality complaint will arrive within a few weeks of public exposure. Build MSDF in the lull between R1 ship and R2 feature work.

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

## Cooperative scheduling (R2 — Option B from the "How CC:T does it" conversation, 2026-04-18)

**Status:** Option A (worker thread + blocking sleep) lands in R1. Option B is the proper CC:T-style coroutine + event-queue model — deferred to R2.

### What R2 needs to add on top of R1

**1. Coroutine-driven script execution.** Each script runs as a Lua coroutine (Cobalt) / Rhino continuation. Custom yielding primitives:
- `os.pullEvent(filter?)` — yields until next event matching filter
- `os.startTimer(seconds)` — schedules a `timer` event
- `os.queueEvent(name, ...)` — synthetic event from script

**2. Per-computer event queue.** Thread-safe queue of `Event(name, args)`. Populated by:
- MC tick scheduler (timer events firing)
- Monitor touches (becomes `monitor_touch` events instead of polled)
- Redstone changes (when adapters land)
- Any peripheral-emitted event (charger insert/remove, network packet rx, etc.)

**3. Coroutine runtime loop.** Worker thread:
- Resumes coroutine with current event (or initial nil)
- Coroutine runs to next yield (e.g. `os.pullEvent`)
- Worker parks until next event arrives in queue, or timer fires

**4. CC:Tweaked compatibility.** Should support porting reactor monitor / quartermaster-style scripts with minor syntax tweaks. Match CC:T's event names where reasonable.

### Why deferred

R1's blocking-sleep model unblocks 90% of useful scripts in ~1 day. The full coroutine path is 2-3 days of careful threading + testing. Better to ship A early and iterate based on real-script feedback before committing to the coroutine model.

### When to act

When CC:T-script ports are the next-most-requested feature, OR when a player writes a real-time dashboard that runs into the 50ms-tick latency of A's marshaling model.
