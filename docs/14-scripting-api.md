# 14 — Scripting API (R1)

OC2 ships two script hosts. Pick by file extension when invoking `run`:

- `.lua` → Cobalt (Lua 5.2 + standard libraries)
- `.js`  → Rhino (ES6, interpreter mode)

Both languages see the **same surface area** — `print`, `sleep`, `fs.*`,
`peripheral.*`, `colors.*`. Idiomatic syntax differs but semantics match.

## Execution model (R1)

- Scripts run on a **per-script worker thread**, not the server tick
- One script at a time per Computer (`run` returns "already running" if blocked)
- Use `kill` from the shell to abort the active script
- `sleep(ms)` blocks the worker thread; **does not block** the server tick
- Peripheral method calls **marshal to the server thread** internally
  (worker waits up to one tick — typical latency 0-50ms per call)
- `print(...)` flushes live to the open Computer terminal each tick;
  buffers when the screen is closed and replays on next open
- **No cooperative scheduling** yet — `os.pullEvent` / event-driven scripts
  land in R2 (see `12-followups.md`)

## Globals

### `print(...)`

Lua: variadic, tab-separated. JS: variadic, space-separated. Newline appended.

```lua
print("hello", 42, true)         -- "hello\t42\ttrue\n"
```

```js
print("hello", 42, true);         // "hello 42 true\n"
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
exposes a method surface (currently only Monitor).

| Function | Returns |
|---|---|
| `peripheral.find(kind)` | handle or nil/null |
| `peripheral.list([kind])` | array of handles (filtered by kind if given) |

## `monitor` — display + touch surface

Acquired via `peripheral.find("monitor")`. Multi-block monitors expose a
single character grid spanning the full group.

### Output

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
| `m.setBackgroundColor(c)` | ARGB int; `0` = transparent |

### Touch input (polling — R1)

```lua
local touches = m.pollTouches()
for _, t in ipairs(touches) do
  print(t.col, t.row, t.player)
end
```

`m.pollTouches()` drains the queue (up to 32 events). Players right-click the
monitor face to register touches. Event-driven `m.onTouch(handler)` lands in
R2 with cooperative scheduling.

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
| `run <file.lua\|file.js>` | start script asynchronously; returns immediately |
| `ps` | show currently running script (pid, status) |
| `kill` | abort the current script |

Output buffers and replays — close the screen, click monitor buttons, reopen
the screen, see the queued `print` lines.

## Limitations (R1)

- One script per Computer at a time
- No cooperative scheduling — long tight loops without `sleep` will starve
  other peripheral marshals (worker holds the queue slot)
- No `os.pullEvent` / event-driven scripts (R2)
- Globals don't persist across `run` invocations (each call: fresh VM)
- Output buffer per closed-screen pos capped at 32 payloads
- `sleep` is bounded to 60s per call

See `12-followups.md` for the R2 plan that lifts most of these.
