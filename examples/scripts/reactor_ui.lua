-- reactor_ui.lua -- reactor dashboard built from the ui_v1 widget library.
--
-- Same reactor / PID control logic as reactor.lua, but the screen is
-- composed from ui_v1 widgets (Card/VBox/HBox/Gauge/Sparkline/Bar/...)
-- instead of hand-rolled pixel draws. Auto-discovers the first bridge
-- peripheral whose protocol + method set look like a ZeroCore reactor;
-- if nothing matches, shows an error banner and keeps polling so it
-- recovers without a restart.
--
-- Layout (on an 80x27 monitor = 960x324 px):
--   row 0         Banner -- ACTIVE/STOPPED/MANUAL + mode text
--   rows 1..17    two columns:
--                   left  - Energy / Fuel / Rods bars (three Cards)
--                   right - PID error Gauge + term Indicator + rod Sparkline
--   rows 18..22   PID tuning card (Kp/Ki/Kd buttons + value labels,
--                 target +/- and threshold +/-)
--   rows 23..26   control row (Toggle power, Auto button, Reset I)

local ui = require("ui_v1")

-- ------- config (same defaults as reactor.lua) -------
local CONFIG = {
    lowerHardPct = 20,
    upperHardPct = 80,
    pidTarget    = 75,
    pidKp        = 2.0,
    pidKi        = 0.10,
    pidKd        = 5.0,
    pidIClamp    = 100,
    tickSec      = 1.0,
    sparkCapacity = 120,
}

-- ------- runtime state -------
local state = {
    reactor = nil,
    active = false, energyStored = 0, energyCapacity = 1, energyPct = 0,
    fuelAmount = 0, fuelCapacity = 1, fuelTemperature = 0, fuelConsumedLastTick = 0,
    wasteAmount = 0,
    rodCount = 0, rodLevel = 0,
    pid = { integral = 0, lastError = 0, output = 50, pTerm = 0, iTerm = 0, dTerm = 0 },
    manualOverride = nil,   -- nil = PID auto; true = forced on; false = forced off
    modeText = "INIT",
    discoveryError = nil,
}

local function clamp(v, lo, hi)
    if v < lo then return lo elseif v > hi then return hi else return v end
end

-- ---------- discovery ----------
-- Walks peripheral.list() looking for a bridge whose described method set
-- looks like a ZeroCore REACTOR (not a turbine, not an energizer). The
-- discriminator is the presence of reactor-only methods in describe().
local REACTOR_DISCRIMINATORS = {
    "getFuelAmount", "getFuelTemperature", "getNumberOfControlRods",
    "getCasingTemperature", "getControlRodsLevels",
}

local function bridgeLooksLikeReactor(b)
    if b.protocol ~= "zerocore" then return false end
    local ok, desc = pcall(function() return b:describe() end)
    if not ok or type(desc) ~= "table" then return false end
    local methods = desc.methods
    if type(methods) ~= "table" then return false end
    local lookup = {}
    for _, m in ipairs(methods) do
        if type(m) == "table" and m.name then lookup[m.name] = true
        elseif type(m) == "string" then lookup[m] = true end
    end
    for _, need in ipairs(REACTOR_DISCRIMINATORS) do
        if not lookup[need] then return false end
    end
    return true
end

local function discover()
    local bridges = peripheral.list("bridge") or {}
    for _, b in ipairs(bridges) do
        local ok, looks = pcall(bridgeLooksLikeReactor, b)
        if ok and looks then
            state.reactor = b
            local okRods, rc = pcall(function() return b:call("getNumberOfControlRods") end)
            state.rodCount = (okRods and rc) or 0
            state.discoveryError = nil
            return true
        end
    end
    state.reactor = nil
    state.discoveryError = "no zerocore reactor on this channel"
    return false
end

-- ---------- poll ----------
local function readState()
    local r = state.reactor
    if not r then return end
    local ok, err = pcall(function()
        state.active = r:call("getActive") or false
        local es = r:call("getEnergyStats") or {}
        state.energyStored   = es.energyStored or 0
        state.energyCapacity = (es.energyCapacity and es.energyCapacity > 0) and es.energyCapacity or 1
        state.energyPct      = state.energyStored / state.energyCapacity * 100
        local fs = r:call("getFuelStats") or {}
        state.fuelAmount           = fs.fuelAmount or 0
        state.fuelCapacity         = (fs.fuelCapacity and fs.fuelCapacity > 0) and fs.fuelCapacity or 1
        state.fuelTemperature      = fs.fuelTemperature or 0
        state.fuelConsumedLastTick = fs.fuelConsumedLastTick or 0
        state.wasteAmount          = fs.wasteAmount or 0
        local rods = r:call("getControlRodsLevels") or {}
        local sum, n = 0, 0
        for _, v in pairs(rods) do sum = sum + (tonumber(v) or 0); n = n + 1 end
        state.rodLevel = (n > 0) and (sum / n) or 0
    end)
    if not ok then
        -- Reactor likely unloaded or bridge broken. Back to discovery.
        state.reactor = nil
        state.discoveryError = "reactor lost: " .. tostring(err)
    end
end

-- ---------- PID + control ----------
local function tickControl()
    local r = state.reactor
    if not r then return end
    -- Manual override wins.
    if state.manualOverride ~= nil then
        local ok = pcall(function()
            if state.active ~= state.manualOverride then r:call("setActive", state.manualOverride) end
            if state.manualOverride then r:call("setAllControlRodLevels", 0) end
        end)
        if not ok then return end
        state.modeText = state.manualOverride and "MANUAL ON (rods 0%)" or "MANUAL OFF"
        return
    end
    -- Hard thresholds before PID.
    if state.energyPct >= CONFIG.upperHardPct and state.active then
        pcall(function() r:call("setActive", false) end)
        state.modeText = string.format("HALT  E>=%d%%", CONFIG.upperHardPct)
        return
    end
    if state.energyPct <= CONFIG.lowerHardPct and not state.active then
        pcall(function()
            r:call("setActive", true)
            r:call("setAllControlRodLevels", 0)
        end)
        state.pid.integral = 0; state.pid.lastError = 0; state.pid.output = 0
        state.modeText = string.format("START E<=%d%%", CONFIG.lowerHardPct)
        return
    end
    if not state.active then
        state.modeText = "IDLE (waiting for low threshold)"
        return
    end
    local err = state.energyPct - CONFIG.pidTarget
    state.pid.integral = clamp(state.pid.integral + err, -CONFIG.pidIClamp, CONFIG.pidIClamp)
    local deriv = err - state.pid.lastError
    state.pid.lastError = err
    state.pid.pTerm = CONFIG.pidKp * err
    state.pid.iTerm = CONFIG.pidKi * state.pid.integral
    state.pid.dTerm = CONFIG.pidKd * deriv
    local out = clamp(state.pid.pTerm + state.pid.iTerm + state.pid.dTerm + 50, 0, 100)
    state.pid.output = out
    pcall(function() r:call("setAllControlRodLevels", math.floor(out + 0.5)) end)
    state.modeText = string.format("PID rod=%d%% err=%+.1f", math.floor(out + 0.5), err)
end

-- ---------- widget tree ----------
local W = {}  -- handles we mutate each tick
local refresh    -- forward declared; buildTree closures reference this

local function fmtFE(n)
    n = tonumber(n) or 0
    if n >= 1e9 then return string.format("%.2fGFE", n / 1e9) end
    if n >= 1e6 then return string.format("%.2fMFE", n / 1e6) end
    if n >= 1e3 then return string.format("%.2fkFE", n / 1e3) end
    return string.format("%dFE", math.floor(n))
end

local function pidDominantTerm()
    local a = math.abs(state.pid.pTerm)
    local b = math.abs(state.pid.iTerm)
    local c = math.abs(state.pid.dTerm)
    if a >= b and a >= c then return "P", "info"
    elseif b >= c then return "I", "warn"
    else return "D", "good" end
end

-- Children of a VBox only auto-stretch to the parent's inner width if their
-- own measure returns 0. Cards and nested Boxes with measurable content
-- return >0, so they'd hug their content and leave the monitor mostly blank.
-- buildTree takes the monitor pixel width and stamps explicit widths on the
-- row-level containers so each spans the full screen.
local function buildTree(pxW)
    local innerW = pxW - 8                  -- minus root padding (4 on each side)

    W.banner = ui.Banner{ text = "REACTOR UI  --  booting...", style = "info",
                          height = 20, width = innerW }

    -- LEFT column: Energy / Fuel / Rods Cards, each with a Bar + inline Labels.
    W.energyBar = ui.Bar{ value = 0, height = 14, color = "good", showPct = true, marker = CONFIG.pidTarget }
    W.energyLabel = ui.Label{ text = "ENERGY  0.0%  (0FE)", color = "fg", height = 12 }
    local energyCard = ui.Card{ padding = 4, children = {
        ui.VBox{ padding = 0, gap = 2, children = { W.energyLabel, W.energyBar } },
    } }

    W.fuelBar = ui.Bar{ value = 0, height = 12, color = "warn", showPct = true }
    W.fuelLabel = ui.Label{ text = "FUEL  0.0%  burn 0 mB/t  waste 0", color = "fg", height = 12 }
    local fuelCard = ui.Card{ padding = 4, children = {
        ui.VBox{ padding = 0, gap = 2, children = { W.fuelLabel, W.fuelBar } },
    } }

    W.rodsBar = ui.Bar{ value = 0, height = 12, color = "info", showPct = true }
    W.rodsLabel = ui.Label{ text = "RODS  0.0%  (0 installed)", color = "fg", height = 12 }
    local rodsCard = ui.Card{ padding = 4, children = {
        ui.VBox{ padding = 0, gap = 2, children = { W.rodsLabel, W.rodsBar } },
    } }

    local leftCol = ui.VBox{ padding = 0, gap = 4, flex = 1, children = {
        energyCard, fuelCard, rodsCard,
    } }

    -- RIGHT column: Gauge (PID error) + Indicator (dominant term) + Sparkline (rod history)
    W.errorGauge = ui.Gauge{
        height = 70, value = 50, min = 0, max = 100,
        startDeg = 225, sweepDeg = 270, thickness = 8,
        color = "info", label = "ERR",
    }
    W.termIndicator = ui.Indicator{ height = 14, state = "info", label = "term: P (0.00)", size = 8 }
    W.rodsSpark = ui.Sparkline{ height = 40, capacity = CONFIG.sparkCapacity, min = 0, max = 100,
                                color = "info", baseline = 50, baselineColor = "muted",
                                fill = true, showLast = true }
    local rightCol = ui.Card{ padding = 4, flex = 1, children = {
        ui.VBox{ padding = 0, gap = 4, children = {
            ui.Label{ text = "PID TELEMETRY", color = "muted", height = 12 },
            W.errorGauge,
            W.termIndicator,
            ui.Label{ text = "rod history (%)", color = "muted", height = 12 },
            W.rodsSpark,
        } },
    } }

    local body = ui.HBox{ padding = 0, gap = 4, flex = 1, width = innerW,
                          children = { leftCol, rightCol } }

    -- PID tuning row: Label of current gains + 6 buttons (Kp/Ki/Kd +/-) + target +/- + threshold +/-
    W.gainsLabel = ui.Label{ text = "Kp=2.0  Ki=0.10  Kd=5.0  tgt=75  band=20-80",
                              color = "fg", align = "center", height = 12 }
    -- flex=1 so the 12 buttons split the row evenly -- buttons have no measure()
    -- so without flex they'd claim width=0 and not render.
    local function stepBtn(label, fn)
        return ui.Button{ label = label, height = 20, style = "primary", flex = 1,
                          onClick = function() fn(); refresh() end }
    end
    local tuningRow = ui.HBox{ padding = 0, gap = 2, height = 20, children = {
        stepBtn("Kp-", function() CONFIG.pidKp = math.max(0, CONFIG.pidKp - 0.5) end),
        stepBtn("Kp+", function() CONFIG.pidKp = CONFIG.pidKp + 0.5 end),
        stepBtn("Ki-", function() CONFIG.pidKi = math.max(0, CONFIG.pidKi - 0.05) end),
        stepBtn("Ki+", function() CONFIG.pidKi = CONFIG.pidKi + 0.05 end),
        stepBtn("Kd-", function() CONFIG.pidKd = math.max(0, CONFIG.pidKd - 1.0) end),
        stepBtn("Kd+", function() CONFIG.pidKd = CONFIG.pidKd + 1.0 end),
        stepBtn("tgt-", function() CONFIG.pidTarget = clamp(CONFIG.pidTarget - 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("tgt+", function() CONFIG.pidTarget = clamp(CONFIG.pidTarget + 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("lo-", function() CONFIG.lowerHardPct = math.max(0, CONFIG.lowerHardPct - 5) end),
        stepBtn("lo+", function() CONFIG.lowerHardPct = math.min(CONFIG.pidTarget - 5, CONFIG.lowerHardPct + 5) end),
        stepBtn("hi-", function() CONFIG.upperHardPct = math.max(CONFIG.pidTarget + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("hi+", function() CONFIG.upperHardPct = math.min(100, CONFIG.upperHardPct + 5) end),
    } }
    local tuningCard = ui.Card{ padding = 4, width = innerW, children = {
        ui.VBox{ padding = 0, gap = 4, children = { W.gainsLabel, tuningRow } },
    } }

    -- Control row: Power Toggle + Auto button + Reset I button.
    W.powerToggle = ui.Toggle{
        label = "POWER", value = false, height = 20, flex = 1,
        onLabel = "ON", offLabel = "OFF",
        onColor = "good", offColor = "danger",
        onChange = function(v)
            state.manualOverride = v
        end,
    }
    local autoBtn = ui.Button{ label = "AUTO (PID)", height = 20, style = "primary", flex = 1,
        onClick = function()
            state.manualOverride = nil
            W.powerToggle:set{ value = state.active }
        end }
    local resetBtn = ui.Button{ label = "RESET I", height = 20, style = "ghost", flex = 1,
        onClick = function()
            state.pid.integral = 0; state.pid.lastError = 0
        end }
    local controlRow = ui.HBox{ padding = 0, gap = 4, height = 22, width = innerW, children = {
        W.powerToggle, autoBtn, resetBtn,
    } }

    local root = ui.VBox{ padding = 4, gap = 4, children = {
        W.banner,
        body,
        tuningCard,
        controlRow,
    } }
    return root
end

-- ---------- render bridge: push state -> widgets ----------
refresh = function ()
    if state.discoveryError then
        W.banner:set{ text = "ERROR: " .. state.discoveryError .. " -- retrying", style = "bad" }
    else
        local style, tag
        if state.manualOverride ~= nil then
            style, tag = "warn", (state.manualOverride and "MANUAL ON" or "MANUAL OFF")
        elseif state.active then
            style, tag = "good", "ACTIVE"
        else
            style, tag = "info", "STOPPED"
        end
        W.banner:set{
            text = string.format("REACTOR  %s  --  %s", tag, state.modeText),
            style = style,
        }
    end

    local fuelPct = state.fuelAmount / state.fuelCapacity * 100

    W.energyBar:set{ value = state.energyPct, marker = CONFIG.pidTarget,
                     color = state.energyPct >= 70 and "good" or (state.energyPct >= 40 and "warn" or "bad") }
    W.energyLabel:set{ text = string.format("ENERGY  %5.1f%%  (%s)",
                                             state.energyPct, fmtFE(state.energyStored)) }

    W.fuelBar:set{ value = fuelPct }
    W.fuelLabel:set{ text = string.format("FUEL  %5.1f%%  burn %.2f mB/t  waste %d",
                                          fuelPct, state.fuelConsumedLastTick, math.floor(state.wasteAmount)) }

    W.rodsBar:set{ value = state.rodLevel }
    W.rodsLabel:set{ text = string.format("RODS  %5.1f%%  (%d installed)  PID out %d%%",
                                          state.rodLevel, state.rodCount, math.floor(state.pid.output + 0.5)) }

    -- PID error gauge: center the bar on 50 so positive err moves right.
    -- Gauge value in [0,100]; error in roughly [-pidTarget, 100-pidTarget].
    local errPct = clamp((state.energyPct - CONFIG.pidTarget) + 50, 0, 100)
    W.errorGauge:set{ value = errPct }

    local term, termStyle = pidDominantTerm()
    local termVal = (term == "P" and state.pid.pTerm) or
                    (term == "I" and state.pid.iTerm) or state.pid.dTerm
    W.termIndicator:set{ state = termStyle, label = string.format("term: %s (%+.2f)", term, termVal) }

    W.rodsSpark:push(state.rodLevel)

    W.gainsLabel:set{ text = string.format("Kp=%.1f  Ki=%.2f  Kd=%.0f    target=%d%%    band=%d-%d%%",
                                            CONFIG.pidKp, CONFIG.pidKi, CONFIG.pidKd,
                                            CONFIG.pidTarget, CONFIG.lowerHardPct, CONFIG.upperHardPct) }

    -- Toggle follows whatever the actual plant is doing when in auto.
    if state.manualOverride == nil then
        W.powerToggle:set{ value = state.active }
    end

    ui.invalidate()
end

-- ---------- main ----------
local mon = peripheral.find("monitor")
assert(mon, "no monitor found on this channel")
mon:clear()
mon:clearPixels(0xFF0A0F1A)

local pxW, _pxH = mon:getPixelSize()
local root = buildTree(pxW)
ui.mount(mon, root)

discover()
if state.reactor then readState(); tickControl() end
refresh()

print("reactor_ui running. ctrl-C to exit.")
local timerId = os.startTimer(CONFIG.tickSec)
while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timerId then
        if not state.reactor then
            discover()
        end
        if state.reactor then readState(); tickControl() end
        refresh()
        timerId = os.startTimer(CONFIG.tickSec)
    end
    ui.tick(ev)
end
