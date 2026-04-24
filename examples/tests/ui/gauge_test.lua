-- gauge_test.lua -- Gauge widget visual check.
--
-- Three gauges side-by-side, values sweep over time:
--  1. Default 270° speedometer; showValue numeric readout.
--  2. Full-circle 360° with a "TEMP" label in the middle.
--  3. 180° bottom half, bad-color fill.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local g1 = ui.Gauge{ width=220, height=100, value=0,  showValue=true }
local g2 = ui.Gauge{ width=220, height=100, value=40, label="TEMP",
                     startDeg=0, sweepDeg=360, thickness=10, color="info" }
local g3 = ui.Gauge{ width=220, height=100, value=70, showValue=true,
                     startDeg=270, sweepDeg=180, color="bad", thickness=12 }

local root = ui.VBox{ padding=8, gap=6, children={
    ui.Banner{ text="gauge_test -- three dials animating", style="info", height=28 },
    ui.Card{ padding=6, children={
        ui.HBox{ padding=0, gap=8, children={ g1, g2, g3 } }
    }}
}}

ui.mount(mon, root)
print("gauge_test running - watch dials sweep; ctrl-C to exit.")

local t = 0
local timerId = os.startTimer(0.2)
while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timerId then
        t = t + 0.2
        -- Smooth sweeps with different periods so the three gauges don't tick together.
        g1:set{ value = 50 + 49 * math.sin(t * 1.0) }
        g2:set{ value = 50 + 49 * math.sin(t * 0.6 + 1.0) }
        g3:set{ value = 50 + 49 * math.sin(t * 1.4 + 2.0) }
        timerId = os.startTimer(0.2)
    end
    ui.tick(ev)
end
