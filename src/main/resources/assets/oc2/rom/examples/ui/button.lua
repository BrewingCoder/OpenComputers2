-- /rom/examples/ui/button.lua -- visual + interactive showcase for ui_v1.Button.
--
--   run /rom/examples/ui/button.lua
--
-- Setup: computer wired to a 2x2 (or larger) monitor group.
--
-- Covers every Button prop:
--   [1] three styles: primary / ghost / danger
--   [2] enabled vs disabled (dimmed, no click)
--   [3] color override + custom borderColor
--   [4] borderThickness variations (0, 1, 2, 4)
--   [5] empty label (chrome only)
--   [6] visible=false (blank)
--   [7] a LIVE counter + ON/OFF toggle driven by onClick
--
-- Right-click any button to fire its onClick. The counter updates in place
-- via :set{...} and the ON/OFF button mutates its own style each press.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- [1] Style sweep (static, for visual reference).
ui.mount(mon, ui.Label{ x=8, y=8, width=200, height=12,
    text="styles:", color="muted", align="left" })
local styles = { "primary", "ghost", "danger" }
for i, s in ipairs(styles) do
    ui.mount(mon, ui.Button{
        x=8, y=24 + (i - 1) * 24, width=180, height=20,
        style=s, label=string.upper(s),
    })
end

-- [2] enabled vs disabled.
ui.mount(mon, ui.Label{ x=200, y=8, width=200, height=12,
    text="enabled vs disabled:", color="muted", align="left" })
ui.mount(mon, ui.Button{ x=200, y=24, width=180, height=20,
    style="primary", label="ENABLED" })
ui.mount(mon, ui.Button{ x=200, y=48, width=180, height=20,
    style="primary", label="DISABLED", enabled=false })

-- [3] color override + custom borderColor.
ui.mount(mon, ui.Label{ x=200, y=76, width=200, height=12,
    text="color + borderColor overrides:", color="muted", align="left" })
ui.mount(mon, ui.Button{ x=200, y=92, width=180, height=20,
    label="MAGENTA", color=0xFF8E44AD, borderColor=0xFFFFFFFF })

-- [4] borderThickness sweep.
ui.mount(mon, ui.Label{ x=8, y=104, width=200, height=12,
    text="borderThickness: 0 / 1 / 2 / 4:", color="muted", align="left" })
local ths = { 0, 1, 2, 4 }
for i, t in ipairs(ths) do
    ui.mount(mon, ui.Button{
        x=8, y=120 + (i - 1) * 24, width=180, height=20,
        style="ghost", label="t="..t, borderThickness=t,
    })
end

-- [5] empty label (chrome only).
ui.mount(mon, ui.Label{ x=200, y=124, width=200, height=12,
    text="empty label (chrome only):", color="muted", align="left" })
ui.mount(mon, ui.Button{ x=200, y=140, width=180, height=20,
    style="primary", label="" })

-- [6] visible=false (blank slot -- label stays).
ui.mount(mon, ui.Label{ x=200, y=168, width=200, height=12,
    text="below: visible=false (blank)", color="muted", align="left" })
ui.mount(mon, ui.Button{ x=200, y=184, width=180, height=20,
    style="danger", label="HIDDEN", visible=false })

-- [7] LIVE counter + ON/OFF toggle.
ui.mount(mon, ui.Label{ x=8, y=224, width=200, height=12,
    text="INTERACTIVE: click below ->", color="muted", align="left" })

local count = 0
local counterLabel = ui.Label{ x=8, y=240, width=180, height=20,
    text="clicks: 0", color="hi", align="center" }
ui.mount(mon, counterLabel)

ui.mount(mon, ui.Button{
    x=8, y=264, width=180, height=20,
    style="primary", label="CLICK ME",
    onClick=function(e)
        count = count + 1
        counterLabel:set{ text = "clicks: " .. count }
    end,
})

local power = true
local powerBtn
powerBtn = ui.Button{
    x=200, y=264, width=180, height=20,
    style="primary", label="POWER: ON",
    onClick=function(e)
        power = not power
        powerBtn:set{
            style = power and "primary" or "ghost",
            label = power and "POWER: ON" or "POWER: OFF",
        }
    end,
}
ui.mount(mon, powerBtn)

print("showcase mounted. right-click monitor to test interactions.")
print("kill the script from another shell to clear.")
ui.run()
