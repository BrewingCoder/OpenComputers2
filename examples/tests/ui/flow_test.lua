-- flow_test.lua -- responsive wrap layout + padding progression.
--
-- Each Card's label shows its own padding value so you can see how
-- padding inflates the border gap around the glyphs. Widths grow with
-- the padding; heights stay on the cell grid.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local styles = { "good", "hi", "warn", "bad", "info" }
local pads = { 0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28 }

local cards = {}
for i, p in ipairs(pads) do
    cards[i] = ui.Card{ padding = p, children = {
        ui.Label{ text = "pad=" .. p, color = styles[((i - 1) % #styles) + 1], align = "center" }
    }}
end

local root = ui.Flow{ padding = 8, gap = 6, children = cards }

ui.mount(mon, root)
ui.render()

print(string.format("flow: %d cards, root %dx%d", #cards, root.width, root.height))
for i, c in ipairs(cards) do
    print(string.format("  [%2d] pad=%-2d  x=%3d y=%3d w=%3d h=%2d",
        i, pads[i], c.x, c.y, c.width, c.height))
end

-- Hold the lease so painted pixels stay on-screen.
while true do sleep(5) end
