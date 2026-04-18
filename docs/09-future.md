# 09 — Future (R2+)

Features and concepts deferred past R1 (the "platform basics + vanilla drivers" release). Captured here so future-us doesn't redesign from scratch.

## R2 — Major mod drivers + Control Plane

Control Plane (Tier 2) and the first set of mod drivers form the R2 release.

- **Control Plane multiblock** — see [`03-tier2-control-plane.md`](03-tier2-control-plane.md)
- **AE2 driver** — wrap an AE2 network as one inventory handle
- **Refined Storage driver** — same shape as AE2
- **Botania driver** — mana spreaders / pools as energy-like properties
- **Create driver** — kinetic stress, fluid tanks, mechanical crafter recipes
- **Range Enhancer module** — slottable item that extends a host's effective wireless range

## R3 — Drones + community drivers

### Drones
Mobile peripheral entities that carry items between waypoints.

- Slot into the channel-registered-device pattern, but in-range state recomputes constantly because they move
- A "logistics" computer runs a delivery-routing service
- Multiple drones = replicas of a delivery fleet (k8s pattern)
- Standard call: `delivery_service.dispatch({ from, to, item, count })` → routed to a free drone

**Chunk-load-during-flight** — the hard problem. Three approaches studied (prior art: Railcraft, Create trains, Immersive Railroading):

| Approach | Description | Trade-off |
|---|---|---|
| **A — Drone-carried chunkloader ticket** | Drone keeps current chunk loaded while in flight | Simple, perf cost, admin-unfriendly |
| **B — Path pre-compute + virtual time advance** | Calculate full path at dispatch; advance via virtual position while chunks unloaded; restore on chunk reload | Best perf, tricky interruption handling |
| **C — Logistics-zone-only** | Drones operate within chunkloaded zones (anchored by Control Plane or dedicated anchors); routes outside the zone queue | Cleanest gameplay rule, requires infrastructure |

**Lean: C as default, with B as an optimization for hops that stay inside an already-loaded zone.** Control Plane is implicit anchor → building one rewards the Tier-2 upgrade with a drone-operational radius.

### Community drivers
PRs welcome for: Mekanism, Immersive Engineering, Thermal series, Pipez, Functional Storage, Industrial Foregoing, etc. Each driver is a small companion mod (~few hundred LOC).

## R4+ — Speculative, low-priority

These are "might be cool someday" — explicitly NOT committed.

- **WebAssembly VM** — third VM target for any language compiling to WASM
- **LLM/AI integration** — computers can call Claude/local models via an MCP-style API; or computers can host MCP servers
- **External webhook receiver** — Control Plane can receive HTTP webhooks from outside MC for cross-system integration
- **Cross-dimensional Control Plane** — Control Plane in Overworld can talk to edges in the Nether (probably needs special handling around chunk loading per dimension)
- **Multiplayer cluster federation** — multiple players' Control Planes can opt into peer relationships for shared services
- **Crypto / signed scripts** — verify script provenance before running
- **Voice/speech input** — script-side, low priority
- **Touch displays beyond click** — gestures, multi-touch (probably never; click is enough)

## Sample-program pack (post-R1)

Distinct from the platform mod. Each is a separate downloadable mod or script pack:

| Sample | What it demonstrates |
|---|---|
| **OC2-Hello** | Basic getting-started — computer turns on, says hello, reads a chest, prints to screen |
| **OC2-Quartermaster** | Isy's-IM-equivalent — quotas, autocrafting, sorting (showcases SQL DB, services, RPC, drivers) |
| **OC2-ReactorOps** | Big/Extreme Reactors monitor + control with multi-reactor coordination (showcases property reader/writer drivers, real-time displays) |
| **OC2-Widgets** | KPI cards, bar charts, gauges built on the drawing primitives (script-side library) |

Three diverse use cases demonstrating storage/orchestration/monitoring/control all on one platform. Each ships as a separate downloadable.

## Open architectural questions for the future

These need answers before R2 ships, not before R1:

- **Manifest declaration syntax** — JSON? Lua DSL? KubeYAML-ish? Pick when actually building
- **DB backend** — SQLite via sqlite-jdbc (lean) vs H2 (pure Java fallback). Lock when implementing
- **Cron syntax** — full crontab (5 fields) vs simplified ("every 5 min")? Probably both, with helpers
- **HTTP card permissions model** — which hosts allowed, rate limits, response size caps
- **Boot/firmware UX** — how players install their first script onto a fresh Computer
- **In-game script editor** — needed for first-launch UX, but how feature-rich?
- **Script package format** — single-file? Folder? Manifest + resources? Inspired by k8s manifest? Or npm/pip-style?

Don't pre-design these. Let R1 reveal what's actually needed.
