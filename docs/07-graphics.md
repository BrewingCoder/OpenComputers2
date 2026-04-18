# 07 — Graphics / Screens

What makes a base feel like an ops center: glanceable KPI dashboards. Walk in, see green = good, red = investigate.

## Tier model

| Screen | Resolution | Use case |
|---|---|---|
| **Standard Screen** | character grid (~50×16), 16 colors | text, basic bar graphs via box characters |
| **HD Screen** | vector primitives (rects/lines/text), full RGB | KPI cards, real bar/line charts, gauges |
| **Multi-block assembly** | snap N×M screens together via Screen Linker tool | the giant wall-of-status visible across the base |

Same drawing API across tiers; HD just exposes more primitives + more pixels.

## Drawing API (low-to-mid level — the WHAT, ~7-8 calls)

```lua
-- buffer model: write to buffer, flush to screen, server pushes texture update to clients
screen.clear(bg_color)
screen.set_pixel(x, y, color)                  -- HD tier only
screen.draw_text(x, y, "Iron: 150/200", color, bg_color)
screen.draw_rect(x, y, w, h, color, fill)
screen.draw_line(x1, y1, x2, y2, color)
screen.draw_image(x, y, image_handle)          -- preloaded sprites/icons
screen.measure_text(text, font_size)            -- returns w, h for layout
screen.flush()                                  -- one network packet to clients
```

That's the entire platform-side surface.

## Touch input

YES, ship it. Simple model: `screen.on_click(handler)` callback gets `(x, y, button, player)`. No gestures, no multi-touch.

```lua
screen.on_click(function(x, y, button, player)
  if x < 50 and y < 20 then toggle_reactor() end
end)
```

Lets players build settings panels and interactive dashboards.

## Widgets ship as scripts, NOT in the platform

Consistent with [`01-platform-vs-software.md`](01-platform-vs-software.md).

KPI cards, bar charts, gauges, sparklines — all built ON the drawing primitives, distributed as a script-library/sample-pack:

```lua
local dash = require("dashboard")  -- ships in OC2-Widgets sample mod

dash.kpi(screen, { x = 0, y = 0, label = "Iron",
  current = inv:count("iron_ingot"), target = 200,
  warn_at = 0.5, crit_at = 0.2 })
-- renders the colored card with target line, current bar, threshold tinting

dash.bargraph(screen, { x = 0, y = 20, w = 100, h = 40,
  series = history_data, color = "#3498db" })

dash.gauge(screen, { x = 100, y = 0, value = power.current,
  max = power.cap, label = "RF/t" })
```

Players install OC2 → bare drawing primitives. Players install OC2 + OC2-Widgets → full dashboard library. Players who want their own theme system ignore Widgets and write their own.

## Render model (under the covers — short version)

Each Screen block has a server-side buffer (in-memory state, persisted across saves). When a script calls drawing primitives:
1. Mutations apply to the buffer
2. `flush()` marks the screen dirty
3. Next tick window: server diffs the buffer, pushes only changed regions to clients
4. Client builds a dynamic texture from the buffer, applies to the block face
5. No re-render unless dirty (no per-tick wasted work)

Standard CC/OC pattern. Well-trodden.

## Multi-block assembly

- Place N×M Screen blocks adjacent in a flat grid
- Right-click with a "Screen Linker" tool → snap them into one logical screen
- Drawing API just sees a bigger canvas
- Visible from a distance (regular block face rendering — no special LOD work)

## Limits (configurable per-server)

| Limit | Default | Why |
|---|---|---|
| Buffer KB cap per screen | 256 KB | Memory bloat protection |
| Per-tick draw call cap | 500 calls | Runaway script protection |
| Max linked screens per assembly | 8×8 = 64 | Sanity bound |
| Screen update packet size | 8 KB | Network politeness |

## Open question (decide on implementation)

- **Screen-as-stdout for `print()` output** — should the OS automatically render `print()` output to a Screen registered as the "console"? Probably yes, configurable per script. Useful for boot messages and logs.

## Explicitly NOT in the platform graphics API

- **Animation primitives** — write your own with the buffer + flush
- **Charting library** — script-side (OC2-Widgets)
- **Themes/styling** — script-side
- **Fonts beyond a built-in monospace + maybe one variable-width** — no font loading from disk
- **3D / vectors / shaders** — forever no, falls under [`08-never-list.md`](08-never-list.md)
