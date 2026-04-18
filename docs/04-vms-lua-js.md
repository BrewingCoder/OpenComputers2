# 04 — VMs: Lua and JavaScript

Both Tier-1 Computers and the Tier-2 Control Plane host scripts in either **Lua** or **JavaScript**, with a shared syscall surface. Player picks per-script.

## Why two VMs

- **Lua** is the modded-MC programmable-computer tradition. CC: Tweaked, OpenComputers, Computronics — all Lua. Players coming from those mods speak Lua. LuaJ runs cleanly on the JVM.
- **JavaScript** is huge in modern Minecraft modding via KubeJS (modpack scripting). The audience that's already JS-fluent in an MC context currently has zero in-game programmable computer mod that speaks their language.

**No other in-game programmable computer mod offers JS.** This is OC2's market differentiator.

## VM choices

| VM | Engine | Why |
|---|---|---|
| Lua | **LuaJ** | Pure JVM, no native libs, good performance for Lua workloads, ASL2 license |
| JavaScript | **Mozilla Rhino** | Same engine KubeJS uses (proven in MC modding), pure JVM, compatible with widely-known JS dialect |

Possible future: WebAssembly via a third VM (any language that compiles to WASM). Not in scope for v1.

## Shared syscall surface

Same APIs available from both languages. Implementation lives once in Kotlin; bindings expose to each VM.

```lua
-- Lua
local devices = channel.devices()
for _, d in ipairs(devices) do
  print(d.id, d.kind)
end
```

```javascript
// JavaScript (same script logically)
const devices = channel.devices();
for (const d of devices) {
  console.log(d.id, d.kind);
}
```

API surface is identical — just syntax differs. Documentation can show both side-by-side.

## Scheduler / coroutine model

Kotlin coroutines are the OS scheduler under the hood. They map naturally to:

- **Lua coroutines** — `coroutine.yield()` / `coroutine.resume()` correspond to Kotlin `suspend` / continuation. `os.sleep(0.5)` yields to the scheduler.
- **JavaScript event loop / promises** — async functions, `await`, timers map to coroutine suspension.

Tick-budget protection: scripts get a budget per game tick. Exceed it and the VM yields (forcibly if needed). No script can stall the server tick; long-running work cooperates with the scheduler.

## Per-script isolation

Each script:
- Runs in its own VM instance
- Has its own filesystem namespace (cannot read other scripts' files on the same Computer)
- Has its own DB namespace (cannot read other scripts' tables)
- Cannot directly call other scripts (must go through pub/sub or RPC)

This is the same "isolation by default" principle the rest of the platform uses.

## Performance budgets (configurable per server)

- **Per-tick instruction count** — VM aborted/yielded if exceeded
- **Memory cap** — script killed if exceeded
- **Max open coroutines** — bound on concurrent in-flight async ops
- **Max DB query duration** — query killed at N ms
- **Max screen draw calls per tick** — runaway draw protection

## Standard library available to scripts

```
os.*           — sleep, time, exit
print, error   — stdout/stderr (routes to bound Screen if available, else log)
channel.*      — wireless device API
inventory.*    — driver-mediated inventory transfers
db.*           — SQL database API
screen.*       — drawing primitives (when a screen is bound)
controlplane.* — Tier-2 only: service discovery, RPC, pub/sub, KV, manifest, cron, log, metric
crypto.*       — hashing, HMAC (later)
http.*         — HTTP client (with permission, rate-limited)
```

## What scripts CANNOT do

- Spawn entities directly (must go through driver SPI)
- Modify other players' blocks
- Bypass per-script isolation (no FFI, no native code, no JVM reflection)
- Run code at server tick rate forever (must yield)
- Talk to scripts on other Computers without going through channels/RPC/pub-sub

## Boot model

- Computer placed in world → empty firmware, prompt-only screen
- Player loads a Lua/JS file onto the Computer's "disk" (via inventory item or in-game editor)
- `boot.lua` or `boot.js` runs on power-on
- Script defines what the Computer does

A "BIOS" of sorts (small platform-shipped script) handles boot, basic shell, file management. Players write everything beyond that.
