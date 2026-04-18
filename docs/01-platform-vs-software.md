# 01 — Platform vs Software

The single most important rule in OC2's design.

## The rule

> **OC2 ships the platform; players ship the software.**

If a feature could plausibly be implemented as a player-written Lua/JS script running on top of OC2's primitives, it does NOT belong in the mod. It belongs in a downloadable script library.

## What this means concretely

### In the mod (platform)
- VM hosts (Lua, JS), scheduler, syscall dispatch
- Wireless channel registry, discovery, RPC routing
- Pub/sub messaging
- KV store on Control Plane
- SQL database (per-script namespace)
- Compiled drivers for world interaction (vanilla R1, mod drivers R2+)
- Drawing primitives for screens
- Heartbeat / health / liveness
- Cron / scheduling
- Logging / metrics emission

### NOT in the mod (script-side)
- The ore processor
- The sorter
- The autocrafter
- The reactor controller
- KPI dashboard widgets
- Any specific "what to do with the platform"

## Why this rule earned its place

Every prior mod that tried to be a "complete automation solution" ended up with a few characteristic failure modes:

1. **Scope creep death** — the mod author keeps adding features ("now we have a built-in autocrafter, now a built-in reactor controller, now…") until the codebase is unmaintainable and the author burns out. OC was very large for this reason.
2. **Niche-product trap** — built-in features are opinionated; players who disagree have no recourse and write a competing mod. Better to give them the platform.
3. **Sample programs become the default** — built-in features get used because they exist; player ingenuity atrophies. Keeping it script-side keeps the community building.
4. **The "real" use cases are unknowable** — Isy's Inventory Manager (Space Engineers) and Sandalle's Big Reactors controller (CC) are masterpieces. Neither was built into their host platform. Both grew from real player frustration in real bases. We can't predict what OC2's "Isy's IM" will be — we just need to make sure someone *can* build it.

## Stress-testing the rule

When uncertain whether something is platform vs software, ask:

- **Can it be written as a player script using documented APIs?** → If yes, keep it script-side.
- **Does it require new privileged access to game state?** → If yes, it's a driver SPI addition or new platform primitive.
- **Is it general enough that ALL players would use it?** → Even then, prefer "ship as default sample script" over "build into the mod."

## The sample-program escape hatch

We DO ship reference scripts — they're how players learn. But they're shipped as **separate downloadable mods/scripts**, not built into the platform mod:

- **OC2-Quartermaster** — Isy's-IM-equivalent: quotas, autocrafting, sorting (showcases SQL DB, services, RPC, drivers)
- **OC2-ReactorOps** — Big/Extreme Reactors monitor + control (showcases property-reader/writer drivers, real-time displays, multi-replica coordination)
- **OC2-Hello** — basic getting-started ("computer turns on, says hello, reads a chest, prints to screen")
- **OC2-Widgets** — KPI cards, bar charts, gauges built on the drawing primitives

A player who wants none of these can install OC2 alone and get a clean platform. A player who wants instant gratification installs OC2 + Quartermaster and has working autocrafting in 5 minutes. Neither is forced on the other.

## Stress test: would Isy's IM be implementable on OC2?

[See `10-references.md` for the full trace.] Yes — and the trace revealed exactly two missing platform primitives that we then added:
- `IRecipeQueueWriter` driver interface (for queueing crafts on furnaces/assemblers)
- `IPropertyReader` / `IPropertyWriter` (the most-load-bearing addition — surfaces of named properties on TEs)

The forcing function works. The next major reference script (the reactor controller) added the property accessor SPI on top.

## When this rule will be hardest to obey

When a player says "wouldn't it be nice if the mod had a built-in X." The answer is almost always:
- "We'll ship it as a sample script in the next sample-pack release."
- Or: "Here's the API surface that lets you build it; please do."

The platform stays small. The community grows large.
