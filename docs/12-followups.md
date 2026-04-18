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

But — as of NeoForge 26.1.1.1-beta — there is no public method on the event for registering a `Consumer<GameTestHelper>` into `Registries.TEST_FUNCTION`. The function registry is data-loaded from JSON files at `data/<modid>/test_function/<name>.json` referencing class methods, which is a different mental model than "annotate a function and it Just Works."

### What's needed to land this
1. **Find the canonical NeoForge 26.1 example** — gametest in another active 1.21+ Kotlin (or Java) mod that's actually shipping tests. The reference clones we have don't include this.
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

## Other followups

(Add new items here as they accrue.)

### Server-side single registration verification → unit test
The Rule A audit on server-only registration logic in `ComputerBlockEntity.registryShouldTrack` isn't directly unit-testable because it depends on `BlockEntity.level.isClientSide`. Either (a) extract a `Side` interface and inject, OR (b) cover via gametest once that lands. Defer until we hit a related bug.

### Position math edge cases
`Position.distanceSqTo` and `isWithin` are tested implicitly by future range-check code, but should have direct tests added when range-aware adapter logic lands.
