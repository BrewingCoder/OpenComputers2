-- /rom/examples/ui/divider.lua -- visual showcase for ui_v1.Divider.
--
-- Study material. Shipped in ROM so every computer has it:
--   run /rom/examples/ui/divider.lua
--
-- Setup: computer wired to a 2x2 (or larger) monitor group.
--
-- Covers every Divider prop:
--   [1] banner at top
--   [2] three horizontal dividers of increasing thickness (1/3/6)
--   [3] color token palette: edge/muted/good/warn/bad/info/hi
--   [4] three vertical dividers of increasing thickness (1/3/6)
--   [5] orientation="v" with a color token
--   [6] visible=false (blank)
--   [7] set() mutation (starts "edge", mutated to "bad" + thicker)
--   [8] hex color override (magenta)
--   [9] thickness clamp (thickness=0 still renders 1-px line)
--   [10] theme swap: 'edge' -> orange, then reset

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

-- Wipe every layer before we render.
mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- [1] Banner
ui.mount(mon, ui.Label{
    x=0, y=0, width=480, height=12,
    text="ui_v1 Divider showcase",
    color="hi", bg="bgCard", align="center",
})

-- [2] Horizontal dividers of increasing thickness.
ui.mount(mon, ui.Label{ x=8, y=24, width=300, height=12,
    text="horizontal thickness 1 / 3 / 6:", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=8, y=44,  width=320, height=4,  thickness=1 })
ui.mount(mon, ui.Divider{ x=8, y=60,  width=320, height=6,  thickness=3 })
ui.mount(mon, ui.Divider{ x=8, y=80,  width=320, height=10, thickness=6 })

-- [3] Color token palette. Seven horizontal dividers labelled by token.
local tokens = {"edge","muted","good","warn","bad","info","hi"}
for i, c in ipairs(tokens) do
    local by = 104 + (i - 1) * 14
    ui.mount(mon, ui.Label{ x=8, y=by, width=60, height=12,
        text=c, color="muted", align="left" })
    ui.mount(mon, ui.Divider{
        x=76, y=by + 5, width=240, height=2, thickness=2, color=c,
    })
end

-- [4] Vertical dividers of increasing thickness.
ui.mount(mon, ui.Label{ x=360, y=24, width=200, height=12,
    text="vertical thickness 1 / 3 / 6:", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=370, y=44, width=4,  height=120, orientation="v", thickness=1 })
ui.mount(mon, ui.Divider{ x=400, y=44, width=6,  height=120, orientation="v", thickness=3 })
ui.mount(mon, ui.Divider{ x=430, y=44, width=10, height=120, orientation="v", thickness=6 })

-- [5] Vertical divider with color token.
ui.mount(mon, ui.Label{ x=360, y=176, width=180, height=12,
    text="vertical 'hi':", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=400, y=196, width=8, height=60,
    orientation="v", thickness=4, color="hi" })

-- [6] visible=false (nothing drawn; label explains).
ui.mount(mon, ui.Label{ x=8, y=208, width=320, height=12,
    text="below: visible=false (blank)", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=8, y=224, width=320, height=4, thickness=2, visible=false })

-- [7] set() mutation.
local mut = ui.Divider{ x=8, y=240, width=320, height=6, thickness=1, color="edge" }
mut:set{ color="bad", thickness=4 }
ui.mount(mon, mut)
ui.mount(mon, ui.Label{ x=8, y=252, width=320, height=12,
    text="mutated via set() -> bad, thickness=4", color="muted", align="left" })

-- [8] Hex color override (magenta).
ui.mount(mon, ui.Label{ x=8, y=272, width=320, height=12,
    text="hex 0xFFFF00FF (magenta):", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=8, y=290, width=320, height=4, thickness=2, color=0xFFFF00FF })

-- [9] Thickness clamp: thickness=0 still renders 1 px.
ui.mount(mon, ui.Label{ x=8, y=302, width=320, height=12,
    text="thickness=0 (clamps to 1):", color="muted", align="left" })
ui.mount(mon, ui.Divider{ x=8, y=320, width=320, height=4, thickness=0 })

ui.render()
print("rendered; swapping theme in 3s...")
sleep(3)

ui.setTheme{
    bg=0xFF0A0F1A, bgCard=0xFF121827, fg=0xFFFFFFFF,
    hi=0xFFE6F0FF, muted=0xFF7A8597,
    good=0xFF2ECC71, warn=0xFFF1C40F, bad=0xFFE74C3C, info=0xFF3498DB,
    edge=0xFFFF8800,  -- orange instead of dark slate
    primary=0xFF3498DB, ghost=0xFF2E3A4E, danger=0xFFE74C3C,
}
ui.render()
print("'edge' dividers now orange for 5s")
sleep(5)
ui.setTheme()
ui.render()
print("theme reset; done")

-- Hold the lease so the monitor keeps showing the result until killed.
print("-- holding display. run `kill` from another shell to clear --")
while true do sleep(60) end
