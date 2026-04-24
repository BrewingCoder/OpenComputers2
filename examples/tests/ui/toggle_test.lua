-- toggle_test.lua -- Toggle widget visual + interaction check.
--
-- Dashboard with five toggles:
--  1. POWER    (label prefix) -- click to flip; banner color follows
--  2. PUMP     (bare on/off)  -- click to flip
--  3. VALVE    (custom labels OPEN/CLOSED, custom colors)
--  4. E-STOP   (bad when off, good when on)
--  5. LOCKED   (enabled=false, pre-latched true) -- visually dim, no click

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local toggles    -- forward declare; onChange closure captures this upvalue
local status = ui.Banner{ text="status: IDLE", style="info", height=28 }

local function refreshStatus()
    local power = toggles[1].value and "RUNNING" or "IDLE"
    local pump  = toggles[2].value and "flowing" or "stopped"
    local valve = toggles[3].value and "OPEN" or "CLOSED"
    status:set{
        text = string.format("status: %s | pump %s | valve %s", power, pump, valve),
        style = toggles[1].value and "good" or "info",
    }
end

local function onAnyChange(v, e)
    refreshStatus()
    local who = e.widget.label or e.widget.onLabel or "?"
    print(string.format("[toggle] %s -> %s", who, tostring(v)))
end

toggles = {
    ui.Toggle{ label="POWER",  height=28, onChange=onAnyChange },
    ui.Toggle{ label="PUMP",   height=28, onChange=onAnyChange },
    ui.Toggle{ label="VALVE",  height=28,
               onLabel="OPEN", offLabel="CLOSED",
               onColor="info", offColor="warn",
               onChange=onAnyChange },
    ui.Toggle{ label="E-STOP", height=28,
               onColor="good", offColor="bad",
               onChange=onAnyChange },
    ui.Toggle{ label="LOCKED", height=28, value=true, enabled=false,
               onChange=onAnyChange },
}

local root = ui.VBox{ padding=8, gap=6, children={
    status,
    ui.Card{ padding=6, children={
        ui.VBox{ padding=0, gap=6, children=toggles }
    }}
}}

ui.mount(mon, root)
refreshStatus()
print("toggle_test running - tap each toggle to flip. LOCKED should ignore taps.")
ui.run()
