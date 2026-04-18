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
