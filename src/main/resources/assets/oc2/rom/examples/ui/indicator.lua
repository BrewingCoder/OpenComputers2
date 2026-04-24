-- /rom/examples/ui/indicator.lua -- visual showcase for ui_v1.Indicator.
--
--   run /rom/examples/ui/indicator.lua
--
-- Setup: computer wired to a 2x2 (or larger) monitor group.
--
-- Covers every Indicator prop:
--   [1] banner at top
--   [2] four state LEDs (on/off/warn/bad/info) without labels
--   [3] same five states with labels to the right
--   [4] size sweep (4/8/12/16/24 px) all "on"
--   [5] hex color override + label color override
--   [6] visible=false (blank)
--   [7] set() mutation: off → warn + label added
--   [8] theme swap: "good" goes orange, LEDs shift

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- [1] Banner
ui.mount(mon, ui.Label{
    x=0, y=0, width=480, height=12,
    text="ui_v1 Indicator showcase",
    color="hi", bg="bgCard", align="center",
})

-- [2] Five state LEDs without labels.
ui.mount(mon, ui.Label{ x=8, y=24, width=280, height=12,
    text="states (no label): on / off / warn / bad / info",
    color="muted", align="left" })
local states = {"on","off","warn","bad","info"}
for i, s in ipairs(states) do
    local bx = 8 + (i - 1) * 50
    ui.mount(mon, ui.Indicator{ x=bx, y=40, width=40, height=16, state=s })
end

-- [3] Same states with labels.
ui.mount(mon, ui.Label{ x=8, y=68, width=280, height=12,
    text="states (with labels):", color="muted", align="left" })
for i, s in ipairs(states) do
    local bx = 8 + (i - 1) * 90
    ui.mount(mon, ui.Indicator{
        x=bx, y=84, width=80, height=16,
        state=s, label=string.upper(s),
    })
end

-- [4] Size sweep, all "on". LED horizontal footprint is ~size * 20/9 due to
-- aspect correction, so space accordingly.
ui.mount(mon, ui.Label{ x=8, y=112, width=280, height=12,
    text="size sweep: 4 / 8 / 12 / 16 / 24:", color="muted", align="left" })
local sizes = {4, 8, 12, 16, 24}
local x = 8
for _, sz in ipairs(sizes) do
    local footprint = math.ceil(sz * 20 / 9)
    ui.mount(mon, ui.Indicator{
        x=x, y=130, width=footprint + 4, height=28,
        size=sz, state="on",
    })
    x = x + footprint + 12
end

-- [5] Hex + label-color override.
ui.mount(mon, ui.Label{ x=8, y=172, width=280, height=12,
    text="hex 0xFFFF00FF + labelColor=hi:", color="muted", align="left" })
ui.mount(mon, ui.Indicator{
    x=8, y=188, width=200, height=16,
    state="off", color=0xFFFF00FF, label="CUSTOM", labelColor="hi",
})

-- [6] visible=false.
ui.mount(mon, ui.Label{ x=8, y=212, width=280, height=12,
    text="below: visible=false (blank)", color="muted", align="left" })
ui.mount(mon, ui.Indicator{
    x=8, y=228, width=200, height=16,
    state="bad", label="HIDDEN", visible=false,
})

-- [7] set() mutation.
local mut = ui.Indicator{ x=8, y=252, width=200, height=16, state="off" }
mut:set{ state="warn", label="MUTATED" }
ui.mount(mon, mut)
ui.mount(mon, ui.Label{ x=8, y=270, width=280, height=12,
    text="mutated via set() -> warn + label", color="muted", align="left" })

-- [8] Grid of 9 LEDs demonstrating a fuel bank view.
ui.mount(mon, ui.Label{ x=300, y=112, width=180, height=12,
    text="fuel bank (9 cells):", color="muted", align="left" })
local fuel = {
    "on","on","warn",
    "on","bad","on",
    "off","on","on",
}
for i, s in ipairs(fuel) do
    local col = (i - 1) % 3
    local row = math.floor((i - 1) / 3)
    ui.mount(mon, ui.Indicator{
        x=300 + col * 60, y=130 + row * 24, width=48, height=20,
        size=12, state=s,
    })
end

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
print("'on' LEDs now orange for 5s")
sleep(5)
ui.setTheme()
ui.render()
print("theme reset; done")

print("-- holding display. run `kill` from another shell to clear --")
while true do sleep(60) end
