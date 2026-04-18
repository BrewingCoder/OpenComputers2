# 11 — Engineering Rules

Three rules that govern how OC2 is built. Established 2026-04-18.

## Rule A — If it's testable, test it.

If a piece of code can be exercised by an automated test (unit test, gametest, integration test) it MUST have one. No exceptions for "trivial" code, "obviously correct" code, or "I just wrote it." Every commit that adds testable logic adds the corresponding test.

**Test layers in order of preference:**
1. **Plain JUnit5/Kotest unit tests** — for any code that doesn't touch MC's static state. Fast (ms), deterministic, run on every build.
2. **NeoForge `@GameTest`** — for code that requires the MC server (BlockEntity round-trips, world save/load, world interactions). Slower (seconds), still automated, runnable via `./gradlew runGameTestServer`.
3. **Manual playtest** — only for things that depend on visual rendering, gameplay feel, or behavior that can't be expressed as an assertion.

If a piece of code can't be tested at any of those layers, it's a candidate for **Rule B**.

## Rule B — If we can abstract a layer to make it testable, do that.

When a piece of code is hard to test because it's coupled to something untestable (Mojang static state, a hard-to-mock framework class, a singleton without a seam), we refactor to introduce a small interface or boundary that breaks the coupling.

The pattern is:
1. Identify the coupling (e.g., `ChannelRegistry` takes `ComputerBlockEntity` directly → coupled to BlockEntity construction)
2. Introduce a minimal interface that captures only what we actually use (e.g., `ChannelRegistrant { val channelId: String; val location: BlockPos }`)
3. Implementation classes implement the interface; tests use a fake/mock implementation
4. Production code is unchanged in behavior, gains a seam

This is "Hexagonal architecture" / "Ports and Adapters" in micro. We do it everywhere it pays off, not as a doctrine but as a tool to make Rule A possible.

## Rule C — Over-engineering abstraction and OOP is OK; it pays dividends later.

This is the most important and most counter-cultural rule. Common modern programming wisdom is "YAGNI" (You Aren't Gonna Need It), "premature abstraction is the root of all evil," "ship the simple thing first." For OC2 we deliberately reject the strong form of that advice.

**Reasoning:**
- OC2 is a **platform**. Platform code lives for years and is depended on by other code (player scripts + future driver mods + community contributors). The cost of changing a platform shape after it ships is enormous (broken backward compat, broken player scripts, broken driver mods).
- Over-abstraction at design time costs maybe 2x the LOC. Re-architecting after-the-fact when the abstraction is missing costs 10-100x.
- We're building this on the sofa, not under a deadline. The "ship faster" pressure that justifies YAGNI doesn't apply.
- Many of the patterns from K8s, distributed systems, OS kernels — the things we're explicitly cribbing — exist because the simple version DIDN'T scale. We're getting that wisdom for free.

**What this looks like in practice:**
- Driver SPI is a set of interfaces from day one, even when only the vanilla driver exists
- ChannelRegistry takes a `Registrant` interface, not a concrete BE
- VM scheduler interfaces are defined before either VM is wired up
- Database backend is behind an interface so SQLite vs H2 is a runtime decision
- The Computer block is a `BaseEntityBlock` from day one even though the BE does almost nothing yet

**What this is NOT:**
- License to add Configurable Configurable Configurations (the inner-platform effect — building a worse version of an existing tool inside our codebase). Stay focused on what OC2 actually does.
- License to add code paths for hypothetical features we've ruled out (see [`08-never-list.md`](08-never-list.md)). Holograms are never coming, so we don't add a "renderer" abstraction layer to support them eventually.
- License to over-design the user-facing scripting API. The platform internals are abstracted; the script-facing surface is small and direct.

## Tension between rules

Rule A says "test everything." Rule C says "abstract liberally." Together they create a healthy pressure: every abstraction introduced has to enable a test (otherwise it's gratuitous), and every untested code path justifies an abstraction effort. They're complementary, not opposed.

If you find yourself adding an abstraction that doesn't help test anything AND doesn't enable a future feature we've planned for, that's the warning sign that you've crossed into Rule C's "what this is NOT" territory.

## Effect on commits

- Every PR/commit must pass `./gradlew test` (unit tests)
- PRs that add testable logic without adding tests get rejected
- Refactors-for-testability are first-class commits, NOT "drive-by during a feature commit"
- It is normal and welcome to have a 3-commit sequence: (1) extract interface (2) write test (3) actually use the new test to drive the new feature

## Enforcement

Eventually a CI hook (GitHub Actions or Azure Pipelines) will run `./gradlew test` on every push. Until then it's the honor system. Don't merge red.
