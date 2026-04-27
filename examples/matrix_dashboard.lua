-- matrix_dashboard.lua — Mekanism Induction Matrix readout via Bridge adapter.
--
-- Place a Bridge part on an Adapter face touching the Induction Port, and a
-- Monitor on the same wifi channel as the Computer. Then `run matrix_dashboard.lua`.
--
-- Layout (sized to monitor pxW): one full-width column of three cards.
--   1. STATUS card  — circular gauge (left) + numeric labels (right, flex).
--   2. THROUGHPUT   — IN bar over OUT bar with rate labels at the right.
--   3. HISTORY      — sparkline of energy fill % over time.

local ui = require("ui_v1")

local mon = peripheral.find("monitor")
assert(mon, "no monitor on this channel")

local matrix = peripheral.find("bridge")
assert(matrix, "no bridge peripheral found — did the part install?")
if matrix.protocol ~= "mekanism-matrix" then
    error("bridge target is " .. tostring(matrix.protocol)
          .. " (target=" .. tostring(matrix.target) .. ")"
          .. " — expected mekanism-matrix. Move the bridge onto an Induction Port face.")
end

-- Format FE into a compact human label. The bridge adapter converts from
-- Mekanism's internal Joules to FE at the boundary, matching the unit
-- Mekanism's GUI shows by default. Ultimate-tier matrices reach 360 PFE
-- of storage cap (= 900 PJ raw) — comfortably scales into PFE.
local function fmtFE(v)
    if v == nil then return "n/a" end
    v = tonumber(v) or 0
    local abs = math.abs(v)
    if abs < 1e3  then return string.format("%.0f FE",  v) end
    if abs < 1e6  then return string.format("%.2f kFE", v / 1e3) end
    if abs < 1e9  then return string.format("%.2f MFE", v / 1e6) end
    if abs < 1e12 then return string.format("%.2f GFE", v / 1e9) end
    if abs < 1e15 then return string.format("%.2f TFE", v / 1e12) end
    return string.format("%.2f PFE", v / 1e15)
end
local function fmtRate(v) return fmtFE(v) .. "/t" end

mon:clear()
mon:clearPixels(0xFF0A0F1A)

-- Pixel size drives every container width so the layout adapts to whatever
-- monitor wall the user built. innerW = pxW minus the root VBox padding.
local pxW, pxH = mon:getPixelSize()
local innerW = pxW - 8
-- Card padding is 6 each side, so inner (HBox/VBox cross-axis) is innerW - 12.
-- ui_v1 hugs cross-axis for non-flex children; we hand row HBoxes an explicit
-- width so they stretch to the card's full inner span instead of collapsing
-- around their measured content. (Only HBoxes with `flex` set already stretch
-- via the framework's flex>0 cross-axis branch.)
local cardInnerW = innerW - 12

-- ---------- widgets ----------
local banner = ui.Banner{ text = "Induction Matrix", style = "info",
                          height = 22, width = innerW }

-- Status card: gauge on the left, two label columns to its right. Gauge is
-- sized so it doesn't dwarf the rest — 96px square is enough to read FILL
-- and the % readout while leaving the throughput and history cards their
-- share of the monitor.
local gaugeSize = math.min(96, math.floor(innerW * 0.16))
local fillGauge = ui.Gauge{
    width = gaugeSize, height = gaugeSize,
    value = 0, label = "FILL", showValue = true,
    color = "good", thickness = 12,
}

local LROW = 12  -- exactly one cell row tall — keeps consecutive labels from skipping a cell
local energyLabel    = ui.Label{ text = "stored:    --", color = "fg",    height = LROW }
local capacityLabel  = ui.Label{ text = "capacity:  --", color = "muted", height = LROW }
local pctLabel       = ui.Label{ text = "fill:      --", color = "muted", height = LROW }
local cellsLabel     = ui.Label{ text = "cells:     --", color = "fg",    height = LROW }
local providersLabel = ui.Label{ text = "providers: --", color = "muted", height = LROW }
local transferLabel  = ui.Label{ text = "xfer cap:  --", color = "muted", height = LROW }
local formedLabel    = ui.Label{ text = "formed:    --", color = "muted", height = LROW }

local statusCard = ui.Card{ padding = 6, width = innerW, children = {
    ui.HBox{ padding = 0, gap = 12, width = cardInnerW, children = {
        fillGauge,
        ui.VBox{ padding = 0, gap = 2, flex = 1, children = {
            energyLabel,
            capacityLabel,
            pctLabel,
            transferLabel,
        }},
        ui.VBox{ padding = 0, gap = 2, flex = 1, children = {
            cellsLabel,
            providersLabel,
            formedLabel,
        }},
    }},
}}

-- Throughput card: per-direction CAP bar + sparkline + NET row.
--   CAP bar  — fills against getTransferCap(). True utilization. For an
--              Ultimate matrix this sits near 0% — that's the truth, you
--              have orders of magnitude of headroom.
--   Sparkline— autoscaled rate history; reveals the *shape* of recent flow
--              even when the CAP bar shows near-zero. The peak label on
--              the right tells you what 100% of the sparkline means.
--   Right-side rate label combines absolute (FE/t) + cap-% so the actual
--              numbers are always visible regardless of bar fill.
local CAP_H    = 14
local SPARK_H  = 32
local NET_H    = 14
local LABEL_W  = 40
-- Right-side label width — sized for the worst case "788.00 MFE/t  100.000%"
-- (~22 chars * 12px/cell ≈ 264px) plus a few pixels of right-edge slack so
-- the right-aligned text never bleeds left into the sparkline. Label:draw
-- doesn't clip — text wider than `width` walks off the left edge. (At
-- Ultimate-matrix scale the transfer cap is 1.97 GJ/t = 788 MFE/t.)
local RATE_W   = 280
local SPARK_CAP = 240         -- 240 samples @ 1Hz = 4 minutes of history

local inLabel    = ui.Label{ text = "IN",  color = "info", width = LABEL_W, height = CAP_H }
local inCapBar   = ui.Bar{ flex = 1, height = CAP_H, value = 0, min = 0, max = 1, color = "info" }
local inRate     = ui.Label{ text = "0 FE/t", color = "info", width = RATE_W, height = CAP_H, align = "right" }

local inSpacer   = ui.Label{ text = "", width = LABEL_W, height = SPARK_H }
local inSpark    = ui.Sparkline{ flex = 1, height = SPARK_H, color = "info",
                                  fill = true, capacity = SPARK_CAP }
local inPeak     = ui.Label{ text = "peak: 0", color = "muted",
                              width = RATE_W, height = SPARK_H, align = "right" }

local outLabel   = ui.Label{ text = "OUT", color = "warn", width = LABEL_W, height = CAP_H }
local outCapBar  = ui.Bar{ flex = 1, height = CAP_H, value = 0, min = 0, max = 1, color = "warn" }
local outRate    = ui.Label{ text = "0 FE/t", color = "warn", width = RATE_W, height = CAP_H, align = "right" }

local outSpacer  = ui.Label{ text = "", width = LABEL_W, height = SPARK_H }
local outSpark   = ui.Sparkline{ flex = 1, height = SPARK_H, color = "warn",
                                  fill = true, capacity = SPARK_CAP }
local outPeak    = ui.Label{ text = "peak: 0", color = "muted",
                              width = RATE_W, height = SPARK_H, align = "right" }

-- NET row — green when IN > OUT (charging), red when OUT > IN (draining),
-- muted when balanced. Indicator on the left is a glanceable color dot;
-- the label spelling out "NET" lives next to it because the indicator's
-- width is too tight for an inline label string. netRate is right-aligned
-- to mirror the IN/OUT rate placement on the rows above.
local netIndicator = ui.Indicator{ width = 16, height = NET_H, state = "info", size = 8 }
local netLabel     = ui.Label{ text = "NET", color = "muted", width = 36, height = NET_H }
local netRate      = ui.Label{ text = "0 FE/t", color = "muted",
                                flex = 1, height = NET_H, align = "right" }

-- 5 rows: IN cap, IN spark, OUT cap, OUT spark, NET.
-- Height = 2*CAP_H + 2*SPARK_H + NET_H + 5 gaps (4*5) + Card padding (12).
local throughputCard = ui.Card{ padding = 6, width = innerW,
                                height = 2 * CAP_H + 2 * SPARK_H + NET_H + 4 * 5 + 12, children = {
    ui.VBox{ padding = 0, gap = 4, flex = 1, children = {
        ui.HBox{ padding = 0, gap = 6, height = CAP_H,   width = cardInnerW, children = { inLabel,   inCapBar,  inRate  }},
        ui.HBox{ padding = 0, gap = 6, height = SPARK_H, width = cardInnerW, children = { inSpacer,  inSpark,   inPeak  }},
        ui.HBox{ padding = 0, gap = 6, height = CAP_H,   width = cardInnerW, children = { outLabel,  outCapBar, outRate }},
        ui.HBox{ padding = 0, gap = 6, height = SPARK_H, width = cardInnerW, children = { outSpacer, outSpark,  outPeak }},
        ui.HBox{ padding = 0, gap = 6, height = NET_H,   width = cardInnerW, children = { netIndicator, netLabel, netRate }},
    }},
}}

-- History card: heading row (label + current %) and a flex sparkline so the
-- card claims the remaining vertical budget after the two cards above.
local historyTitle = ui.Label{ text = "energy fill % (history)", color = "muted",
                                flex = 1, height = 12 }
local historyValue = ui.Label{ text = "0.00%", color = "good",
                                width = 80, height = 12, align = "right" }
local energyHist   = ui.Sparkline{ flex = 1, color = "good", min = 0, max = 100,
                                    fill = true, capacity = 240 }

local historyCard = ui.Card{ padding = 6, width = innerW, flex = 1, children = {
    ui.VBox{ padding = 0, gap = 4, flex = 1, children = {
        ui.HBox{ padding = 0, gap = 6, height = 12, width = cardInnerW, children = {
            historyTitle, historyValue,
        }},
        energyHist,
    }},
}}

local root = ui.VBox{ padding = 4, gap = 4, children = {
    banner,
    statusCard,
    throughputCard,
    historyCard,
}}

ui.mount(mon, root)
print("matrix dashboard running — ctrl-C to exit")
print(string.format("monitor pxW=%d pxH=%d innerW=%d gauge=%d", pxW, pxH, innerW, gaugeSize))

local function pull(name) return matrix:call(name) end

-- Peak helper for the rate sparklines — Sparkline auto-scales its Y axis
-- internally for rendering, but we still need the max value to display the
-- "peak: N" annotation. Direct read of the live sample buffer is fine; the
-- widget appends in place via :push.
local function maxOf(t)
    local m = 0
    for i = 1, #t do
        local v = t[i]
        if v > m then m = v end
    end
    return m
end

local timerId = os.startTimer(1.0)
while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timerId then
        local stored    = pull("getEnergy")
        local maxE      = pull("getMaxEnergy")
        local pct       = tonumber(pull("getEnergyFilledPercentage")) or 0  -- 0..1
        local lastIn    = tonumber(pull("getLastInput"))   or 0
        local lastOut   = tonumber(pull("getLastOutput"))  or 0
        local cap       = tonumber(pull("getTransferCap")) or 1
        local cells     = pull("getInstalledCells")     or 0
        local providers = pull("getInstalledProviders") or 0
        local formed    = pull("isFormed")

        local pctNum = pct * 100

        -- Gauge color tracks fill: good(>=70) / warn(>=30) / bad(<30).
        local gaugeColor = (pctNum >= 70 and "good") or
                           (pctNum >= 30 and "warn") or "bad"
        fillGauge:set{ value = pctNum, color = gaugeColor }

        energyLabel   :set{ text = "stored:    " .. fmtFE(stored) }
        capacityLabel :set{ text = "capacity:  " .. fmtFE(maxE) }
        pctLabel      :set{ text = string.format("fill:      %.2f%%", pctNum) }
        cellsLabel    :set{ text = "cells:     " .. tostring(cells) }
        providersLabel:set{ text = "providers: " .. tostring(providers) }
        transferLabel :set{ text = "xfer cap:  " .. fmtRate(cap) }
        formedLabel   :set{ text = "formed:    " .. tostring(formed) }

        -- CAP bars: true % of matrix transfer capacity. Sub-1% is normal
        -- and *correct* on Ultimate-tier matrices — the bars are honest about
        -- headroom; the sparklines below carry the relative-flow story.
        local capDenom = (cap > 0) and cap or 1
        inCapBar :set{ value = lastIn  / capDenom }
        outCapBar:set{ value = lastOut / capDenom }
        inRate :set{ text = string.format("%s  %.3f%%", fmtRate(lastIn),  lastIn  / capDenom * 100) }
        outRate:set{ text = string.format("%s  %.3f%%", fmtRate(lastOut), lastOut / capDenom * 100) }

        inSpark :push(lastIn)
        outSpark:push(lastOut)
        inPeak :set{ text = "peak: " .. fmtRate(maxOf(inSpark.values)) }
        outPeak:set{ text = "peak: " .. fmtRate(maxOf(outSpark.values)) }

        -- Net flow: positive = charging, negative = draining.
        local net = lastIn - lastOut
        local netState, netColor, netSign
        if net > 0 then
            netState, netColor, netSign = "good",  "good",  "+"
        elseif net < 0 then
            netState, netColor, netSign = "bad",   "bad",   "-"
        else
            netState, netColor, netSign = "info",  "muted", " "
        end
        netIndicator:set{ state = netState }
        netLabel:set{ color = netColor }
        netRate:set{ text = string.format("%s%s", netSign, fmtRate(math.abs(net))),
                     color = netColor }

        historyValue:set{ text = string.format("%.2f%%", pctNum) }
        energyHist:push(pctNum)

        timerId = os.startTimer(1.0)
    end
    ui.tick(ev)
end
