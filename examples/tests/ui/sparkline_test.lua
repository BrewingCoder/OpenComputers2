-- sparkline_test.lua -- Sparkline widget visual check.
--
-- Three sparklines stacked in a card, each showing a different look:
--  1. Auto-scaled sine wave, line only (default look).
--  2. Fixed range 0..100, filled area, showLast readout.
--  3. Baseline at 50 with fill, warn color, narrower capacity so recent
--     history scrolls visibly.

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local s1 = ui.Sparkline{ width=880, height=70, capacity=120,
                          color="info" }
local s2 = ui.Sparkline{ width=880, height=70, capacity=120,
                          min=0, max=100, color="good",
                          fill=true, showLast=true }
local s3 = ui.Sparkline{ width=880, height=70, capacity=60,
                          min=0, max=100, color="warn",
                          baseline=50, fill=true, showLast=true }

local root = ui.VBox{ padding=8, gap=6, children={
    ui.Banner{ text="sparkline_test -- three time series streaming",
               style="info", height=28 },
    ui.Card{ padding=6, children={
        ui.VBox{ padding=0, gap=6, children={ s1, s2, s3 } }
    }}
}}

ui.mount(mon, root)
print("sparkline_test running - watch traces scroll; ctrl-C to exit.")

local t = 0
local timerId = os.startTimer(0.15)
while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timerId then
        t = t + 0.15
        s1:push(math.sin(t * 0.8) * 20 + math.sin(t * 2.3) * 6)
        s2:push(50 + 45 * math.sin(t * 0.5))
        s3:push(50 + 30 * math.sin(t * 1.2) + (math.random() - 0.5) * 15)
        ui.invalidate()
        timerId = os.startTimer(0.15)
    end
    ui.tick(ev)
end
