# 03 — Tier 2: Control Plane (Linux VM block)

The R2 (release 2) global tier. Tier 1 gives you a sandboxed Lua/JS computer
per chunk-cluster; Tier 2 gives you **a real Linux machine, one per player,
that talks to your whole fleet over the OC2 channel network**.

> **The Control Plane block IS a Linux VM.** Everything the
> [original docs](../docs/00-overview.md#tier-2--global-control-plane)
> described as "k8s-style services / replicas / manifests / RPC routing" is
> **userspace software** that ships on the default disk image. The mod
> provides the VM and the bridges; the OS image provides the orchestration.

This split is deliberate and matches the platform-vs-software rule
([docs/01](01-platform-vs-software.md)): the k8s metaphor is the *software*
layer that runs on top of the *platform* layer. Just like Kubernetes runs on
top of Linux, our orchestrator runs on top of the Sedna-emulated Linux box.

## Shipping status (2026-04-27)

What's wired up today, vs what the rest of this doc describes as the target.

| Piece | Status | Notes |
|---|---|---|
| Control Plane block (1×2 vertical, places, ticks) | ✅ shipped | `block/ControlPlaneBlock`, `ControlPlaneBlockEntity` |
| Sedna `R5Board` + 64 MB RAM | ✅ shipped | `platform/vm/ControlPlaneVm` |
| Per-BE disk image at `<world>/oc2/vm-disks/<be-uuid>.img` (sparse 256 MB, virtio-blk) | ✅ shipped | `platform/vm/ControlPlaneDisk` |
| UART16550A standard-output device + host capture ring | ✅ shipped | `platform/vm/ConsoleCapture`. Becomes Linux's `console=ttyS0`. |
| Cycle counter NBT persistence | ✅ shipped | Persists every ~16M cycles |
| **Disk image keyed on per-BE UUID** | ⚠️ interim | Doc target is per-player UUID — single-player works either way; multiplayer + singleton-per-player enforcement lands with the registry. |
| `OC2ControlPlaneRegistry` + singleton-per-player | ⏳ deferred | Needed before MP. |
| Linux kernel + initramfs default image | ⏳ deferred | Needs cross-compiled vmlinux + busybox initramfs as mod resources. With no firmware loaded the CPU spins on illegal-instruction traps — that's the proof-of-life signal that the cycle counter ticks. |
| virtio-console for in-world terminal screen | ⏳ deferred | UART covers boot console; virtio-console + terminal screen ship together. |
| virtio chardev `/dev/oc2net` | ⏳ deferred | Needs both host bridge + guest kernel module. |
| 9P host mount `/mnt/host` | ⏳ deferred | Sedna ships `VirtIOFileSystemDevice`; we just haven't wired it. |
| `controlplaned` userspace + Cloud Card item | ⏳ deferred | Lives on the OS image, gated on the kernel + initramfs commit. |
| Sedna VM serialization (chunk-unload pause/resume) | ⏳ deferred | Sedna is serializable; we just haven't wired the snapshot/restore path. |

## The block

- **Single block, occupies 1×2 vertical** (lower BE owns state; upper slot
  is a marker BlockEntity referencing the lower).
- Server-rack visual styling. Blinkenlights tied to VM state.
- Right-click → terminal screen (virtio-console framebuffer + keyboard).
- Sneak + right-click empty hand → config GUI (power, owner, disk size).

## The VM

**Engine:** [Sedna](https://github.com/fnuecke/sedna) — pure-JVM RISC-V
emulator from the original OC2 (`li.cil.sedna`, MIT). RV64 with the standard
extension set (G + C). Same engine fnuecke shipped in upstream OC2 — a known
quantity for running a real Linux kernel inside Minecraft.

**R1 fixed configuration** (no item-craftable internals — that's R2 polish):
- **64 MB RAM**
- **One virtio-blk** backed by a per-world disk image
- **virtio-console** for the in-world terminal screen
- **virtio chardev** exposing the OC2 channel network (kernel sees it as
  `/dev/oc2net`)
- **9P mount** for host-side file shares (kernel sees it as `/mnt/host`)
- No virtio-net, no virtio-gpu, no virtio-input. The terminal *is* the
  console; world-network access is through the chardev, not virtio-net.

R2 may move to a slot-based "stick of RAM, HDD module, network card" model
similar to upstream OC2 if the gameplay payoff justifies the recipe surface.
For now, fixed.

## Disk images

Each player's Control Plane has one raw disk image at:

```
<world>/oc2/vm-disks/<player-uuid>.img
```

- File-backed, NOT serialized into BE NBT (NBT is wrong for hundreds of MB
  of disk data — would thrash the chunk save path).
- Initial size: **256 MB**, sparse on filesystems that support it.
- Format: ext4 inside a single MBR partition. The default OS image we ship
  bootstraps this on first boot if the file doesn't exist.
- Backed up with the world. Survives chunk unloads. Tied to player UUID,
  not block position — replacing a destroyed Control Plane reuses the same
  disk.

## Singleton enforcement

- One Control Plane per player per world.
- Tracked in level-attached `OC2ControlPlaneRegistry` (similar pattern to
  `ChannelRegistry`).
- Place attempt by player who already owns one → block doesn't place,
  player gets a chat error.
- If destroyed → registry entry cleared; player can build again. Disk
  image is preserved by default (config option to wipe on placement).
- Other players cannot interact with another player's Control Plane.
  Right-click is rejected with a permission error. Per-user channel
  scoping continues here.

## The OC2 channel bridge

The Linux VM's primary differentiator from a generic emulator is that it
can talk to the rest of the OC2 fleet — Tier-1 Computers, peripherals,
adapters — through the same wifi-channel network those use today.

**Wiring:**
- Host side: a virtio chardev wired into the BE. Each Tier-1 Computer
  (or peripheral) that opts in via the **Cloud Card** appears to the host
  as an addressable endpoint.
- VM side: a small kernel module (`oc2net.ko`) ships on the default disk
  image. It exposes `/dev/oc2net` as a packet-oriented chardev with a
  small ABI:
  - `read()` — pull next inbound packet `(channel, src_id, payload)`.
  - `write()` — send packet `(channel, dst_id, payload)` (`dst_id = 0`
    broadcasts).
  - `ioctl(OC2_LIST)` — list endpoints currently registered to the
    Control Plane.
  - `ioctl(OC2_OPEN, name)` / `ioctl(OC2_CALL, ...)` — the synchronous
    RPC fast path used by the orchestrator (under the hood: a
    request/reply pair routed through the same chardev).
- **Range:** the Cloud Card endpoint registration is global within the
  world — once a Tier-1 Computer is enrolled, the VM sees it from
  anywhere. No 32-block range cap. **That is the Tier-2 reward.**

The kernel module is a tiny C file (~200 LOC) compiled into the default
disk image we ship. Players writing their own disk images can either ship
the same module or open the chardev raw.

## The 9P host mount

Generic file-share mount for host-side files visible inside the VM:

```
/mnt/host/scripts/   (read-only) — scripts the player drops via oc2dbg or
                                   a worldfolder file manager
/mnt/host/spool/     (write)     — anything the VM writes here lands on the
                                   host filesystem
```

Used for: workflow ergonomics (write a Lua script in your IDE, drop it in
`world/oc2/host-share/scripts/`, the VM sees it instantly), debug log
spools, snapshot dumps. Not load-bearing for any feature — purely a
quality-of-life mount.

## Userspace (the k8s metaphor lives here)

The mod ships a **default disk image** preloaded with:

- Linux kernel + busybox/musl userland
- `oc2net.ko` kernel module + udev rule
- `controlplaned` — the orchestrator daemon. Provides:
  - **Service registry** (named services, replicas, health)
  - **RPC dispatch** (`controlplane.call("ore-processor", ...)`)
  - **Pub/sub** (`controlplane.publish("alert", ...)`)
  - **KV store** (small key/value, BoltDB-style)
  - **SQL** via SQLite per script namespace (see `06-database.md`)
  - **Manifests** (declarative desired state)
  - **Cron** (scheduled jobs)
  - **Heartbeats** (liveness, marks edge replicas unhealthy after N
    missed pings)
  - **Logs / metrics** aggregation
- A Lua + JS client library (`controlplane.so` / equivalent) that scripts
  on Tier-1 Computers link against to talk to `controlplaned`. Library
  marshals the `controlplane.*` API into `OC2_CALL` ioctls on the
  Cloud Card's chardev.

Players can replace any of this. Want a different orchestrator? Image a
new disk. Want no orchestrator at all and just a raw shell? Empty Linux
boots fine. The mod doesn't care what the OS image does — it provides the
VM and the channel bridge and gets out of the way.

This is the platform-vs-software rule applied recursively: the mod is
the platform, the OS image is the software layer, and player scripts run
on top of *that*.

## k8s → OC2 mapping (now: userspace software, not platform)

Same mapping as before, just to anchor the terminology — but understand
this is what `controlplaned` (a userspace daemon) implements, not what the
mod provides directly:

| k8s concept | OC2 (userspace) |
|---|---|
| Control plane | `controlplaned` running on the Linux VM |
| Worker node | Tier-1 Computer with a Cloud Card |
| kubelet | The Cloud Card chardev driver on the worker |
| Service | Logical name (`"ore-processor"`, `"farm-A"`, ...) |
| Replica | A Tier-1 Computer running that service |
| Manifest | A JSON spec on the VM: `{ service, desired, alert_below }` |
| Endpoint routing | `controlplaned` routes `call(...)` to a healthy replica |
| Heartbeat | Periodic Cloud Card → `controlplaned` ping |
| Deployment | Pushing new script versions to replicas (rolling) |
| ConfigMap | Shared config in the KV store |
| CronJob | Scheduled task in `controlplaned` |

Players who want this whole stack get it for free out-of-the-box. Players
who want to write their own orchestrator can ignore `controlplaned`
entirely.

## Script-facing API (from a Tier-1 Computer with a Cloud Card)

Scripts on Tier-1 Computers see the same surface they always have, plus
a `controlplane.*` namespace once a Cloud Card is installed:

```lua
-- node lifecycle
controlplane.register("ore-processor", "v1.2", { handles = {"smelt", "extract"} })
controlplane.heartbeat()       -- usually called by the OS automatically

-- service discovery
local replicas = controlplane.discover("ore-processor")

-- RPC (auto-routed to a healthy replica)
local result = controlplane.call("ore-processor", "smelt", { ore = "iron", count = 200 })

-- pub/sub
controlplane.publish("alert", { level = "warn", msg = "low fuel" })
controlplane.subscribe("alert", function(msg) ... end)

-- KV store
controlplane.store.put("quotas/iron_ingot", 200)
local v = controlplane.store.get("quotas/iron_ingot")

-- declarative manifest
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

This API is implemented entirely in the userspace client library
(`controlplane.lua` / `controlplane.js`) shipping in the OC2 ROM. The
library marshals each call into a Cloud Card chardev RPC to
`controlplaned` on the Linux VM. **The mod does not implement any of
these verbs in Kotlin.**

## Failure modes

- **VM crashes / kernel panics** — BE catches the Sedna fault, marks the
  VM as halted, persists the disk image, surfaces the panic to the
  terminal screen. Player can power-cycle.
- **Disk image corruption** — fsck on next boot (busybox ships it).
  Worst case: player rebuilds the disk; their userspace state is lost
  but their world is fine.
- **Chunk unload of the Control Plane block** — VM pauses. All edge
  computers' Cloud Cards mark themselves disconnected. When the chunk
  loads back, VM resumes from where it stopped (Sedna is fully
  serializable).
- **Edge computer dies / chunk unloads** — Cloud Card endpoint registry
  drops the entry; `controlplaned` marks the replica unhealthy after N
  missed heartbeats. RPC stops routing to it.
- **Network partition** — same as above; recovers when the chunk loads
  back.
- **Player who owns the Control Plane logs out** — VM keeps running. The
  OS doesn't know or care. Other players still can't access it.

## Performance budget

- VM ticks on the server thread at MC's 20 TPS rate. Each MC tick we
  step the Sedna VM for a fixed cycle budget (~1M cycles by default,
  config-tunable).
- Disk image flushes are async — VM writes back to a buffer, the BE
  flushes to disk on chunk save or every N seconds.
- Channel bridge is non-blocking from the VM's perspective; the host
  side queues inbound packets up to a per-VM cap (1024 packets / 1 MiB).
  Overflow drops oldest.
- Single Control Plane per player, so the upper bound is `(players × VM
  cost)`, not `(loaded chunks × VM cost)`. A 4-player world running 4
  Linux boxes is ~the same load as four idle Lua scripts in CC:T —
  Sedna is fast.

## What's NOT here (deliberate exclusions)

- **No multiblock rack assembly.** 1×2 vertical and that's it.
- **No item-craftable RAM / HDD / network cards in R1.** Fixed config.
- **No virtio-net.** The VM doesn't get a TCP/IP stack to the outside
  world. The channel bridge is the only external interface, and it's
  scoped to OC2 endpoints.
- **No "cloud sync" or external internet.** The Linux VM is fully
  contained in the world save. Nothing leaks out.
- **No replacing the Lua/JS Tier-1 hosts with the Linux VM.** Tier-1
  stays as it is — sandboxed Cobalt + Rhino. Tier-2 is additive.

## Build cost / power cost

TBD when balancing. Principle: Tier-2 is meaningful infrastructure.
Players invest in it because they've outgrown Tier-1 and want global
coordination. The fact that it's a *real* Linux box — apt, pip, gcc,
your own kernel modules — is the gameplay reward.
