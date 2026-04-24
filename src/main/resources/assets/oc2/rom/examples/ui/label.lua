-- /rom/examples/ui/label.lua -- visual showcase for ui_v1.Label.
--
-- Study material. Shipped in ROM so every computer has it:
--   run /rom/examples/ui/label.lua
--
-- Setup: computer wired to a 2x2 monitor group (or larger).
--
-- Covers every Label prop:
--   [1] banner at top: centered, hi-color, bgCard rect
--   [2] three rows demonstrating align=left / center / right
--   [3] color-token palette (good/warn/bad/info/hi/muted/primary)
--   [4] hex-color override (magenta)
--   [5] visible=false (blank row)
--   [6] set() mutation ("mutated via set()" in green)
--   [7] right-flush hex cyan
--   [8] no-bg label (text only, no rect)
--   [9] theme swap: 'good' -> orange for 5s, then reset to default

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

-- Wipe every layer before we render. All three steps are load-bearing:
--   1. clearPixels(opaque-black)     wipes the HD pixel buffer
--   2. setBackgroundColor(black)     sets the bg color that clear() uses
--   3. clear()                       repaints every text cell with the bg
-- Skip any of these and stale reactor-dashboard artifacts bleed through.
mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- Banner
ui.mount(mon, ui.Label{
    x=0, y=0, width=320, height=12,
    text="ui_v1 Label showcase",
    color="hi", bg="bgCard", align="center",
})

-- Alignment demo
for i, align in ipairs({"left","center","right"}) do
    ui.mount(mon, ui.Label{
        x=0, y=24 + (i-1)*12, width=320, height=12,
        text="align="..align,
        color="fg", bg="bgCard", align=align,
    })
end

-- Color token palette
local tokens = {"good","warn","bad","info","hi","muted","primary"}
for i, tok in ipairs(tokens) do
    ui.mount(mon, ui.Label{
        x=8, y=72 + (i-1)*12, width=200, height=12,
        text="color="..tok,
        color=tok, align="left",
    })
end

-- Hex override (magenta)
ui.mount(mon, ui.Label{
    x=8, y=168, width=200, height=12,
    text="hex color 0xFFFF00FF",
    color=0xFFFF00FF, align="left",
})

-- visible=false (no output expected)
ui.mount(mon, ui.Label{
    x=8, y=180, width=200, height=12,
    text="INVISIBLE -- should NOT appear",
    color="bad", visible=false,
})

-- set() mutation
local mut = ui.Label{ x=8, y=192, width=200, height=12, text="old", color="muted" }
mut:set{ text="mutated via set()", color="good" }
ui.mount(mon, mut)

-- Right-aligned, hex cyan
ui.mount(mon, ui.Label{
    x=0, y=204, width=320, height=12,
    text="right hex 0xFF00FFFF",
    color=0xFF00FFFF, align="right",
})

-- No bg (default)
ui.mount(mon, ui.Label{
    x=8, y=216, width=200, height=12,
    text="no bg prop",
    color="fg", align="left",
})

ui.render()
print("rendered; swapping theme in 3s...")
sleep(3)

ui.setTheme{
    bg=0xFF0A0F1A, bgCard=0xFF121827, fg=0xFFFFFFFF,
    hi=0xFFE6F0FF, muted=0xFF7A8597,
    good=0xFFFF8800,  -- orange instead of green
    warn=0xFFF1C40F, bad=0xFFE74C3C, info=0xFF3498DB,
    edge=0xFF2E3A4E, primary=0xFF3498DB, ghost=0xFF2E3A4E, danger=0xFFE74C3C,
}
ui.render()
print("'good'-color labels should now be orange for 5s")

sleep(5)
ui.setTheme()  -- nil -> reset to DEFAULT_THEME
ui.render()
print("theme reset; done")

-- Hold the peripheral lease open so the monitor keeps showing our labels.
-- When the script exits, MonitorBlockEntity's onRelease wipes the text + pixel
-- buffers (so a later script doesn't inherit stale content). That's the right
-- call in general, but for a SHOWCASE you want the output to stay on-screen
-- until you decide to tear it down -- so we park here until the user kills us.
print("-- holding display. run `kill` from another shell to clear --")
while true do sleep(60) end
