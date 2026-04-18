# 03 — Tier 2: Control Plane (k8s-inspired)

The R2 (release 2) global orchestration layer. Tier 1 gives you local automation; Tier 2 lets you treat all your bases as one fleet.

## What it is

A multi-block "Control Plane" — visually a server rack — that any one player can build **once per world**. Costs more to build, costs more power to operate, and unlocks global-range device coordination plus the full SaaS-like service mesh.

## Singleton enforcement

- One Control Plane per player per world
- Attempting to build a second one fails (returns error to the player)
- If destroyed, the player can build a new one (replacing the previous registration)
- Other players can't access another player's Control Plane (per-user channel scoping continues here)

## What it provides over Tier 1

### Channel match without range cap
- Tier 1: channel match AND wireless range required
- Tier 2: channel match only — global within the world
- The Tier-2 build IS the unlocking of unlimited range. That's the gameplay reward.

### Service mesh (the SaaS metaphor)

Edge computers (Tier-1 with a Cloud Card peripheral) **register** with the Control Plane as **services**. Multiple computers running the same service become **replicas**. The Control Plane routes RPC calls, aggregates telemetry, and maintains desired state per **manifest**.

```
┌─────────────────────────────────────────────────────────┐
│             Control Plane (one per player)              │
│   service registry │ RPC dispatch │ pub/sub │ KV │ SQL  │
│   heartbeats │ manifests │ cron │ logs │ metrics        │
└────┬───────┬───────┬────────────────────────────────────┘
     │       │       │
┌────▼─┐ ┌──▼──┐ ┌──▼────┐
│edge  │ │edge │ │edge   │
│ore-  │ │ore- │ │farm   │
│proc  │ │proc │ │ctrl   │
│  +CC │ │ +CC │ │  +CC  │   (CC = Cloud Card peripheral)
└──────┘ └─────┘ └───────┘
   ↑          ↑       ↑
   └──────────┴───────┴── adapters on local channel pull/push items
```

### Cloud Card
- A peripheral cartridge slotted into a Tier-1 Computer
- Opts that computer into the user's Control Plane fleet
- Computer keeps its local channel for nearby adapters AND has a global link to the Control Plane

## k8s → OC2 mapping

| k8s concept | OC2 |
|---|---|
| Control plane | **Control Plane** (singleton block, multiblock structure) |
| Worker node | Tier-1 Computer with a Cloud Card |
| kubelet | The Cloud Card itself |
| Service | Logical name like `"ore-processor"`, `"farm-A"`, `"smelter-bank"` |
| Replica | An individual computer running that service (parallel for throughput / redundancy) |
| Manifest | A JSON spec on Control Plane: `{ service: "ore-processor", desired: 3, alert_below: 2 }` |
| Endpoint routing | Control Plane routes `call("ore-processor", ...)` to a healthy replica |
| Heartbeat / liveness | Edge → Control Plane ping (every N ticks) |
| Deployment | Pushing new script versions to replicas (rolling updates without downtime) |
| ConfigMap | Shared config on Control Plane that edge scripts pull |
| CronJob | Scheduled task on Control Plane |

We use k8s terminology verbatim where it fits. Anyone playing this mod who finds the SaaS metaphor cool already speaks that language; non-devops players learn the term once and gain the muscle memory bonus when they ever touch real k8s.

## Platform API surface (script-facing)

```lua
-- node lifecycle
controlplane.register("ore-processor", "v1.2", { handles = {"smelt", "extract"} })
controlplane.heartbeat()       -- usually called by the OS automatically

-- service discovery (from any computer in the fleet)
local replicas = controlplane.discover("ore-processor")  -- list of healthy edges

-- RPC (auto-routed to a healthy replica)
local result = controlplane.call("ore-processor", "smelt", { ore = "iron", count = 200 })

-- pub/sub (broadcast across the fleet)
controlplane.publish("alert", { level = "warn", msg = "low fuel" })
controlplane.subscribe("alert", function(msg) ... end)

-- KV store (shared config / small state)
controlplane.store.put("quotas/iron_ingot", 200)
local v = controlplane.store.get("quotas/iron_ingot")

-- declarative manifest (desired state — Control Plane enforces and alerts)
controlplane.manifest.declare({
  service = "ore-processor",
  desired_replicas = 3,
  alert_when_below = 2
})

-- scheduled jobs
controlplane.cron.schedule("hourly-sweep", "0 * * * *", function() ... end)

-- observability
controlplane.log.emit("info", "smelting started")
controlplane.metric.record("ore-processor.throughput", 47)
```

That's roughly the full surface — about 12-15 distinct API verbs total.

## State on Control Plane

- **KV store** — small key/value (config, counters, flags)
- **SQL database** — per-script namespace, full SQL-via-SQLite-style API. See [`06-database.md`](06-database.md).
- **Aggregated metrics** — Control Plane keeps recent metric values for dashboards
- **Service registry** — current replicas, health status, last seen
- **Manifests** — desired state specs

All persists across world saves. All survives chunk unloads (Control Plane is always loaded by virtue of being claimed by a player).

## Failure modes

- **Control Plane destroyed mid-flight**: Edge computers continue running their local logic but lose Cloud Card access. Their local channel + adapters still work. They reconnect when the Control Plane is rebuilt.
- **Edge computer dies**: Control Plane marks the replica unhealthy after N missed heartbeats. RPC stops routing to it. If a manifest declared more desired replicas than healthy, an alert fires.
- **Network partition** (chunk unloaded for the edge): Edge marked unhealthy, traffic stops, recovers when chunk loads back.

## Build cost / power cost

Concrete numbers TBD when balancing. The principle: Tier-2 is meaningful infrastructure. Players invest in it because they've outgrown Tier-1.
