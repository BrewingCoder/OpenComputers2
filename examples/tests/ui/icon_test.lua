-- icon_test.lua -- Icon widget visual check.
--
-- Five icons in a row showcasing each shape:
--  1. rect     -- plain filled rectangle (info)
--  2. circle   -- filled disc with 20:9 aspect stretch (good)
--  3. diamond  -- scan-line filled rhombus (warn)
--  4. triangle -- scan-line filled isosceles (bad)
--  5. bits     -- custom 5x5 plus-sign bitmap (hi, with border)
--
-- Each icon is wrapped in a VBox with a Label underneath so the
-- shape names are on-screen for identification.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local PLUS = {
    {0,0,1,0,0},
    {0,0,1,0,0},
    {1,1,1,1,1},
    {0,0,1,0,0},
    {0,0,1,0,0},
}

local function slot(label, iconWidget)
    return ui.VBox{ padding=0, gap=4, children={
        iconWidget,
        ui.Label{ text=label, align="center", color="muted" },
    }}
end

local root = ui.VBox{ padding=8, gap=6, children={
    ui.Banner{ text="icon_test -- five shapes: rect, circle, diamond, triangle, bits",
               style="info", height=28 },
    ui.Card{ padding=8, children={
        ui.HBox{ padding=0, gap=10, children={
            slot("RECT",     ui.Icon{ width=140, height=80, shape="rect",     color="info",
                                      bg="bgCard", border="edge" }),
            slot("CIRCLE",   ui.Icon{ width=140, height=80, shape="circle",   color="good",
                                      bg="bgCard", border="edge" }),
            slot("DIAMOND",  ui.Icon{ width=140, height=80, shape="diamond",  color="warn",
                                      bg="bgCard", border="edge" }),
            slot("TRIANGLE", ui.Icon{ width=140, height=80, shape="triangle", color="bad",
                                      bg="bgCard", border="edge" }),
            slot("PLUS",     ui.Icon{ width=140, height=80, shape="bits", bits=PLUS,
                                      color="hi", bg="bgCard", border="edge" }),
        }}
    }}
}}

ui.mount(mon, root)
ui.render(mon)
print("icon_test running - five shapes rendered; ctrl-C to exit.")

while true do
    local ev = { os.pullEvent() }
    ui.tick(ev)
end
