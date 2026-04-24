-- fit_test.lua -- visual check for hug-content sizing.
--
-- Shows three rows on the monitor:
--   1. A Card with a short Label      -> hugs to ~text width (cross stretches)
--   2. A Card with a longer Label     -> same height (pxPerRow+padding*2)
--   3. A flex Card                    -> fills remaining vertical space
--
-- Visually: the first two cards should look identical in height and tightly
-- wrap the single text row. The third should stretch to the bottom of the
-- monitor. If any Card collapses to 0px or stretches in the wrong axis, the
-- hug-content path is broken.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local root = ui.VBox{
    padding = 8,
    gap = 6,
    children = {
        ui.Card{ children = { ui.Label{ text = "HUG-SIZED", color = "good", align = "center" } } },
        ui.Card{ children = { ui.Label{ text = "a slightly longer label inside a Card", color = "hi", align = "center" } } },
        ui.Banner{ text = "flex Card below stretches to fill remainder", style = "info" },
        ui.Card{ flex = 1, children = {
            ui.Label{ text = "flex=1 (fills rest of VBox)", color = "warn", align = "center" }
        }},
    },
}

ui.mount(mon, root)
ui.render()

print("fit_test: cards 1 & 2 should be same tight height, card 3 fills.")
print(string.format("card1 size = %dx%d", root.children[1].width, root.children[1].height))
print(string.format("card2 size = %dx%d", root.children[2].width, root.children[2].height))
print(string.format("card4 size = %dx%d (should be tall)", root.children[4].width, root.children[4].height))

-- Hold the lease so the painted pixels stay on screen while a human inspects.
-- (When the script exits the monitor's peripheral lease releases and the pixel
-- buffer is nulled — the visible frame goes back to a blank slate.)
while true do sleep(5) end
