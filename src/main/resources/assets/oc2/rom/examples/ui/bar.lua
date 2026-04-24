-- /rom/examples/ui/bar.lua -- visual showcase for ui_v1.Bar.
--
-- Study material. Shipped in ROM so every computer has it:
--   run /rom/examples/ui/bar.lua
--
-- Setup: computer wired to a 2x2 (or larger) monitor group.
--
-- Covers every Bar prop:
--   [1] banner at top
--   [2] 5 horizontal bars sweeping fill % (0 / 25 / 50 / 75 / 100)
--   [3] 6 horizontal bars demonstrating color tokens (good/warn/bad/info/hi/primary)
--   [4] horizontal bar with marker at 70% + markerColor
--   [5] horizontal bar with showPct=true
--   [6] 5 vertical bars showing fills of 20/40/60/80/100
--   [7] visible=false bar (blank row)
--   [8] set() mutation (starts empty, mutated to 60% bad)
--   [9] hex color override (magenta)
--   [10] theme swap: 'good' -> orange, then reset

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

-- Wipe every layer before we render -- all three ops are load-bearing.
-- (See /rom/examples/ui/label.lua for the full rationale.)
mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- Banner
ui.mount(mon, ui.Label{
    x=0, y=0, width=480, height=12,
    text="ui_v1 Bar showcase",
    color="hi", bg="bgCard", align="center",
})

-- [2] Fill-percentage sweep. Five bars showing 0/25/50/75/100.
local pcts = {0, 25, 50, 75, 100}
for i, v in ipairs(pcts) do
    local bx = 8 + (i - 1) * 92
    ui.mount(mon, ui.Label{ x=bx, y=24, width=88, height=12,
        text=tostring(v) .. "%", color="muted", align="left" })
    ui.mount(mon, ui.Bar{
        x=bx, y=40, width=88, height=16,
        value=v, min=0, max=100,
    })
end

-- [3] Color token palette.
local colors = {"good","warn","bad","info","hi","primary"}
for i, c in ipairs(colors) do
    local bx = 8 + (i - 1) * 78
    ui.mount(mon, ui.Label{ x=bx, y=68, width=76, height=12,
        text=c, color="muted", align="left" })
    ui.mount(mon, ui.Bar{
        x=bx, y=84, width=76, height=16,
        value=65, color=c,
    })
end

-- [4] Marker bar: marker at 70%, markerColor hi.
ui.mount(mon, ui.Label{ x=8, y=112, width=200, height=12,
    text="marker at 70% (white line)", color="muted", align="left" })
ui.mount(mon, ui.Bar{
    x=8, y=128, width=300, height=16,
    value=45, marker=70, markerColor="hi",
})

-- [5] showPct=true.
ui.mount(mon, ui.Label{ x=320, y=112, width=200, height=12,
    text="showPct = true", color="muted", align="left" })
ui.mount(mon, ui.Bar{
    x=320, y=128, width=200, height=16,
    value=42, showPct=true,
})

-- [6] Vertical bars at 20/40/60/80/100.
ui.mount(mon, ui.Label{ x=8, y=156, width=200, height=12,
    text="vertical bars:", color="muted", align="left" })
local vpcts = {20, 40, 60, 80, 100}
for i, v in ipairs(vpcts) do
    local bx = 8 + (i - 1) * 40
    ui.mount(mon, ui.Bar{
        x=bx, y=172, width=28, height=80,
        value=v, orientation="v",
    })
    ui.mount(mon, ui.Label{ x=bx, y=256, width=28, height=12,
        text=tostring(v) .. "%", color="muted", align="center" })
end

-- [7] visible=false.
ui.mount(mon, ui.Label{ x=240, y=172, width=200, height=12,
    text="below: visible=false (blank)", color="muted", align="left" })
ui.mount(mon, ui.Bar{
    x=240, y=188, width=200, height=16,
    value=80, color="bad", visible=false,
})

-- [8] set() mutation: starts at 0/muted, mutated to 60/bad.
local mut = ui.Bar{ x=240, y=212, width=200, height=16,
    value=0, color="muted" }
mut:set{ value=60, color="bad" }
ui.mount(mon, mut)
ui.mount(mon, ui.Label{ x=240, y=230, width=200, height=12,
    text="mutated via set() -> 60% bad", color="muted", align="left" })

-- [9] hex color override (magenta).
ui.mount(mon, ui.Label{ x=460, y=172, width=200, height=12,
    text="hex 0xFFFF00FF (magenta)", color="muted", align="left" })
ui.mount(mon, ui.Bar{
    x=460, y=188, width=200, height=16,
    value=75, color=0xFFFF00FF,
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
print("'good' bars now orange for 5s")
sleep(5)
ui.setTheme()
ui.render()
print("theme reset; done")

-- Hold the lease so the monitor keeps showing the result until killed.
print("-- holding display. run `kill` from another shell to clear --")
while true do sleep(60) end
