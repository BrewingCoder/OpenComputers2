# 00 — Overview

## What OC2 is in one paragraph

A platform mod for Minecraft 1.21+ (NeoForge) that gives players programmable in-world computers. They write their own scripts in **Lua or JavaScript**; the platform handles registration, discovery, RPC, storage, scheduling, and world interaction. Inspired by OpenComputers but built fresh in Kotlin with a Kubernetes-style architecture: tier-1 local computers register into per-user **wireless channels**, and a tier-2 **Control Plane** (one per player per world) orchestrates them globally.

## What OC2 is NOT

- Not a digital storage mod. We're logistics, not storage. AE2 and Refined Storage are storage; we orchestrate them.
- Not a hologram / custom-rendering mod. Block models + GUI screens only. (See [`08-never-list.md`](08-never-list.md).)
- Not a port of OpenComputers. We're a fresh Kotlin implementation that draws design inspiration and asset cribbing from OC's MIT/CC0 source.
- Not a single-tier "drop a computer, run a script" toy. The platform metaphor (control plane, services, replicas, manifests) is load-bearing and the differentiator.

## The core discipline

> **We ship the platform; players ship the software.**

OC2 provides:
- Registration, discovery, RPC routing, pub/sub, KV storage, SQL database, heartbeat/health, scheduling primitives
- Compiled drivers for world interaction (vanilla R1; major mods R2; community R3+)
- Drawing primitives for screens (rect, line, text, pixel — ~8 calls)

OC2 does NOT ship:
- The ore processor, farm controller, sorter, autocrafting orchestrator, reactor controller — all of those are user-authored scripts running on top
- Higher-level UI widgets (KPI cards, charts, gauges) — those ship as a separate downloadable script library

This is the Kubernetes model: k8s ships the API server + etcd + kubelet + Service abstraction. K8s does NOT ship the apps that run on top. That separation is what keeps the project sane.

See [`01-platform-vs-software.md`](01-platform-vs-software.md) for full reasoning.

## The two tiers

### Tier 1 — local automation
- **Computer** block (single block) with a wifi channel
- **Adapter** items attached to tile entities (chests, machines, etc.) with cartridge slots
- **Cartridges** are compiled capabilities (`InventoryReader`, `RedstoneIO`, `PropertyReader`, ...)
- Channel pairing + wireless range = local discovery
- No cabling. Ever.

See [`02-tier1-local.md`](02-tier1-local.md).

### Tier 2 — global control plane
- **Control Plane** — a Linux VM block (Sedna RISC-V emulator), 1×2 vertical, singleton per user per world
- Real Linux box: kernel + busybox/musl userland, persistent disk image per player
- Tier-1 computers opt in via a **Cloud Card** peripheral that exposes them to the VM as `/dev/oc2net` endpoints
- The k8s-style service mesh (services, replicas, manifests, heartbeats, RPC, pub/sub, KV, SQL) runs as **userspace software** (`controlplaned`) on the default disk image, not as a platform abstraction
- Channel match only — no range cap (that's the Tier-2 reward)

See [`03-tier2-control-plane.md`](03-tier2-control-plane.md).

## The dual-VM differentiator

Both tiers run scripts in either **Lua (LuaJ)** or **JavaScript (Rhino)**. Same syscall surface for both. No other in-game programmable computer mod offers JS — CC: Tweaked is Lua-only; KubeJS is modpack scripting (different category).

See [`04-vms-lua-js.md`](04-vms-lua-js.md).

## The architectural rules nobody breaks

1. **Registry-only discovery, NEVER scanning** — adapters publish themselves; computers read the registry. No world-chunk iteration, no AABB queries, no spatial sweeps. Performance budget is O(registry size), not O(loaded chunks).
2. **Logistics, not storage** — items always live in real inventories somewhere; we move them. We never invent abstract item pools.
3. **No custom rendering beyond standard models + screens** — no holograms, no 3D overlays, no shaders.
4. **Platform vs software** — if it can be written as a player script, it doesn't ship in the mod.

Each rule earned its place by ruling out a class of failure modes from prior mods (lag monsters, scope creep, niche-product death, never-shipping).

## Reference repos in `~/repos/_reference/`

- `OpenComputers` — the original OC, MIT + CC0, primary crib source
- `KotlinModdingSkeleton` — thedarkcolour's Kotlin/NeoForge template (this scaffold's base)
- `KotlinForForge` — the language adapter source
- `Future-MC` — production Kotlin mod for pattern reference
- `tgbridge` — small modern Kotlin NeoForge mod
- (defer Cobblemon — gigantic, only clone if needed)
