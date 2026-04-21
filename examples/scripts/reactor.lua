-- reactor.lua -- corporate-KPI reactor dashboard.
-- HD layer: card backgrounds + horizontal bars. Text layer: colored labels/values.
-- 960x324 px ; 80 cols x 27 rows.

local CONFIG = {
  lowerHardPct = 20,
  upperHardPct = 80,
  pidTarget    = 75,
  pidKp        = 2.0,
  pidKi        = 0.10,
  pidKd        = 5.0,
  pidIClamp    = 100,
  tickSec      = 1.0,
}

local state = {
  reactor = nil, monitors = {}, modeText = "INIT",
  active = false, energyStored = 0, energyCapacity = 1, energyPct = 0,
  energyProducedLastTick = 0,
  fuelAmount = 0, fuelCapacity = 1, fuelTemperature = 0,
  fuelConsumedLastTick = 0, fuelReactivity = 0, wasteAmount = 0,
  casingTemperature = 0, rodCount = 0, rodLevel = 0,
  pid = { integral = 0, lastError = 0, output = 50 },
  manualOverride = nil,
  buttons = {},
}

-- ---------- color palette (ARGB) ----------
local C = {
  bg      = 0xFF0A0E1A,
  panel   = 0xFF141A28,
  panelHi = 0xFF1A2236,
  border  = 0xFF253148,
  accent  = 0xFF3A6FB8,
  accentLo= 0xFF1F3D70,
  rule    = 0xFF2A5090,
  txtHi   = 0xFFEEF2F8,
  txtMid  = 0xFFB8C2D0,
  txtDim  = 0xFF6B7588,
  good    = 0xFF2ECC71,
  goodLo  = 0xFF143A22,
  warn    = 0xFFE8A23A,
  warnLo  = 0xFF3F2A0E,
  bad     = 0xFFE05050,
  badLo   = 0xFF3A1818,
  rod     = 0xFF5DADE2,
  rodLo   = 0xFF153244,
  btn     = 0xFF1F2C44,
  btnEdge = 0xFF4A82C8,
  marker  = 0xFFFFFFFF,
}

-- text-color codes (matches ScriptColors palette ARGB)
local TC = {
  hi   = 0xFFEEF2F8,
  mid  = 0xFFB8C2D0,
  dim  = 0xFF6B7588,
  good = 0xFF2ECC71,
  warn = 0xFFE8A23A,
  bad  = 0xFFE05050,
  rod  = 0xFF5DADE2,
  acc  = 0xFFEEF2F8,
}

-- ---------- helpers ----------
local function clamp(v, lo, hi) if v<lo then return lo elseif v>hi then return hi else return v end end

local function fmtFE(n)
  n = tonumber(n) or 0
  if n >= 1e9 then return string.format("%.2fGFE", n/1e9) end
  if n >= 1e6 then return string.format("%.2fMFE", n/1e6) end
  if n >= 1e3 then return string.format("%.2fkFE", n/1e3) end
  return string.format("%dFE", math.floor(n))
end

local function pickFill(pct)
  if pct >= 70 then return C.good, C.goodLo, TC.good end
  if pct >= 40 then return C.warn, C.warnLo, TC.warn end
  return C.bad, C.badLo, TC.bad
end

-- ---------- discovery / read / control ----------
local function discover()
  state.reactor = peripheral.find("bridge")
  if not state.reactor then error("no bridge peripheral found") end
  if state.reactor.protocol ~= "zerocore" then
    error("bridge protocol="..tostring(state.reactor.protocol)..", expected zerocore")
  end
  state.monitors = peripheral.list("monitor") or {}
  state.rodCount = state.reactor:call("getNumberOfControlRods") or 0
  print(string.format("ready: %d rods, %d monitor(s)", state.rodCount, #state.monitors))
end

local function readState()
  local r = state.reactor
  state.active = r:call("getActive") or false
  local es = r:call("getEnergyStats") or {}
  state.energyStored          = es.energyStored or 0
  state.energyCapacity        = (es.energyCapacity and es.energyCapacity > 0) and es.energyCapacity or 1
  state.energyProducedLastTick = es.energyProducedLastTick or 0
  state.energyPct = state.energyStored / state.energyCapacity * 100
  local fs = r:call("getFuelStats") or {}
  state.fuelAmount           = fs.fuelAmount or 0
  state.fuelCapacity         = (fs.fuelCapacity and fs.fuelCapacity > 0) and fs.fuelCapacity or 1
  state.fuelTemperature      = fs.fuelTemperature or 0
  state.fuelConsumedLastTick = fs.fuelConsumedLastTick or 0
  state.fuelReactivity       = fs.fuelReactivity or 0
  state.wasteAmount          = fs.wasteAmount or 0
  state.casingTemperature    = r:call("getCasingTemperature") or 0
  local rods = r:call("getControlRodsLevels") or {}
  local sum, n = 0, 0
  for _, v in pairs(rods) do sum = sum + (tonumber(v) or 0); n = n + 1 end
  state.rodLevel = (n > 0) and (sum / n) or 0
end

local function tickControl()
  local r = state.reactor
  if state.manualOverride ~= nil then
    if state.active ~= state.manualOverride then r:call("setActive", state.manualOverride) end
    if state.manualOverride then
      r:call("setAllControlRodLevels", 0); state.modeText = "MANUAL ON  rods=0"
    else
      state.modeText = "MANUAL OFF"
    end
    return
  end
  if state.energyPct >= CONFIG.upperHardPct and state.active then
    r:call("setActive", false); state.modeText = string.format("HALT  E>=%d%%", CONFIG.upperHardPct); return
  end
  if state.energyPct <= CONFIG.lowerHardPct and not state.active then
    r:call("setActive", true); r:call("setAllControlRodLevels", 0)
    state.pid.integral = 0; state.pid.lastError = 0; state.pid.output = 0
    state.modeText = string.format("START E<=%d%%", CONFIG.lowerHardPct); return
  end
  if not state.active then state.modeText = "IDLE  (waiting for low threshold)"; return end
  local err = state.energyPct - CONFIG.pidTarget
  state.pid.integral = clamp(state.pid.integral + err, -CONFIG.pidIClamp, CONFIG.pidIClamp)
  local deriv = err - state.pid.lastError
  state.pid.lastError = err
  local out = CONFIG.pidKp*err + CONFIG.pidKi*state.pid.integral + CONFIG.pidKd*deriv
  out = clamp(out + 50, 0, 100)
  state.pid.output = out
  r:call("setAllControlRodLevels", math.floor(out + 0.5))
  state.modeText = string.format("PID  rod=%d%%  err=%+.1f", math.floor(out+0.5), err)
end

-- ---------- HD primitives ----------
local function fillCard(m, x, y, w, h)
  m:drawRect(x, y, w, h, C.panel)
  m:drawRectOutline(x, y, w, h, C.border, 1)
end

local function bar(m, x, y, w, h, pct, fill, fillLo)
  pct = clamp(pct, 0, 100)
  m:drawRect(x, y, w, h, fillLo)
  local fw = math.floor(w * pct / 100)
  if fw > 0 then
    m:drawGradientV(x, y, fw, h, fill, fillLo)
    m:drawRect(x, y, fw, math.max(2, math.floor(h/3)), fill)
  end
  m:drawRectOutline(x, y, w, h, C.border, 1)
end

-- ---------- text helpers ----------
local function tcell(m, col, row, fg, txt)
  m:setForegroundColor(fg)
  m:setCursorPos(col, row)
  m:write(txt)
end

local function defineButtons(pxW, pxH)
  -- 5 buttons across the bottom, each 180-ish wide, 32 tall
  local y = 268
  local h = 32
  local n = 5
  local pad = 8
  local totalPad = pad * (n + 1)
  local bw = math.floor((pxW - totalPad) / n)
  local labels = {
    { id="toggle", label="ON / OFF" },
    { id="auto",   label="  AUTO"   },
    { id="tdn",    label=" TGT - "  },
    { id="tup",    label=" TGT + "  },
    { id="reset",  label="  RESET"  },
  }
  state.buttons = {}
  for i = 1, n do
    local x = pad + (i-1) * (bw + pad)
    state.buttons[i] = { id = labels[i].id, label = labels[i].label, x = x, y = y, w = bw, h = h }
  end
end

-- ---------- render ----------
local function renderTo(m)
  local pxW, pxH = m:getPixelSize()
  if pxW == 0 then return end
  if #state.buttons == 0 then defineButtons(pxW, pxH) end
  local cols, _ = m:getSize()
  local fuelPct = state.fuelAmount / state.fuelCapacity * 100
  local tFuelPct = clamp(state.fuelTemperature / 2000 * 100, 0, 100)
  local tCasePct = clamp(state.casingTemperature / 2000 * 100, 0, 100)

  -- ===== HD LAYER =====
  m:clearPixels(C.bg)

  -- header band (rows 0-1, y 0..24) + thin rule below
  m:drawRect(0, 0, pxW, 24, C.accent)
  m:drawGradientV(0, 18, pxW, 6, C.accent, C.accentLo)
  m:drawRect(0, 26, pxW, 2, C.rule)

  -- KPI cards: 3 stacked, each 40 px tall (rows 3..6 = y 36..76, etc)
  -- card 1 (energy)  y 32..76
  fillCard(m, 8, 32, pxW - 16, 44)
  -- card 2 (fuel)    y 84..128
  fillCard(m, 8, 84, pxW - 16, 44)
  -- card 3 (rods)    y 136..180
  fillCard(m, 8, 136, pxW - 16, 44)

  -- bars inside cards (y 56..68, bar width starts after 14-col label = 168 px in)
  local barX = 168
  local barW = pxW - 16 - barX - 8

  local efill, elo, _ = pickFill(state.energyPct)
  bar(m, barX, 56, barW, 12, state.energyPct, efill, elo)
  -- target marker (white) on energy bar
  local tx = barX + math.floor(barW * CONFIG.pidTarget / 100)
  m:drawLine(tx, 52, tx, 70, C.marker)

  local ffill, flo, _ = pickFill(fuelPct)
  bar(m, barX, 108, barW, 12, fuelPct, ffill, flo)

  bar(m, barX, 160, barW, 12, state.rodLevel, C.rod, C.rodLo)

  -- temps mini block (y 188..220)
  fillCard(m, 8, 188, pxW - 16, 32)
  local halfW = math.floor((barW - 16) / 2)
  bar(m, barX,             204, halfW, 10, tFuelPct, C.warn, C.warnLo)
  bar(m, barX + halfW + 16, 204, halfW, 10, tCasePct, C.warn, C.warnLo)

  -- status banner (y 228..256) — colored by mode
  local bannerCol, bannerLo
  if state.manualOverride ~= nil then bannerCol, bannerLo = C.warn, C.warnLo
  elseif state.active then bannerCol, bannerLo = C.good, C.goodLo
  else bannerCol, bannerLo = C.bad, C.badLo end
  m:drawRect(8, 228, pxW - 16, 28, bannerLo)
  m:drawGradientV(8, 228, 6, 28, bannerCol, bannerLo)  -- left edge accent
  m:drawRectOutline(8, 228, pxW - 16, 28, C.border, 1)

  -- buttons
  for _, b in ipairs(state.buttons) do
    m:drawRect(b.x, b.y, b.w, b.h, C.btn)
    m:drawGradientV(b.x, b.y, b.w, math.floor(b.h/2), C.panelHi, C.btn)
    m:drawRectOutline(b.x, b.y, b.w, b.h, C.btnEdge, 2)
  end

  -- ===== TEXT LAYER =====
  m:clear()

  -- header (row 0): "REACTOR CONTROL" centered + AUTO/MAN badge right
  local title = "REACTOR  CONTROL"
  local pad = math.max(0, math.floor((cols - #title) / 2))
  m:setBackgroundColor(C.accent)
  tcell(m, pad, 0, TC.hi, title)
  local badge = state.manualOverride == nil and "[ AUTO ]" or "[ MAN ]"
  tcell(m, cols - #badge - 1, 0, TC.hi, badge)
  m:setBackgroundColor(C.bg)

  -- card labels (row 3 / 7 / 11) -- KPI label LEFT, big value RIGHT-of-label, contextual right side

  -- ENERGY card (top edge row 2, label row 3)
  tcell(m, 2, 3, TC.dim, "ENERGY")
  local _, _, etxt = pickFill(state.energyPct)
  tcell(m, 2, 4, etxt, string.format("%5.1f%%", state.energyPct))
  -- right-side extras
  local rateStr  = string.format("%s/t", fmtFE(state.energyProducedLastTick))
  local tgtStr   = string.format("target %d%%", CONFIG.pidTarget)
  tcell(m, cols - #rateStr - 2, 3, TC.dim, "RATE")
  tcell(m, cols - #rateStr - 2, 4, TC.hi,  rateStr)
  tcell(m, cols - #rateStr - #tgtStr - 8, 4, TC.dim, tgtStr)

  -- FUEL card (label row 7)
  tcell(m, 2, 7, TC.dim, "FUEL")
  local _, _, ftxt = pickFill(fuelPct)
  tcell(m, 2, 8, ftxt, string.format("%5.1f%%", fuelPct))
  local burnStr = string.format("burn %.2f mB/t", state.fuelConsumedLastTick)
  local wasteStr = string.format("waste %d", math.floor(state.wasteAmount))
  tcell(m, cols - #wasteStr - 2, 7, TC.dim, "WASTE")
  tcell(m, cols - #wasteStr - 2, 8, TC.hi,  wasteStr)
  tcell(m, cols - #wasteStr - #burnStr - 6, 8, TC.dim, burnStr)

  -- RODS card (label row 11)
  tcell(m, 2, 11, TC.dim, "RODS")
  tcell(m, 2, 12, TC.rod, string.format("%5.1f%%", state.rodLevel))
  local rodInfo = string.format("n=%d  PID out %d%%", state.rodCount, math.floor(state.pid.output + 0.5))
  tcell(m, cols - #rodInfo - 2, 12, TC.mid, rodInfo)

  -- TEMP strip (rows 15-17). label col1, T values then bars to right
  tcell(m, 2, 16, TC.dim, "TEMP")
  tcell(m, 2, 17, TC.warn, string.format("%4.0fK / %4.0fK", state.fuelTemperature, state.casingTemperature))
  -- two small column headers above bars
  tcell(m, 14, 16, TC.dim, "FUEL")
  tcell(m, 14 + math.floor(((cols-2-14)) / 2), 16, TC.dim, "CASE")

  -- STATUS BANNER (rows 19-20)
  local statusGlyph = state.active and "ACTIVE" or "STOPPED"
  local lineCol = state.active and TC.good or TC.bad
  if state.manualOverride ~= nil then lineCol = TC.warn end
  tcell(m, 3, 19, TC.dim, "STATUS")
  tcell(m, 11, 19, lineCol, statusGlyph)
  tcell(m, 22, 19, TC.hi, state.modeText)

  -- Buttons (label centered in each button)
  -- buttons start y=268 → row 22, label row ≈ row 23 (center of 32px button: y=284 → row 23.6)
  local btnRow = 23
  for _, b in ipairs(state.buttons) do
    -- center label horizontally in the button (col = b.x/12 + (bw-charW)/2)
    local lblCols = #b.label
    local startCol = math.floor(b.x / 12) + math.max(0, math.floor((b.w/12 - lblCols) / 2))
    tcell(m, startCol, btnRow, TC.hi, b.label)
  end

  -- footer (row 26): PID gains + controls hint
  local foot = string.format("PID Kp=%.1f Ki=%.2f Kd=%.0f  |  thresholds %d-%d%%  |  right-click buttons to act",
    CONFIG.pidKp, CONFIG.pidKi, CONFIG.pidKd, CONFIG.lowerHardPct, CONFIG.upperHardPct)
  tcell(m, 1, 26, TC.dim, foot:sub(1, cols - 2))

  m:setForegroundColor(TC.hi)  -- restore default
end

local function renderAll()
  for _, m in ipairs(state.monitors) do pcall(renderTo, m) end
end

local function hitButton(px, py)
  for _, b in ipairs(state.buttons) do
    if px >= b.x and px < b.x + b.w and py >= b.y and py < b.y + b.h then return b end
  end
end
local function handleTouch(px, py)
  local b = hitButton(px, py); if not b then return end
  if b.id == "toggle" then
    local cur = state.manualOverride; if cur == nil then cur = state.active end
    state.manualOverride = not cur
  elseif b.id == "auto" then state.manualOverride = nil
  elseif b.id == "tdn" then CONFIG.pidTarget = clamp(CONFIG.pidTarget - 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5)
  elseif b.id == "tup" then CONFIG.pidTarget = clamp(CONFIG.pidTarget + 5, CONFIG.lowerHardPct + 5, CONFIG.upperHardPct - 5)
  elseif b.id == "reset" then state.pid.integral = 0; state.pid.lastError = 0
  end
end

discover()
pcall(readState); pcall(tickControl); pcall(renderAll)
local timerId = os.startTimer(CONFIG.tickSec)
while true do
  local ev = { os.pullEvent() }
  if ev[1] == "timer" and ev[2] == timerId then
    pcall(readState); pcall(tickControl); pcall(renderAll)
    timerId = os.startTimer(CONFIG.tickSec)
  elseif ev[1] == "monitor_touch" then
    handleTouch(ev[4] or 0, ev[5] or 0)
    pcall(renderAll)
  end
end
