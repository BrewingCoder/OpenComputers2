-- reactor_ui.lua -- reactor dashboard built from the ui_v1 widget library.
--
-- Three operational modes (press buttons in the control row to switch):
--   BUFFER  -- default. PID drives rod insertion to keep the energy buffer
--             at CONFIG.pidTarget (%). Tuning card below applies to this mode.
--   EFFIC   -- PID drives rod insertion to hold fuel temperature at
--             CONFIG.efficTempTarget (~1100°C). At that temperature ER2's
--             radiation penalty hasn't kicked in yet and fertility can
--             accumulate, so we burn less fuel per FE. Energy buffer
--             floats; reactor auto-halts if buffer saturates.
--   OUTPUT  -- rods held at 0% (max radiation). Temperature-ceiling PID
--             pulls rods in only if fuel temp exceeds CONFIG.outputTempCeil
--             (~1800°C, just below the high-heat fuel-burn penalty).
--             Maximum raw FE/t, worst FE/mB. Buffer-saturates → halt.
-- Mechanics reference: see ReactorLogic.java:365-380 in the ExtremeReactors2
-- source -- fertility divides rawFuelUsage, and high fuel heat *increases*
-- fuel usage while *reducing* effective radiation. That combination makes
-- ~1100°C the practical efficiency sweet spot.
--
-- Auto-discovers the first bridge peripheral whose protocol + method set
-- looks like a ZeroCore reactor; if nothing matches, shows an error banner
-- and keeps polling so it recovers without a restart.
--
-- Layout (on an 80x27 monitor = 960x324 px):
--   row 0         Banner -- ACTIVE/STOPPED/MANUAL + mode text
--   rows 1..17    two columns:
--                   left  - Energy / Fuel / Rods bars (three Cards)
--                   right - PID indicator, efficiency card, heat card,
--                           rod history sparkline
--   rows 18..22   PID tuning card (Kp/Ki/Kd buttons + value labels,
--                 target +/- and threshold +/-) -- applies to BUFFER mode
--   rows 23..26   control row (POWER, BUFFER, EFFIC, OUTPUT, RESET I)

local ui = require("ui_v1")

-- ------- config -------
local CONFIG = {
    -- BUFFER mode: two-threshold control.
    --   lowerHardPct  -- below this, rods forced to 0 and reactor kicked
    --                    active; PID is frozen/reset. This is also the
    --                    "PID activation" point -- rods start ramping at
    --                    or just above this energy level.
    --   pidTarget     -- where PID tries to hold the buffer. With Kp tuned
    --                    to give a 50-rod-% bias at target and a 0-rod-%
    --                    ramp-start at lowerHardPct (Kp ≈ 50 / (target -
    --                    lower)), rods ramp linearly 0→50 across the band,
    --                    then PID pushes them higher above target.
    --   upperHardPct  -- last-ditch safety halt so ER2 never wastes a full
    --                    buffer on a 99-100% overshoot.
    -- pidMaxSlewPct caps per-tick rod movement so PID can't spike in one
    -- jump when err flips sign.
    lowerHardPct   = 25,
    upperHardPct   = 99,
    pidTarget      = 90,
    -- Kp chosen so raw hits 100 at upper with the default band: bias=88 at
    -- target, err=+9 at upper (99-90), Kp=(100-88)/9 ≈ 1.35. If Scott
    -- widens the band via the UI, Kp may need to nudge up -- math is
    -- Kp = (100 - bias) / (upper - target) = (upper - lower) / (upper - target).
    pidKp          = 1.35,
    pidKi          = 0.10,
    pidKd          = 0.5,
    pidIClamp      = 50,
    pidMaxSlewPct  = 20,
    -- EFFIC mode: fuel-temp-targeted PID. Output is rod insertion %, so the
    -- sign convention is err = (T - target). When too hot, err > 0 pushes
    -- rods IN; when too cold, err < 0 lets rods withdraw toward 0.
    efficTempTarget = 1100,
    efficKp         = 0.05,
    efficKi         = 0.001,
    efficKd         = 0.5,
    efficIClamp     = 10000,
    -- OUTPUT mode: same PID shape, just a higher setpoint -- the ceiling
    -- before ER2's high-heat penalty kicks in hard.
    outputTempCeil  = 1800,
    -- polling / logging
    tickSec         = 0.5,
    sparkCapacity   = 120,
}

-- ------- runtime state -------
local state = {
    reactor = nil,
    controlMode = "BUFFER",                -- BUFFER | EFFIC | OUTPUT (manualOverride supersedes)
    active = false, energyStored = 0, energyCapacity = 1, energyPct = 0,
    fuelAmount = 0, fuelCapacity = 1, fuelTemperature = 0, fuelConsumedLastTick = 0,
    wasteAmount = 0,
    casingTemp = 0,
    fertility = 100,
    isActiveCooled = false,
    energyProducedLastTick = 0,
    hotFluidProducedLastTick = 0,
    efficiency = 0,                         -- FE/mB (passive) or mB/mB (active)
    efficiencyUnit = "FE/mB",
    rodCount = 0, rodLevel = 0,
    pid = { integral = 0, lastError = 0, output = 50, pTerm = 0, iTerm = 0, dTerm = 0 },
    manualOverride = nil,                   -- nil = obey controlMode; true = forced on; false = forced off
    modeText = "INIT",
    discoveryError = nil,
}

local function clamp(v, lo, hi)
    if v < lo then return lo elseif v > hi then return hi else return v end
end

-- ---------- discovery ----------
local REACTOR_DISCRIMINATORS = {
    "getFuelAmount", "getFuelTemperature", "getNumberOfControlRods",
    "getCasingTemperature", "getControlRodsLevels",
}

local function bridgeLooksLikeReactor(b)
    if b.protocol ~= "zerocore" then return false end
    local ok, methods = pcall(function() return b:methods() end)
    if not ok or type(methods) ~= "table" then return false end
    local lookup = {}
    for _, m in ipairs(methods) do
        if type(m) == "string" then lookup[m] = true end
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

-- ---------- logging ----------
local LOG_PATH   = "/reactor_log.csv"
local LOG_HEADER = "epoch_s,clock_s,mode,active,energy_stored,energy_capacity,energy_pct," ..
                   "fuel_amount,fuel_capacity,fuel_pct,fuel_consumed_last_tick," ..
                   "waste_amount,fuel_temperature,casing_temp,fertility," ..
                   "energy_produced,efficiency,rod_level,pid_output," ..
                   "pid_p,pid_i,pid_d,pid_integral," ..
                   "eff_kp,eff_ki,eff_kd,eff_setpoint,lower_pct,upper_pct,mode_text\n"
local LOG_INTERVAL_S = 30

-- ------- PID state persistence -------
-- The full PID/runtime state (integral, output, mode, user-tweaked CONFIG
-- knobs) is saved to STATE_PATH each log-tick (30s) and restored on script
-- start IF the file is younger than STATE_FRESH_S real-world seconds.
--
-- Why a freshness window? If the world was unloaded for a long time the
-- reactor cooled and the buffer drained; restoring an integral that was
-- legit at unload would push rods to a wrong value on a clean restart.
-- 60s catches a reload-or-crash without preserving stale state across
-- "quit for a few hours" sessions. os.time() returns real epoch seconds
-- in OC2 Lua (continues across world unload), which is exactly what we
-- need for this comparison.
local STATE_PATH    = "/reactor_state.json"
local STATE_FRESH_S = 60

-- Active-mode view of the PID configuration -- BUFFER uses pidKp/Ki/Kd against
-- an energy-% setpoint; EFFIC/OUTPUT use efficKp/Ki/Kd against a fuel-temp
-- setpoint. Logging "effective" values keeps one schema across mode changes.
local function effectiveTuning()
    if state.controlMode == "EFFIC" then
        return CONFIG.efficKp, CONFIG.efficKi, CONFIG.efficKd, CONFIG.efficTempTarget
    elseif state.controlMode == "OUTPUT" then
        return CONFIG.efficKp, CONFIG.efficKi, CONFIG.efficKd, CONFIG.outputTempCeil
    else  -- BUFFER
        return CONFIG.pidKp, CONFIG.pidKi, CONFIG.pidKd, CONFIG.pidTarget
    end
end

-- Schema rotation: if an existing log's header doesn't match the current
-- LOG_HEADER, rename it to /reactor_log.<epoch>.csv so we never silently
-- append mismatched columns.
local function logInit()
    local ok, exists = pcall(fs.exists, LOG_PATH)
    if ok and exists then
        local okRead, handle = pcall(fs.open, LOG_PATH, "r")
        if okRead and handle then
            local firstLine = handle.readLine()
            handle.close()
            if (firstLine or "") .. "\n" == LOG_HEADER then return end
        end
        local rotated = string.format("/reactor_log.%d.csv", os.time())
        pcall(fs.move, LOG_PATH, rotated)
    end
    pcall(fs.write, LOG_PATH, LOG_HEADER)
end

-- Persist enough state to seamlessly resume after a script kill / world
-- reload: the PID accumulators (so we don't re-integrate from zero) plus
-- every CONFIG knob the user can change from the tuning card (so the
-- buttons aren't "forgotten" on restart). Static defaults (efficKp etc.)
-- are NOT persisted -- if you change them in the source, that should win.
local function saveStateFile()
    local payload = {
        savedAt        = os.time(),
        controlMode    = state.controlMode,
        manualOverride = state.manualOverride,
        pid            = {
            integral  = state.pid.integral,
            lastError = state.pid.lastError,
            output    = state.pid.output,
            pTerm     = state.pid.pTerm,
            iTerm     = state.pid.iTerm,
            dTerm     = state.pid.dTerm,
        },
        config = {
            lowerHardPct = CONFIG.lowerHardPct,
            upperHardPct = CONFIG.upperHardPct,
            pidTarget    = CONFIG.pidTarget,
            pidKp        = CONFIG.pidKp,
            pidKi        = CONFIG.pidKi,
            pidKd        = CONFIG.pidKd,
        },
    }
    local ok, encoded = pcall(json.encode, payload)
    if not ok or type(encoded) ~= "string" then return end
    pcall(fs.write, STATE_PATH, encoded)
end

-- Apply a previously saved snapshot to the live state IF the file exists
-- and is younger than STATE_FRESH_S seconds. Silent no-op otherwise --
-- the script just keeps its CONFIG defaults and a zeroed PID.
local function loadStateFile()
    local ok, exists = pcall(fs.exists, STATE_PATH)
    if not ok or not exists then return end
    local okOpen, handle = pcall(fs.open, STATE_PATH, "r")
    if not okOpen or not handle then return end
    local raw = handle.readAll() or ""
    handle.close()
    if raw == "" then return end
    local okDec, payload = pcall(json.decode, raw)
    if not okDec or type(payload) ~= "table" then return end
    local age = os.time() - (tonumber(payload.savedAt) or 0)
    if age < 0 or age > STATE_FRESH_S then
        print(string.format("reactor_ui: ignoring stale state (age=%ds > %ds)", age, STATE_FRESH_S))
        return
    end
    -- Restore. Each field guarded so a partially-truncated file still
    -- yields a usable session rather than crashing on init.
    if type(payload.controlMode) == "string" then state.controlMode = payload.controlMode end
    if payload.manualOverride ~= nil then state.manualOverride = payload.manualOverride end
    if type(payload.pid) == "table" then
        for k, v in pairs(payload.pid) do
            if type(v) == "number" then state.pid[k] = v end
        end
    end
    if type(payload.config) == "table" then
        for k, v in pairs(payload.config) do
            if type(v) == "number" and CONFIG[k] ~= nil then CONFIG[k] = v end
        end
    end
    print(string.format("reactor_ui: resumed PID state (age=%ds, mode=%s, integral=%.2f, output=%.2f)",
        age, state.controlMode, state.pid.integral, state.pid.output))
end

local function logRow()
    if not state.reactor then return end
    local fuelPct = state.fuelAmount / state.fuelCapacity * 100
    local modeText = tostring(state.modeText or ""):gsub(",", ";")
    local kp, ki, kd, setpoint = effectiveTuning()
    local row = string.format(
        "%d,%.3f,%s,%d,%d,%d,%.3f,%d,%d,%.3f,%.6f,%d,%.1f,%.1f,%.1f,%.3f,%.3f,%.2f,%.2f," ..
        "%.3f,%.3f,%.3f,%.3f," ..
        "%.4f,%.4f,%.4f,%.2f,%d,%d,%s\n",
        os.time(), os.clock(),
        state.controlMode,
        state.active and 1 or 0,
        math.floor(state.energyStored), math.floor(state.energyCapacity), state.energyPct,
        math.floor(state.fuelAmount), math.floor(state.fuelCapacity), fuelPct,
        state.fuelConsumedLastTick,
        math.floor(state.wasteAmount), state.fuelTemperature,
        state.casingTemp, state.fertility,
        state.energyProducedLastTick, state.efficiency,
        state.rodLevel, state.pid.output,
        state.pid.pTerm, state.pid.iTerm, state.pid.dTerm, state.pid.integral,
        kp, ki, kd, setpoint, CONFIG.lowerHardPct, CONFIG.upperHardPct, modeText)
    pcall(fs.append, LOG_PATH, row)
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
        state.energyProducedLastTick = es.energyProducedLastTick or 0
        local fs = r:call("getFuelStats") or {}
        state.fuelAmount           = fs.fuelAmount or 0
        state.fuelCapacity         = (fs.fuelCapacity and fs.fuelCapacity > 0) and fs.fuelCapacity or 1
        state.fuelTemperature      = fs.fuelTemperature or 0
        state.fuelConsumedLastTick = fs.fuelConsumedLastTick or 0
        state.fertility            = fs.fuelReactivity or 100
        state.wasteAmount          = fs.wasteAmount or 0
        state.casingTemp           = r:call("getCasingTemperature") or 0
        state.isActiveCooled       = r:call("isActivelyCooled") or false
        if state.isActiveCooled then
            local hf = r:call("getHotFluidStats") or {}
            state.hotFluidProducedLastTick = hf.fluidProducedLastTick or 0
            state.efficiency = (state.fuelConsumedLastTick > 0.00001)
                and state.hotFluidProducedLastTick / state.fuelConsumedLastTick or 0
            state.efficiencyUnit = "mB/mB"
        else
            state.efficiency = (state.fuelConsumedLastTick > 0.00001)
                and state.energyProducedLastTick / state.fuelConsumedLastTick or 0
            state.efficiencyUnit = "FE/mB"
        end
        local rods = r:call("getControlRodsLevels") or {}
        local sum, n = 0, 0
        for _, v in pairs(rods) do sum = sum + (tonumber(v) or 0); n = n + 1 end
        state.rodLevel = (n > 0) and (sum / n) or 0
    end)
    if not ok then
        state.reactor = nil
        state.discoveryError = "reactor lost: " .. tostring(err)
    end
end

-- ---------- control strategies ----------
-- BUFFER: PID on energy %. Historical default.
local function tickBuffer(r)
    if state.energyPct >= CONFIG.upperHardPct and state.active then
        pcall(function() r:call("setActive", false) end)
        state.modeText = string.format("BUFFER HALT E>=%d%%", CONFIG.upperHardPct)
        return
    end
    if state.energyPct <= CONFIG.lowerHardPct and not state.active then
        pcall(function()
            r:call("setActive", true)
            r:call("setAllControlRodLevels", 0)
        end)
        state.pid.integral = 0; state.pid.lastError = 0; state.pid.output = 0
        state.modeText = string.format("BUFFER START E<=%d%%", CONFIG.lowerHardPct)
        return
    end
    if not state.active then
        state.modeText = "BUFFER IDLE (waiting for low threshold)"
        return
    end
    local err = state.energyPct - CONFIG.pidTarget
    local deriv = err - state.pid.lastError
    state.pid.lastError = err

    -- Anti-windup (conditional integration): skip the integral update when
    -- output is already pinned against a saturation limit in the direction
    -- err is trying to push. Without this, the first time the buffer drains
    -- for a while integrates to -50 and the PID can't unwind fast enough to
    -- insert rods when the buffer refills -- we watched it overshoot straight
    -- from 15% to 87% without ever pushing rods above 16%.
    local sat = (state.pid.output >= 99 and err > 0) or
                (state.pid.output <= 1  and err < 0)
    if not sat then
        state.pid.integral = clamp(state.pid.integral + err,
                                   -CONFIG.pidIClamp, CONFIG.pidIClamp)
    end

    state.pid.pTerm = CONFIG.pidKp * err
    state.pid.iTerm = CONFIG.pidKi * state.pid.integral
    state.pid.dTerm = CONFIG.pidKd * deriv
    -- Dynamic bias: rod position predicted by a linear ramp across the
    -- [lowerHardPct .. upperHardPct] band at energy = pidTarget. Hard-coding
    -- bias=50 clamped the output ceiling: with target=90, upper=99, Kp=0.8
    -- the max raw was 50 + 0.8*9 = ~57, so rods never exceeded 57% even
    -- during overshoot. Deriving bias from the band geometry makes the
    -- steady-state rod position match the linear-ramp expectation (~88% at
    -- target=90 with the 25-99 band) and lets P drive raw to 100 at upper.
    local band = math.max(1, CONFIG.upperHardPct - CONFIG.lowerHardPct)
    local bias = 100 * clamp((CONFIG.pidTarget - CONFIG.lowerHardPct) / band, 0, 1)
    local raw = clamp(state.pid.pTerm + state.pid.iTerm + state.pid.dTerm + bias, 0, 100)
    local slew = CONFIG.pidMaxSlewPct or 100
    local out = clamp(raw, state.pid.output - slew, state.pid.output + slew)
    state.pid.output = out
    pcall(function() r:call("setAllControlRodLevels", math.floor(out + 0.5)) end)
    state.modeText = string.format("BUFFER rod=%d%% err=%+.1f", math.floor(out + 0.5), err)
end

-- Shared temperature-targeted PID used by EFFIC and OUTPUT.
-- Positive error (temp > setpoint) pushes rods IN (insertion up).
-- Negative error lets them pull out toward 0.
local function tickTempPid(r, setpoint, label)
    -- Buffer-full safety: no point cooking fuel when the buffer can't absorb it.
    if state.energyPct >= 99 then
        if state.active then pcall(function() r:call("setActive", false) end) end
        state.modeText = label .. " HOLD (buffer 99%)"
        return
    end
    if not state.active then
        pcall(function()
            r:call("setActive", true)
            r:call("setAllControlRodLevels", 0)
        end)
        state.pid.integral = 0; state.pid.lastError = 0; state.pid.output = 0
        state.modeText = label .. " START (cold)"
        return
    end
    local err = state.fuelTemperature - setpoint
    state.pid.integral = clamp(state.pid.integral + err, -CONFIG.efficIClamp, CONFIG.efficIClamp)
    local deriv = err - state.pid.lastError
    state.pid.lastError = err
    state.pid.pTerm = CONFIG.efficKp * err
    state.pid.iTerm = CONFIG.efficKi * state.pid.integral
    state.pid.dTerm = CONFIG.efficKd * deriv
    local out = clamp(state.pid.pTerm + state.pid.iTerm + state.pid.dTerm, 0, 100)
    state.pid.output = out
    pcall(function() r:call("setAllControlRodLevels", math.floor(out + 0.5)) end)
    state.modeText = string.format("%s rod=%d%% T=%.0fC err=%+.0f",
        label, math.floor(out + 0.5), state.fuelTemperature, err)
end

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
    if state.controlMode == "EFFIC" then
        tickTempPid(r, CONFIG.efficTempTarget, "EFFIC")
    elseif state.controlMode == "OUTPUT" then
        tickTempPid(r, CONFIG.outputTempCeil, "OUTPUT")
    else
        tickBuffer(r)
    end
end

local function setMode(newMode)
    if state.controlMode == newMode and state.manualOverride == nil then return end
    state.controlMode = newMode
    state.manualOverride = nil
    -- Reset PID between modes -- scales and setpoints differ wildly.
    state.pid.integral = 0; state.pid.lastError = 0; state.pid.output = 50
end

-- ---------- widget tree ----------
local W = {}
local refresh

local function fmtFE(n)
    n = tonumber(n) or 0
    if n >= 1e9 then return string.format("%.2fGFE", n / 1e9) end
    if n >= 1e6 then return string.format("%.2fMFE", n / 1e6) end
    if n >= 1e3 then return string.format("%.2fkFE", n / 1e3) end
    return string.format("%dFE", math.floor(n))
end

-- Compact formatter for any positive scalar. Drops trailing `.0` for whole
-- numbers. ER2's fuelReactivity accumulator can reach 6+ digits so we need
-- k/M suffixes to keep the label from wrapping.
local function fmtCompact(n)
    n = tonumber(n) or 0
    if n >= 1e6 then return string.format("%.1fM", n / 1e6) end
    if n >= 1e3 then return string.format("%.1fk", n / 1e3) end
    if n >= 10  then return string.format("%.0f", n) end
    return string.format("%.1f", n)
end

local function pidDominantTerm()
    local a = math.abs(state.pid.pTerm)
    local b = math.abs(state.pid.iTerm)
    local c = math.abs(state.pid.dTerm)
    if a >= b and a >= c then return "P", "info"
    elseif b >= c then return "I", "warn"
    else return "D", "good" end
end

local function buildTree(pxW)
    local innerW = pxW - 8
    local halfW = math.floor((innerW - 4) / 2)

    W.banner = ui.Banner{ text = "REACTOR UI  --  booting...", style = "info",
                          height = 20, width = innerW }

    -- LEFT column: Energy / Fuel / Rods cards. Bars bumped to 36px tall and
    -- `pctScale=3` so the %-readout becomes chunky-pixel 15x21 text. Readable
    -- from across the room -- the default 5x7 is unreadable past ~3 blocks.
    W.energyBar = ui.Bar{ value = 0, height = 36, color = "good", showPct = true, pctScale = 3, marker = CONFIG.pidTarget, bg = "edge" }
    W.energyLabel = ui.Label{ text = "ENERGY  0.0%  (0FE)", color = "fg", height = 12 }
    local energyCard = ui.Card{ padding = 4, gap = 2, width = halfW, children = {
        W.energyLabel, W.energyBar,
    } }

    W.fuelBar = ui.Bar{ value = 0, height = 36, color = "warn", showPct = true, pctScale = 3, bg = "edge" }
    W.fuelLabel = ui.Label{ text = "FUEL  0.0%  burn 0 mB/t  waste 0", color = "fg", height = 12 }
    local fuelCard = ui.Card{ padding = 4, gap = 2, width = halfW, children = {
        W.fuelLabel, W.fuelBar,
    } }

    W.rodsBar = ui.Bar{ value = 0, height = 36, color = "info", showPct = true, pctScale = 3, bg = "edge" }
    W.rodsLabel = ui.Label{ text = "RODS  0.0%  (0 installed)", color = "fg", height = 12 }
    local rodsCard = ui.Card{ padding = 4, gap = 2, width = halfW, children = {
        W.rodsLabel, W.rodsBar,
    } }

    local leftCol = ui.VBox{ padding = 0, gap = 4, flex = 1, width = halfW, children = {
        energyCard, fuelCard, rodsCard,
    } }

    -- RIGHT column: mode status, efficiency card, heat card, rod sparkline.
    -- Heights budgeted to 218px total (body flex=1 on a 324px monitor).
    -- Right column uses *flex* sparklines (not fixed height) so the card
    -- never overflows: if the monitor is small, the sparklines share whatever
    -- vertical budget is left after the value rows. If the monitor is big,
    -- they grow. Previous fixed-height layout would overflow the card bounds
    -- on 2x3 monitors and the last sparkline(s) would render under the next
    -- card instead of inside their own.
    --
    -- Banner already shows mode; termIndicator rides along with the mode
    -- prefix so we don't spend a dedicated MODE row. Rod section has no
    -- value label -- the 0-100 baseline and the bar in the left column
    -- already identify it.
    W.termIndicator = ui.Indicator{ height = 14, state = "info", label = "BUFFER  term: P (0.00)", size = 8 }

    W.efficValue = ui.Label{ text = "EFFIC  0 FE/mB    fertility 0", color = "fg", height = 12 }
    W.efficSpark = ui.Sparkline{ flex = 1, capacity = CONFIG.sparkCapacity,
                                  color = "good", fill = true, showLast = true }

    W.heatValue = ui.Label{ text = "HEAT   fuel 0C   casing 0C", color = "fg", height = 12 }
    W.heatSpark = ui.Sparkline{ flex = 1, capacity = CONFIG.sparkCapacity,
                                 min = 0, max = 2500,
                                 color = "warn", baseline = CONFIG.efficTempTarget,
                                 baselineColor = "muted", showLast = true }

    W.rodsValue = ui.Label{ text = "RODS   0%", color = "fg", height = 12 }
    W.rodsSpark = ui.Sparkline{ flex = 1, capacity = CONFIG.sparkCapacity,
                                 min = 0, max = 100,
                                 color = "info", baseline = 50, baselineColor = "muted",
                                 fill = true, showLast = true }

    local rightCol = ui.Card{ padding = 4, flex = 1, children = {
        ui.VBox{ padding = 0, gap = 4, flex = 1, children = {
            W.termIndicator,
            W.efficValue, W.efficSpark,
            W.heatValue,  W.heatSpark,
            W.rodsValue,  W.rodsSpark,
        } },
    } }

    local body = ui.HBox{ padding = 0, gap = 4, flex = 1, width = innerW,
                          children = { leftCol, rightCol } }

    -- Tuning row: applies to BUFFER mode's PID.
    W.gainsLabel = ui.Label{ text = "BUFFER tuning  Kp=2.0  Ki=0.10  Kd=5.0  tgt=75  band=20-80",
                              color = "fg", align = "center", height = 12 }
    local function stepBtn(label, fn)
        return ui.Button{ label = label, height = 20, style = "primary", flex = 1,
                          onClick = function() fn(); refresh() end }
    end
    -- Split into two rows: PID gains on top, energy-band thresholds below.
    -- One 12-wide row was too cramped to read the labels at typical monitor
    -- sizes; two 6-wide rows give each button enough width for the label.
    -- innerW propagated explicitly: a non-flex HBox containing only flex
    -- children will otherwise measure-hug to ~gap-width and starve every
    -- button to 0px. controlRow uses the same pattern for the same reason.
    local rowW = innerW - 8  -- minus tuningCard's 2*padding
    local gainsRow = ui.HBox{ padding = 0, gap = 2, height = 20, width = rowW, children = {
        stepBtn("Kp-", function() CONFIG.pidKp = math.max(0, CONFIG.pidKp - 0.5) end),
        stepBtn("Kp+", function() CONFIG.pidKp = CONFIG.pidKp + 0.5 end),
        stepBtn("Ki-", function() CONFIG.pidKi = math.max(0, CONFIG.pidKi - 0.05) end),
        stepBtn("Ki+", function() CONFIG.pidKi = CONFIG.pidKi + 0.05 end),
        stepBtn("Kd-", function() CONFIG.pidKd = math.max(0, CONFIG.pidKd - 0.5) end),
        stepBtn("Kd+", function() CONFIG.pidKd = CONFIG.pidKd + 0.5 end),
    } }
    local bandRow = ui.HBox{ padding = 0, gap = 2, height = 20, width = rowW, children = {
        stepBtn("LO-",  function() CONFIG.lowerHardPct = math.max(0, CONFIG.lowerHardPct - 5) end),
        stepBtn("LO+",  function() CONFIG.lowerHardPct = math.min(CONFIG.pidTarget - 5, CONFIG.lowerHardPct + 5) end),
        stepBtn("TGT-", function() CONFIG.pidTarget = clamp(CONFIG.pidTarget - 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("TGT+", function() CONFIG.pidTarget = clamp(CONFIG.pidTarget + 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("HI-",  function() CONFIG.upperHardPct = math.max(CONFIG.pidTarget + 5, CONFIG.upperHardPct - 5) end),
        stepBtn("HI+",  function() CONFIG.upperHardPct = math.min(100, CONFIG.upperHardPct + 5) end),
    } }
    local tuningCard = ui.Card{ padding = 4, width = innerW, children = {
        ui.VBox{ padding = 0, gap = 4, children = { W.gainsLabel, gainsRow, bandRow } },
    } }

    -- Control row: POWER + 3 mode buttons + RESET I. Mode buttons recolor
    -- via the `color` prop (theme token) in refresh(): "good" = active,
    -- "ghost" = inactive. Button.style is fixed at "primary".
    W.powerToggle = ui.Toggle{
        label = "POWER", value = false, height = 20, flex = 1,
        onLabel = "ON", offLabel = "OFF",
        onColor = "good", offColor = "danger",
        onChange = function(v) state.manualOverride = v end,
    }
    W.modeBtnBuffer = ui.Button{ label = "BUFFER", height = 20, style = "primary", flex = 1, color = "ghost",
        onClick = function() setMode("BUFFER"); W.powerToggle:set{ value = state.active }; refresh() end }
    W.modeBtnEffic = ui.Button{ label = "EFFIC", height = 20, style = "primary", flex = 1, color = "ghost",
        onClick = function() setMode("EFFIC"); W.powerToggle:set{ value = state.active }; refresh() end }
    W.modeBtnOutput = ui.Button{ label = "OUTPUT", height = 20, style = "primary", flex = 1, color = "ghost",
        onClick = function() setMode("OUTPUT"); W.powerToggle:set{ value = state.active }; refresh() end }
    local resetBtn = ui.Button{ label = "RESET I", height = 20, style = "ghost", flex = 1,
        onClick = function() state.pid.integral = 0; state.pid.lastError = 0 end }
    local controlRow = ui.HBox{ padding = 0, gap = 4, height = 22, width = innerW, children = {
        W.powerToggle, W.modeBtnBuffer, W.modeBtnEffic, W.modeBtnOutput, resetBtn,
    } }

    local root = ui.VBox{ padding = 4, gap = 4, children = {
        W.banner,
        body,
        tuningCard,
        controlRow,
    } }
    return root
end

-- ---------- render bridge ----------
refresh = function ()
    if state.discoveryError then
        W.banner:set{ text = "ERROR: " .. state.discoveryError .. " -- retrying", style = "bad" }
    else
        local style, tag
        if state.manualOverride ~= nil then
            style, tag = "warn", (state.manualOverride and "MANUAL ON" or "MANUAL OFF")
        elseif state.active then
            style, tag = "good", state.controlMode .. " ACTIVE"
        else
            style, tag = "info", state.controlMode .. " STOPPED"
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

    -- Right column: mode + telemetry. Mode is folded into the term indicator
    -- label so we don't spend an extra row on a dedicated MODE line.
    local modeTag
    if state.manualOverride ~= nil then
        modeTag = "MANUAL"
    else
        modeTag = state.controlMode
    end

    local term, termStyle = pidDominantTerm()
    local termVal = (term == "P" and state.pid.pTerm) or
                    (term == "I" and state.pid.iTerm) or state.pid.dTerm
    W.termIndicator:set{ state = termStyle,
        label = string.format("%s  term: %s (%+.2f)", modeTag, term, termVal) }

    -- Efficiency: FE/mB (or mB/mB for active-cooled) + fertility.
    -- ER2 surfaces fuelReactivity as an accumulator that reaches 6+ digits, so
    -- both numbers get the k/M compact treatment to fit the label width.
    W.efficValue:set{ text = string.format("EFFIC  %s %s    fertility %s",
        fmtCompact(state.efficiency), state.efficiencyUnit, fmtCompact(state.fertility)) }
    W.efficSpark:push(state.efficiency)

    -- Heat: fuel + casing temps. Sparkline baseline tracks current mode's setpoint
    -- so you can see "how close to target are we" at a glance.
    local setpoint
    if state.controlMode == "EFFIC" then setpoint = CONFIG.efficTempTarget
    elseif state.controlMode == "OUTPUT" then setpoint = CONFIG.outputTempCeil
    else setpoint = nil end  -- BUFFER mode doesn't target temp; hide the guide line
    W.heatValue:set{ text = string.format("HEAT   fuel %.0fC   casing %.0fC",
        state.fuelTemperature, state.casingTemp) }
    W.heatSpark:set{ baseline = setpoint }
    W.heatSpark:push(state.fuelTemperature)

    W.rodsValue:set{ text = string.format("RODS   %.0f%%  (PID out %d%%)",
        state.rodLevel, math.floor(state.pid.output + 0.5)) }
    W.rodsSpark:push(state.rodLevel)

    W.gainsLabel:set{ text = string.format("BUFFER tuning  Kp=%.1f  Ki=%.2f  Kd=%.0f    target=%d%%    band=%d-%d%%",
                                            CONFIG.pidKp, CONFIG.pidKi, CONFIG.pidKd,
                                            CONFIG.pidTarget, CONFIG.lowerHardPct, CONFIG.upperHardPct) }

    -- Mode button highlight via color token override (see buildTree note).
    W.modeBtnBuffer:set{ color = (state.manualOverride == nil and state.controlMode == "BUFFER") and "good" or "ghost" }
    W.modeBtnEffic:set{  color = (state.manualOverride == nil and state.controlMode == "EFFIC")  and "good" or "ghost" }
    W.modeBtnOutput:set{ color = (state.manualOverride == nil and state.controlMode == "OUTPUT") and "good" or "ghost" }

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

logInit()
loadStateFile()  -- restore PID/CONFIG/mode from disk if file is fresh
discover()
-- Force reactor OFF at script start so PID stays IDLE until the buffer
-- naturally drains below lowerHardPct. Matches the CC reactor script's
-- startup behavior: never auto-resume on a partial-or-full buffer; wait
-- for the natural low-threshold crossing to kick on. Without this, a
-- script restart while the reactor is hot would immediately enter the
-- PID branch and overshoot.
--
-- We do NOT zero the PID here -- loadStateFile() may have just restored
-- a mid-cycle integral from disk, and zeroing it would defeat the whole
-- point of persistence. The "BUFFER START" branch in tickBuffer zeroes
-- PID when it crosses the lower threshold from rest, which is the right
-- moment to start a fresh control cycle.
if state.reactor then
    pcall(function() state.reactor:call("setActive", false) end)
    state.active = false
end
if state.reactor then readState(); tickControl(); logRow() end
refresh()

print(string.format("reactor_ui running  mode=%s  tick=%.1fs  log=%ds",
    state.controlMode, CONFIG.tickSec, LOG_INTERVAL_S))
print("buttons: BUFFER | EFFIC | OUTPUT to switch strategy; POWER for manual override")

local timerId    = os.startTimer(CONFIG.tickSec)
local logTimerId = os.startTimer(LOG_INTERVAL_S)
while true do
    local ev = { os.pullEvent() }
    if ev[1] == "timer" and ev[2] == timerId then
        if not state.reactor then discover() end
        if state.reactor then readState(); tickControl() end
        refresh()
        timerId = os.startTimer(CONFIG.tickSec)
    elseif ev[1] == "timer" and ev[2] == logTimerId then
        logRow()
        saveStateFile()
        logTimerId = os.startTimer(LOG_INTERVAL_S)
    end
    ui.tick(ev)
end
