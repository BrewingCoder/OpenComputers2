# 14 — Scripting API (R1)

OC2 ships two script hosts. Pick by file extension when invoking `run`:

- `.lua` → Cobalt (Lua 5.2 + standard libraries)
- `.js`  → Rhino (ES6, interpreter mode)

Both languages see the **same surface area** — `print`, `sleep`, `fs.*`,
`peripheral.*`, `colors.*`. Idiomatic syntax differs but semantics match.

## Execution model

- Scripts run on **per-script worker threads** — never on the server tick
- **Multi-script per Computer** — one foreground (terminal-attached) plus
  any number of background scripts. `run` starts foreground; `bg` starts
  background; `jobs` lists; `fg <pid>` promotes; `kill [pid]` terminates;
  `tail [pid] [-n N]` shows the recent output of any script
- `sleep(ms)` blocks the worker thread; **does not block** the server tick
- `os.pullEvent` blocks until a matching event arrives — same mechanism;
  worker parks, server keeps ticking
- Peripheral method calls **marshal to the server thread** internally
  (worker waits up to one tick — typical latency 0–50ms per call)
- `print(...)` from the foreground script flushes live to the open Computer
  terminal each tick. Background-script print output goes to a per-script
  bounded tail buffer (200 lines) — read it with `tail` / `tail <pid>`.
  Bg scripts that crash with an unhandled error also surface a
  `[bg pid=N name] crashed: <msg>` banner in the foreground terminal —
  same model as Java/C# unhandled-exception printing to stderr. `pcall` /
  `try/catch` suppress as expected.

## Computer power state

A freshly placed Computer is **OFF** — the terminal shows
`[ powered off — press Power ]` and shell commands are rejected. Click the
Power button (top-left of the GUI) to turn on. Power state persists across
save/load.

- **Power off** kills any in-flight scripts (foreground + background) and
  clears the recent output buffer
- **Reset** (red square button) kills running scripts and wipes the terminal,
  but leaves power state alone — useful for "stop the runaway script" without
  fully shutting down. Greyed out when the computer is off.

## Globals

### `print(...)`

Lua: variadic, tab-separated. JS: variadic, space-separated. Newline appended.

```lua
print("hello", 42, true)         -- "hello\t42\ttrue\n"
```

```js
print("hello", 42, true);         // "hello 42 true\n"
```

### `os.pullEvent([filter])` / `os.queueEvent(name, ...)` / `os.startTimer(secs)`

Cooperative scheduling primitives, CC:Tweaked-style. The script blocks on
`os.pullEvent` until an event arrives — the worker thread sleeps; the server
keeps ticking; other peripherals/scripts on the same channel don't pause.

| Function | Returns |
|---|---|
| `os.pullEvent()` | `name, args...` from the next event |
| `os.pullEvent("monitor_touch")` | next event of that name; non-matching events are dropped (Phase 1 — no requeue yet) |
| `os.queueEvent(name, ...)` | enqueue an event into THIS script's queue |
| `os.startTimer(secs)` | timer id; fires `"timer", id` event after [secs] seconds |

Event sources shipped in Phase 1:
- **`monitor_touch`** — args: `(col, row, playerName)`. Fires for every right-click on a monitor sharing your wifi channel.
- **`network_message`** — args: `(from, body)`. Fires every time a `network.send` arrives at this computer.
- **`timer`** — args: `(timerId)`. Fires once for each `os.startTimer` after the delay.

```lua
print("waiting for input...")
while true do
  local name, a, b, c = os.pullEvent()
  if name == "monitor_touch" then
    print("touch:", a, b, "by", c)
  elseif name == "network_message" then
    print("msg from", a, ":", b)
  elseif name == "timer" then
    print("timer", a, "fired")
    return
  end
end
```

### `sleep(ms)`

Block the script for `ms` milliseconds. Range: 0 to 60000 (clamped).
The server keeps ticking — this only pauses the worker.

```lua
sleep(250)
```

## `fs` — per-computer filesystem

All paths resolve **relative to the script's cwd**. Same mount the shell
uses. Path utilities (`combine`/`getName`/`getDir`) are pure string ops.

### Filesystem ops

| Function | Returns | Notes |
|---|---|---|
| `fs.list(path)` | array/table of names | throws on missing dir |
| `fs.exists(path)` | bool | never throws |
| `fs.isDir(path)` | bool | never throws |
| `fs.size(path)` | bytes | throws on missing |
| `fs.read(path)` | string (UTF-8) | throws on missing or directory |
| `fs.write(path, text)` | void | overwrites; throws on capacity |
| `fs.append(path, text)` | void | extends; throws on capacity |
| `fs.mkdir(path)` | void | creates parent dirs as needed |
| `fs.delete(path)` | void | recursive on directories |
| `fs.capacity()` | total bytes | per-computer limit (default 2 MiB) |
| `fs.free()` | remaining bytes | live |

### Path utilities

| Function | Example |
|---|---|
| `fs.combine(a, b)` | `"foo"`, `"bar"` → `"foo/bar"` |
| `fs.getName(path)` | `"a/b/c.txt"` → `"c.txt"` |
| `fs.getDir(path)` | `"a/b/c.txt"` → `"a/b"` |

### Errors

Lua: throws via `error(...)` — catch with `pcall`.
JS: throws — catch with `try/catch`.

```lua
local ok, err = pcall(fs.read, "missing.txt")
if not ok then print("nope:", err) end
```

```js
try { fs.read("missing.txt"); } catch (e) { print("nope:", e.message); }
```

## `peripheral` — devices on the wifi channel

A peripheral is any block on the same wifi channel as this Computer that
exposes a method surface. R1 ships:

- `monitor` — display + touch (see below)
- `inventory` / `fluid` / `energy` / `redstone` — Adapter parts that wrap
  the adjacent block's NeoForge capability (see [Adapter parts](#adapter-parts))

| Function | Returns |
|---|---|
| `peripheral.find(kind)` | first handle of [kind] on this channel, or nil/null |
| `peripheral.list([kind])` | array of handles (filtered by kind if given) |

Each handle has `.kind` (always the type id) and most have `.name` (the
display name — auto-generated like `inv_north_3` or whatever the player labeled
it). Use `.name` to disambiguate when several adapters expose the same kind.

## `monitor` — display + touch surface

Acquired via `peripheral.find("monitor")`. Multi-block monitors expose a
single character grid spanning the full group.

### Output (text grid)

| Method | Notes |
|---|---|
| `m.write(text)` | append at cursor; wraps + scrolls; ANSI escapes parsed |
| `m.println(text)` | `write(text)` + newline |
| `m.clear()` | wipe buffer + reset cursor + reset colors |
| `m.setCursorPos(col, row)` | 0-indexed |
| `m.getCursorPos()` | returns `(col, row)` |
| `m.getSize()` | returns `(cols, rows)` |
| `m.setForegroundColor(c)` | ARGB int |
| `m.setTextColor(c)` | alias for `setForegroundColor` (CC:T-aligned) |
| `m.setBackgroundColor(c)` | ARGB int; `0` = transparent (text grid only — pixel layer below shows through) |

### Output (HD pixel layer)

The pixel buffer renders BELOW the cell grid, so text overlays graphics.
12 px per cell; an 80×27 cell group is 960×324 px. Persisted in NBT.

| Method | Notes |
|---|---|
| `m.getPixelSize()` | returns `(pxW, pxH)` |
| `m.clearPixels(argb)` | fill the entire pixel layer |
| `m.setPixel(x, y, argb)` | single pixel; out-of-range silently dropped |
| `m.drawRect(x, y, w, h, argb)` | filled rect (clipped to surface) |
| `m.drawRectOutline(x, y, w, h, argb, thickness?)` | outlined rect; thickness defaults to 1 |
| `m.drawLine(x1, y1, x2, y2, argb)` | Bresenham line |
| `m.drawGradientV(x, y, w, h, topArgb, bottomArgb)` | vertical lerp |
| `m.fillCircle(cx, cy, r, argb)` | filled circle |

### Touch input

Two equivalent surfaces:

**Polling** (drains a queue of up to 32 events):
```lua
for _, t in ipairs(m.pollTouches()) do
  print(t.col, t.row, t.px, t.py, t.player)
end
```

**Event-driven** (cooperative scheduling — R2):
```lua
local _, col, row, px, py, player = os.pullEvent("monitor_touch")
```

Touch payload includes both cell coords (`col`, `row`) for text-grid hit
testing AND pixel coords (`px`, `py`) for HD-layer hit testing — match the
button-bounds check to whichever rendering surface the button lives on.

### Per-script peripheral lease

The first script to mutate a monitor (or any peripheral with a lease check)
implicitly leases it for the script's lifetime. A second script trying to
mutate the same monitor sees:

```
peripheral monitor is held by pid=3 (reactor.lua) -- kill it or wait
```

thrown as a `LuaError` / `js error`. Lease auto-releases when the holder
script ends (kill / crash / normal exit). Read-only methods (`getSize`,
`getPixelSize`, `pollTouches`) skip the lease — many readers, one writer.

### ANSI escape sequences

`m.write` parses a small CSI subset. Useful for porting `bash`/`vim`-style output.

| Sequence | Meaning |
|---|---|
| `\27[<n>m` | SGR — colors. Standard 30-37 (fg), 40-47 (bg), 90-97 (bright fg), 100-107 (bright bg). `0` resets. `38;2;r;g;b` / `48;2;r;g;b` for 24-bit. |
| `\27[<row>;<col>H` | Move cursor (1-indexed) |
| `\27[2J` | Clear screen |
| `\27[A/B/C/D` | Cursor up/down/right/left |

## `colors` — 16-color palette

CC:Tweaked-aligned names + values. Stored as opaque ARGB ints so they pass
straight to color-setter methods.

```
colors.white      colors.orange     colors.magenta    colors.lightBlue
colors.yellow     colors.lime       colors.pink       colors.gray
colors.lightGray  colors.cyan       colors.purple     colors.blue
colors.brown      colors.green      colors.red        colors.black
```

```lua
m.setTextColor(colors.green)
m.write("OK")
m.setTextColor(colors.red)
m.write(" FAIL")
```

```js
m.setTextColor(colors.green);
m.write("OK");
m.setTextColor(colors.red);
m.write(" FAIL");
```

## Worked example — clickable button panel

```lua
local m = peripheral.find("monitor")
if not m then return end
local w, h = m.getSize()

local buttons = {
  { label="RUN",   bg=colors.green },
  { label="PAUSE", bg=colors.yellow },
  { label="EXIT",  bg=colors.red },
}

local row = math.floor(h / 2)
local function colFor(i)
  local pad = 4
  if i == 1 then return 1 end
  if i == 2 then return math.floor((w - #buttons[2].label - pad) / 2) end
  return w - #buttons[i].label - pad - 1
end

local function draw()
  m.clear()
  for i, b in ipairs(buttons) do
    m.setTextColor(colors.black)
    m.setBackgroundColor(b.bg)
    m.setCursorPos(colFor(i), row)
    m.write("  " .. b.label .. "  ")
  end
  m.setBackgroundColor(0)
  m.setTextColor(colors.white)
  m.setCursorPos(0, h - 1)
  m.write("right-click a button")
end

draw()
while true do
  for _, t in ipairs(m.pollTouches()) do
    for i, b in ipairs(buttons) do
      local c = colFor(i)
      if t.row == row and t.col >= c and t.col < c + #b.label + 4 then
        if b.label == "EXIT" then
          m.clear(); m.setCursorPos(0, 0); m.write("done.")
          return
        end
        print(b.label .. " by " .. t.player)
      end
    end
  end
  sleep(100)
end
```

## Adapter parts

The **Adapter** block hosts up to one part per face (6 max). Each installed
part registers as its own peripheral on **its own per-part wifi channel** —
two parts on the same adapter can sit on different channels. Scripts look
them up via `peripheral.find(kind)` / `peripheral.list(kind)` exactly like
a monitor.

### Install / configure / remove

| Action | Gesture |
|---|---|
| **Install** | Hold a part item, right-click the adapter face you want to attach it to |
| **Configure** | Empty-hand right-click an installed part → opens the Part Settings GUI |
| **Remove** | Sneak + empty-hand right-click an installed part → drops the part item back |

The Part Settings GUI is the same panel for every part kind. It exposes:

- **name** — script-facing label (`peripheral.find(...).name`); auto-generated like `inv_north_3` until edited
- **channel** — per-part wifi channel; "▼" picks from channels of computers within 32 blocks
- **access side** (inventory / fluid / energy only) — which side of the adjacent block to read from. `auto` = the install face's opposite (default); explicit pick (`up`, `down`, etc.) is useful for sided machines (furnace top = input slots, bottom = output, sides = fuel)
- **kind-specific options** — see each kind's section below for what's exposed

The adapter is just a physical mount — break it (or sneak-remove the part)
and the items pop out toward the player.

R1 ships **five** part kinds:

### `inventory` — wraps any `IItemHandler` (chests, barrels, hoppers, machines)

| Method | Notes |
|---|---|
| `inv.size()` | slot count |
| `inv.getItem(slot)` | `{id, count}` or nil; **slots are 1-indexed** |
| `inv.list()` | array length [size]; nils for empty slots |
| `inv.find(itemId)` | first slot containing [itemId] (`"minecraft:diamond"`), or -1 |
| `inv.push(slot, target [, count [, targetSlot]])` | move items → returns count moved |
| `inv.pull(source, slot [, count [, targetSlot]])` | inverse of push |
| `inv.destroy(slot [, count])` | void items → returns count destroyed |

```lua
local chest = peripheral.find("inventory")
local diamonds = chest.find("minecraft:diamond")
if diamonds > 0 then
  local s = chest.getItem(diamonds)
  print(s.id, s.count)
end
```

### `fluid` — wraps any `IFluidHandler` (tanks, machines)

| Method | Notes |
|---|---|
| `fl.tanks()` | tank count |
| `fl.getFluid(tank)` | `{id, amount}` or nil; **1-indexed**, amount in mB |
| `fl.list()` | snapshots for every tank |
| `fl.push(target [, amount])` | move mB → returns mB moved |
| `fl.pull(source [, amount])` | inverse |
| `fl.destroy(amount)` | void fluid → returns mB destroyed |

### `energy` — wraps any `IEnergyStorage` (FE buffers, generators)

| Method | Notes |
|---|---|
| `en.stored()` | current FE |
| `en.capacity()` | max FE |
| `en.push(target [, amount])` | transfer FE → returns amount moved |
| `en.pull(source [, amount])` | inverse |
| `en.destroy(amount)` | void FE → returns amount destroyed |

### `block` — read adjacent block state / break-and-route the block

Reads the block sitting in front of this face (id, NBT, light, redstone power,
hardness, position). Can also break the block and route its loot into a
target inventory.

| Method | Notes |
|---|---|
| `b.read()` | `{id, isAir, lightLevel, redstonePower, hardness, pos: {x,y,z}, nbt?}` or nil/null |
| `b.harvest(target)` | break the block; route loot into `target` (an inventory peripheral). Items that don't fit drop on the ground. Returns the array of `{id, count}` snapshots routed to `target`. |

Anti-dupe: `harvest` is a *move* — the block ceases to exist atomically with
the items appearing. No copy path. Pass `nil` / `null` as the target to break
the block with all loot dropping on the ground (purely destructive).

```lua
local b = peripheral.find("block")
local r = b.read()
print(r.id, r.lightLevel, r.redstonePower)
if r.id == "minecraft:stone" then
  local inv = peripheral.find("inventory")
  for _, s in ipairs(b.harvest(inv)) do print("got", s.id, s.count) end
end
```

### `redstone` — read input / write output on this face

Not a NeoForge capability — vanilla redstone goes through the block face
directly.

| Method | Notes |
|---|---|
| `rs.getInput()` | signal feeding INTO our face (0–15) |
| `rs.getOutput()` | signal we're emitting OUT of our face (0–15) |
| `rs.setOutput(level)` | set emission; sticky until changed |

**Config GUI option — Inverted.** Toggle in the part settings panel. When ON:
- `getInput()` returns `15 - actual_level` (powered face reads as 0; unpowered as 15)
- `getOutput()` reflects what the script set, but the wire emits the inverse
- `setOutput(15)` emits 0 to the wire; `setOutput(0)` emits 15

Useful for "do X when NOT powered" patterns without writing the inversion in
every script.

```lua
local rs = peripheral.find("redstone")
if rs.getInput() > 0 then
  rs.setOutput(15)   -- echo full strength while powered
else
  rs.setOutput(0)
end
```

### `bridge` — universal protocol shim

Install a Bridge Part on an Adapter face touching anything scriptable from
another mod (Big/Extreme Reactors, ZeroCore turbines/energizers, future: any
CC `IPeripheral` mod). The Bridge introspects the adjacent BlockEntity and
surfaces its API generically.

| Property/Method | Notes |
|---|---|
| `b.protocol` | adapter id (`"zerocore"`, future: `"cc"`, `"caps"`); `"none"` if nothing claimed the BE |
| `b.target` | underlying BE class FQN (or block id) — diagnostic only |
| `b.methods()` | list of method names on the underlying peripheral |
| `b.call(name, ...args)` | invoke a method, returns single value or list |
| `b.describe()` | identity map: `{protocol, name, target, methods}`. Does **not** invoke any methods — pure metadata. Call the getters yourself if you want state. |

Method names + value shapes are **mod-specific** — the bridge does no
normalization. Scripts are talking to the underlying mod's API, OC2 is just
the courier. Use `b.methods()` or `b.describe()` to see what's available,
then call the getters you actually want:

```lua
local r = peripheral.find("bridge")
print(r.protocol, r.target)
for _, m in ipairs(r.methods()) do print(m) end

-- Driving an Extreme Reactor:
print(r.call("getEnergyStored"), "/", r.call("getEnergyCapacity"))
r.call("setActive", true)
r.call("setAllControlRodLevels", 50)     -- 50% inserted across all rods
```

> **Never auto-probe unknown methods.** Some mod peripherals expose
> zero-arg methods with destructive side effects (e.g. ZeroCore reactors
> surface `doEjectFuel` / `doEjectWaste`). Enumerate names first; only
> call methods you've specifically identified as safe.

Both `r.call(...)` (dot-syntax) and `r:call(...)` (colon-syntax) work
identically — the wrappers detect and strip the implicit receiver.

## `network` — wifi messaging between computers

Every computer has built-in wifi (no separate adapter block). Messages route by
the computer's wifi channel (set via NBT or future `chan` shell command).
Inboxes are **in-memory and restart-transient** — perfect for live coordination,
not for persistence. If you need durable state, write it to `fs`.

| Function | Returns | Notes |
|---|---|---|
| `network.id()` | int | this computer's id (matches `id` shell cmd) |
| `network.send(msg)` | void | broadcast on **own** channel |
| `network.send(msg, ch)` | void | send on a specific channel |
| `network.recv()` | `{from, body}` or nil/null | pop oldest pending message |
| `network.peek()` | `{from, body}` or nil/null | look without consuming |
| `network.size()` | int | number of pending messages |

Message body is a string; if you need structure, encode/decode JSON
(see [`json`](#json--lua-only-js-has-built-in-json) below). Bounded per inbox
(currently 32 messages, drop-oldest); per-message limit is 4 KiB UTF-8.

Self-exclusion: `network.send` skips your own inbox. Two computers on the
same channel get each other's broadcasts but never see their own.

```lua
network.send("hello world")          -- broadcast on own channel
network.send("hi ops", "ops")        -- specific channel
local m = network.recv()
if m then print(m.from .. ": " .. m.body) end
```

```js
network.send("hello world");
network.send("hi ops", "ops");
const m = network.recv();
if (m) print(m.from + ": " + m.body);
```

## `json` — Lua only (JS has built-in JSON)

Lua has no native JSON. OC2 ships a Gson-backed binding so Lua scripts can
serialize tables for `network.send`.

| Function | Returns | Notes |
|---|---|---|
| `json.encode(value)` | string | tables with int keys 1..n → arrays; otherwise objects |
| `json.decode(text)` | LuaValue | objects → string-keyed tables; arrays → 1..n tables |

Decode errors raise via `error(...)`; catch with `pcall`.

```lua
local payload = json.encode({ kind = "ping", ts = 42 })
network.send(payload)

local m = network.recv()
if m then
  local t = json.decode(m.body)
  print(t.kind, t.ts)
end
```

JS scripts use the standard `JSON` object — no OC2 binding needed.

```js
network.send(JSON.stringify({ kind: "ping", ts: 42 }));
const m = network.recv();
if (m) {
  const t = JSON.parse(m.body);
  print(t.kind, t.ts);
}
```

## Shell commands

| Command | Notes |
|---|---|
| `run <file.lua\|file.js>` | start script in foreground; output goes to terminal |
| `bg <file.lua\|file.js>` | start script in background; output drained but not shown |
| `jobs` | list every running script (foreground + background) with `[pid] fg/bg state name` |
| `ps` | foreground script only (alias for the first `jobs` row) |
| `fg <pid>` | promote a background script to foreground (only when no fg is running) |
| `kill` | terminate the foreground script |
| `kill <pid>` | terminate any specific script by pid |

Foreground script output buffers and replays — close the screen, click
monitor buttons, reopen the screen, see the queued `print` lines.

## Common patterns

**Event loop daemon (background-friendly):**
```lua
-- listen.lua — run via `bg listen.lua`. Reacts to network messages forever.
while true do
  local _, from, body = os.pullEvent("network_message")
  -- do something with body
end
```

**Periodic poll without busy-loop:**
```lua
local id = os.startTimer(5)
while true do
  local n, t = os.pullEvent("timer")
  if t == id then
    -- 5 seconds passed; do work
    id = os.startTimer(5)  -- re-arm
  end
end
```

**Cross-computer chat:**
```lua
network.send(json.encode({ kind = "alert", msg = "intruder!" }))
```

**Item routing:**
```lua
local src = peripheral.find("inventory")  -- or peripheral.list and filter by name
local dst = nil
for _, p in ipairs(peripheral.list("inventory")) do
  if p.name == "smelter_in" then dst = p end
end
src.push(1, dst, 8)   -- move 8 items from src slot 1 → dst (first fit)
```

## Limitations (R1)

- Background scripts have no per-script log viewer yet (drained + dropped)
- `os.pullEvent` filter drops non-matching events instead of CC:T-style requeue
- JS doesn't support `os.pullEvent` yet (Phase 3)
- Globals don't persist across `run` invocations (each call: fresh VM)
- Output buffer per closed-screen pos capped at 32 payloads
- `sleep` is bounded to 60s per call

See `12-followups.md` for the followup roadmap.
