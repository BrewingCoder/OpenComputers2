-- ui_v1 -- retained-mode widget library for OC2 monitors (frozen API).
--
-- Script authors build dashboards as a tree of widget tables. The library
-- owns the render + event loop; scripts just describe their UI and bind
-- callbacks.
--
--   local ui = require("ui_v1")
--   local monitor = peripheral.find("monitor")
--   local title = ui.Label{ x=10, y=10, width=200, height=20,
--                           text="Hello!", color="hi", align="center" }
--   ui.mount(monitor, title)
--   ui.render()
--
-- This version is frozen at v1. Breaking changes go into a new module
-- (ui_v2) alongside, never replacing this file.
--
-- Library surface — rendering + geometry helpers live on the monitor
-- peripheral itself (getCellMetrics, snapCellRect, argb/lighten/dim,
-- drawText/fillText, drawSmallText). Widgets pass the monitor handle
-- down to their draw methods and call those directly.

local M = {}
M.VERSION = "v1"

-- ============================================================
-- Theme palette. Widgets can reference a color by token (`"good"`,
-- `"hi"`, `"warn"`, etc.) OR by raw ARGB integer (`0xFF2ECC71`).
-- Tokens make scripts survive theme changes without rewriting color
-- constants everywhere.
-- ============================================================

M.DEFAULT_THEME = {
    bg       = 0xFF0A0F1A,
    bgCard   = 0xFF121827,
    fg       = 0xFFFFFFFF,
    hi       = 0xFFE6F0FF,
    muted    = 0xFF7A8597,
    good     = 0xFF2ECC71,
    warn     = 0xFFF1C40F,
    bad      = 0xFFE74C3C,
    info     = 0xFF3498DB,
    edge     = 0xFF2E3A4E,
    primary  = 0xFF3498DB,
    ghost    = 0xFF2E3A4E,
    danger   = 0xFFE74C3C,
}

local _theme = M.DEFAULT_THEME

function M.setTheme(t) _theme = t or M.DEFAULT_THEME; M.invalidate() end
function M.getTheme() return _theme end

-- Resolve a color prop: nil → fallback; number → used as-is; string → theme
-- token lookup (fallback if unknown). Exported for test visibility.
local function resolveColor(v, fallback)
    if v == nil then return fallback end
    if type(v) == "number" then return v end
    if type(v) == "string" then return _theme[v] or fallback end
    return fallback
end
M._resolveColor = resolveColor

-- ============================================================
-- Per-monitor mount state. Weak-keyed so garbage-collecting the
-- monitor handle lets the library forget about it naturally.
-- ============================================================

local _monitors = setmetatable({}, { __mode = "k" })
local _dirty = true
-- Per-monitor flag: if true, the next Icon(shape="item") draw will call
-- monitor:clearIcons() exactly once before emitting drawItem calls. Set at the
-- top of M.render, cleared after the first item-icon fires in the pass.
M._iconClearPending = setmetatable({}, { __mode = "k" })
local _running = false

function M.invalidate() _dirty = true end
function M.isDirty() return _dirty end

-- ============================================================
-- Widget base. Each widget is a plain table with a metatable
-- pointing back at the class. Prop-bag style: unknown props are
-- ignored, so adding new fields in a future ui_v1 patch can't break
-- existing scripts.
-- ============================================================

local Widget = {}
Widget.__index = Widget

function Widget:set(props)
    if props then for k, v in pairs(props) do self[k] = v end end
    M.invalidate()
    return self
end

function Widget:get(name) return self[name] end

function Widget:hittest(px, py)
    if self.visible == false then return false end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    return px >= x and px < x + w and py >= y and py < y + h
end

-- ============================================================
-- Label -- text in a rect, optional bg fill, left/center/right align.
-- Non-interactive; no onClick.
-- ============================================================

local Label = setmetatable({}, { __index = Widget })
Label.__index = Label

function M.Label(props)
    local o = setmetatable({
        kind = "Label",
        x = 0, y = 0, width = 0, height = 0,
        text = "",
        color = "fg",
        bg = nil,
        align = "left",  -- "left" | "center" | "right"
        visible = true,
    }, Label)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

-- Hug-content size: one text row tall, #text cells wide. Used by parent
-- containers when a Label has no explicit width/height and no flex.
function Label:measure(monitor)
    local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
    local text = tostring(self.text or "")
    return #text * pxPerCol, pxPerRow
end

function Label:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0

    -- `h` is a HINT. snapCellRect returns the largest odd-count cell band
    -- that fits, centered on the user's midpoint, so bg + text co-register
    -- on the same cell grid. Without this the bg rect drifts from the text
    -- row whenever y isn't cell-aligned.
    local _, _, pxPerCol, _ = monitor:getCellMetrics()
    local snappedY, snappedH, textRow = monitor:snapCellRect(y, h)

    -- Optional bg rect (only if bg was set -- default Label bg is transparent).
    if self.bg ~= nil then
        local bg = resolveColor(self.bg, 0)
        if bg ~= 0 then monitor:drawRect(x, snappedY, w, snappedH, bg) end
    end

    local text = tostring(self.text or "")
    local textPx = #text * pxPerCol

    local textX = x
    if self.align == "center" then
        textX = x + math.floor((w - textPx) / 2)
    elseif self.align == "right" then
        textX = x + w - textPx
    end

    local textCol = math.max(0, math.floor(textX / pxPerCol))

    local fg = resolveColor(self.color, 0xFFFFFFFF)
    monitor:drawText(textCol, textRow, text, fg, 0)
end

-- ============================================================
-- Bar -- horizontal or vertical fill bar. Maps `value` from [min,max]
-- to a filled region of `color` over a `bg` track, with optional
-- `border` outline and `marker` line. `showPct=true` overlays a
-- centered "NN%" label in `fg`.
--
-- Vertical bars fill bottom-up (fuller = more coverage at the bottom),
-- matching player intuition for power/fuel/fluid levels.
-- ============================================================

local Bar = setmetatable({}, { __index = Widget })
Bar.__index = Bar

function M.Bar(props)
    local o = setmetatable({
        kind = "Bar",
        x = 0, y = 0, width = 0, height = 0,
        value = 0, min = 0, max = 100,
        color = "good",
        bg = "bgCard",
        border = "edge",
        marker = nil,         -- value in [min,max]; nil = no marker
        markerColor = "fg",
        orientation = "h",    -- "h" | "v"
        showPct = false,
        visible = true,
    }, Bar)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

local function _clamp01(v) if v < 0 then return 0 elseif v > 1 then return 1 else return v end end

function Bar:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    if w <= 0 or h <= 0 then return end

    local min = self.min or 0
    local max = self.max or 100
    local range = max - min
    if range <= 0 then range = 1 end
    local pct = _clamp01(((self.value or 0) - min) / range)

    -- Track bg. Always drawn so overwriting with a lower value shrinks the fill.
    local bgc = resolveColor(self.bg, 0)
    if bgc ~= 0 then monitor:drawRect(x, y, w, h, bgc) end

    -- Filled region
    local fillc = resolveColor(self.color, 0xFF2ECC71)
    if self.orientation == "v" then
        local fh = math.floor(h * pct)
        if fh > 0 then monitor:drawRect(x, y + h - fh, w, fh, fillc) end
    else
        local fw = math.floor(w * pct)
        if fw > 0 then monitor:drawRect(x, y, fw, h, fillc) end
    end

    -- Optional marker line
    if self.marker ~= nil then
        local mv = _clamp01((self.marker - min) / range)
        local mc = resolveColor(self.markerColor, 0xFFFFFFFF)
        if self.orientation == "v" then
            local my = y + h - math.floor(h * mv) - 1
            if my < y then my = y end
            if my >= y + h then my = y + h - 1 end
            monitor:drawLine(x, my, x + w - 1, my, mc)
        else
            local mx = x + math.floor(w * mv)
            if mx >= x + w then mx = x + w - 1 end
            if mx < x then mx = x end
            monitor:drawLine(mx, y, mx, y + h - 1, mc)
        end
    end

    -- Border (drawn last so fill + marker don't overdraw the outline).
    local bc = resolveColor(self.border, 0)
    if bc ~= 0 then monitor:drawRectOutline(x, y, w, h, bc, 1) end

    -- Percent overlay. Pixel-space glyph (5x7 font) so "42%" sits truly
    -- centered in the bar rect, independent of cell geometry. Engine-side
    -- drawSmallText handles the 5x7 font blit; only digits + '%' are
    -- supported, which matches the label format.
    if self.showPct then
        local label = string.format("%d%%", math.floor(pct * 100 + 0.5))
        local fg = resolveColor("fg", 0xFFFFFFFF)
        monitor:drawSmallText(math.floor(x + w / 2), math.floor(y + h / 2), label, fg)
    end
end

-- ============================================================
-- Divider -- thin line separator. Horizontal (default) or vertical.
-- Stroke is centered in the declared rect along the cross axis, so
-- a tall rect with orientation="h" still gives a mid-rect line.
-- ============================================================

local Divider = setmetatable({}, { __index = Widget })
Divider.__index = Divider

function M.Divider(props)
    local o = setmetatable({
        kind = "Divider",
        x = 0, y = 0, width = 0, height = 0,
        orientation = "h",  -- "h" | "v"
        color = "edge",
        thickness = 1,
        visible = true,
    }, Divider)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

function Divider:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    local t = self.thickness or 1
    if t < 1 then t = 1 end
    if w <= 0 or h <= 0 then return end
    local c = resolveColor(self.color, 0xFF2E3A4E)
    if self.orientation == "v" then
        local tx = x + math.floor((w - t) / 2)
        monitor:drawRect(tx, y, t, h, c)
    else
        local ty = y + math.floor((h - t) / 2)
        monitor:drawRect(x, ty, w, t, c)
    end
end

-- ============================================================
-- Indicator -- LED dot + optional label. Semantic state drives the
-- dot color via theme tokens ("on" = good, "off" = muted, etc.); an
-- explicit `color` prop overrides the mapping. Label renders in the
-- text cell grid, vertically centered in the rect, to the right of
-- the dot with a small gap.
-- ============================================================

local Indicator = setmetatable({}, { __index = Widget })
Indicator.__index = Indicator

local _IND_STATE_TOKEN = {
    on   = "good",
    warn = "warn",
    bad  = "bad",
    info = "info",
}

function M.Indicator(props)
    local o = setmetatable({
        kind = "Indicator",
        x = 0, y = 0, width = 0, height = 0,
        size = 8,              -- LED diameter in pixels
        state = "off",         -- "on" | "off" | "warn" | "bad" | "info"
        label = nil,           -- optional text drawn to right of LED
        color = nil,           -- optional override (token or ARGB int)
        offColor = "muted",
        labelColor = "fg",
        gap = 4,               -- px between LED and label
        visible = true,
    }, Indicator)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

-- Monitor pixels are non-square: 20 cols x 9 rows per block face, 12x12 px
-- per cell, so a vertical pixel is (20/9)x taller than a horizontal pixel is
-- wide. Stretch horizontal radius by this factor so the LED renders as a
-- round disc in world space instead of a tall skinny ellipse.
local _IND_ASPECT_NUM = 20
local _IND_ASPECT_DEN = 9

function Indicator:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local h = self.height or 0
    local size = self.size or 8
    if size < 2 then size = 2 end

    local ledColor
    if self.color ~= nil then
        ledColor = resolveColor(self.color, 0xFF2ECC71)
    else
        local token = _IND_STATE_TOKEN[self.state or "off"]
        if token then
            ledColor = resolveColor(token, 0xFF2ECC71)
        else
            ledColor = resolveColor(self.offColor or "muted", 0xFF7A8597)
        end
    end

    local ry = math.floor(size / 2)
    if ry < 1 then ry = 1 end
    local rx = math.floor(ry * _IND_ASPECT_NUM / _IND_ASPECT_DEN + 0.5)
    if rx < 1 then rx = 1 end

    local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()

    local hasLabel = self.label ~= nil and self.label ~= ""
    local cx, cy
    if hasLabel then
        -- Snap LED center to the cell that contains (x + rx, y + h/2). This
        -- keeps the LED-to-glyph gap constant across different x values
        -- (otherwise the floor/ceil of (x + 2*rx + gap) / pxPerCol makes the
        -- spacing alternate with x mod pxPerCol).
        local cellCol = math.floor((x + rx) / pxPerCol)
        local cellRow = math.floor((y + math.floor(h / 2)) / pxPerRow)
        cx = cellCol * pxPerCol + math.floor(pxPerCol / 2)
        cy = cellRow * pxPerRow + math.floor(pxPerRow / 2)
    else
        cx = x + rx
        cy = y + math.floor(h / 2)
    end

    monitor:fillEllipse(cx, cy, rx, ry, ledColor)

    if hasLabel then
        local gap = self.gap or 4
        local textX = cx + rx + gap
        local textCol = math.max(0, math.ceil(textX / pxPerCol))
        local textRow = math.max(0, math.floor((y + math.floor(h / 2)) / pxPerRow))

        local fg = resolveColor(self.labelColor or "fg", 0xFFFFFFFF)
        monitor:drawText(textCol, textRow, tostring(self.label), fg, 0)
    end
end

-- ============================================================
-- Gauge -- circular dial. Renders an annular arc ("donut wedge") with
-- a filled portion scaled to `value` within [min, max] over a `bg`
-- track, optional center label (arbitrary text, cell-aligned) or
-- `showValue=true` for a pixel-centered numeric readout. Defaults
-- describe a 270° speedometer sweep starting lower-left.
--
-- Angle convention (clock): 0° = up, 90° = right, 180° = down, 270° = left.
-- Sweep direction is clockwise. startDeg=225 + sweepDeg=270 gives the
-- typical dashboard dial with a 90° gap at the bottom.
--
-- Aspect: rx is stretched by 20/9 vs ry so the arc appears round in
-- world space, matching Indicator.
-- ============================================================

local Gauge = setmetatable({}, { __index = Widget })
Gauge.__index = Gauge

function M.Gauge(props)
    local o = setmetatable({
        kind = "Gauge",
        x = 0, y = 0, width = 0, height = 0,
        value = 0, min = 0, max = 100,
        color = "good",        -- filled-arc color
        bg = "bgCard",         -- unfilled-arc color (track under fill)
        thickness = 6,         -- ring stroke (Y-axis pixels)
        startDeg = 225,        -- clock angle of arc start (0=up, 90=right, ...)
        sweepDeg = 270,        -- total arc span in degrees (clockwise from start)
        label = nil,           -- center text; cell-aligned via drawText
        labelColor = "fg",
        showValue = false,     -- overlay floor(value) via drawSmallText (pixel-centered)
        visible = true,
    }, Gauge)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

function Gauge:draw(monitor)
    if self.visible == false then return end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    if w <= 0 or h <= 0 then return end

    local min = self.min or 0
    local max = self.max or 100
    local range = max - min
    if range <= 0 then range = 1 end
    local pct = _clamp01(((self.value or 0) - min) / range)

    -- Aspect-correct radii: keep the arc visually round.
    local maxRY = math.floor(h / 2) - 1
    local maxRX = math.floor(w / 2) - 1
    if maxRY < 3 then maxRY = 3 end
    if maxRX < 3 then maxRX = 3 end
    local ry = maxRY
    local rx = math.floor(ry * _IND_ASPECT_NUM / _IND_ASPECT_DEN + 0.5)
    if rx > maxRX then
        rx = maxRX
        ry = math.floor(rx * _IND_ASPECT_DEN / _IND_ASPECT_NUM + 0.5)
        if ry < 1 then ry = 1 end
    end

    local thickness = self.thickness or 6
    if thickness < 1 then thickness = 1 end
    if thickness > ry then thickness = ry end

    local cx = x + math.floor(w / 2)
    local cy = y + math.floor(h / 2)

    local startDeg = math.floor(self.startDeg or 225) % 360
    local sweepDeg = math.floor(self.sweepDeg or 270)
    if sweepDeg < 0 then sweepDeg = 0 end
    if sweepDeg > 360 then sweepDeg = 360 end
    local fillSweep = math.floor(sweepDeg * pct + 0.5)
    if fillSweep > sweepDeg then fillSweep = sweepDeg end

    local bgColor = resolveColor(self.bg, 0xFF121827)
    local fillColor = resolveColor(self.color, 0xFF2ECC71)
    if self.enabled == false then
        bgColor = monitor:dim(bgColor)
        fillColor = monitor:dim(fillColor)
    end

    -- Unfilled portion behind the fill so any clockwise aliasing at the
    -- boundary is overpainted by the fill pass.
    if sweepDeg - fillSweep > 0 then
        monitor:drawArc(cx, cy, rx, ry, thickness,
            (startDeg + fillSweep) % 360, sweepDeg - fillSweep, bgColor)
    end
    if fillSweep > 0 then
        monitor:drawArc(cx, cy, rx, ry, thickness, startDeg, fillSweep, fillColor)
    end

    -- Center overlay. showValue wins over label when both set, for the
    -- common "current reading" dashboard pattern. Both paths use the same
    -- cell-aligned drawText so numeric readouts match label crispness.
    local text = nil
    if self.showValue then
        text = string.format("%d", math.floor((self.value or 0) + 0.5))
    elseif self.label and self.label ~= "" then
        text = tostring(self.label)
    end
    if text then
        local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
        local textPx = #text * pxPerCol
        local startPx = cx - math.floor(textPx / 2)
        local textCol = math.max(0, math.floor(startPx / pxPerCol))
        local textRow = math.max(0, math.floor(cy / pxPerRow))
        local fg = resolveColor(self.labelColor or "fg", 0xFFFFFFFF)
        if self.enabled == false then fg = monitor:dim(fg) end
        monitor:drawText(textCol, textRow, text, fg, 0)
    end
end

-- ============================================================
-- Sparkline -- time-series line chart backed by a ring buffer. Samples
-- are pushed via :push(v); when length exceeds `capacity` the oldest
-- sample is dropped so recent history always fits the widget. Auto-
-- scales to the visible range by default; explicit `min`/`max` lock the
-- axis. Optional baseline, area fill under the line, and numeric readout
-- of the most recent sample.
--
-- API:
--   sp:push(v)       append a sample; rings off head when over capacity
--   sp:clear()       empty the ring
--   sp:setValues(a)  replace the series in one shot
-- ============================================================

local Sparkline = setmetatable({}, { __index = Widget })
Sparkline.__index = Sparkline

function M.Sparkline(props)
    local o = setmetatable({
        kind = "Sparkline",
        x = 0, y = 0, width = 0, height = 0,
        capacity = 64,
        min = nil, max = nil,       -- nil -> auto-scale
        color = "info",
        bg = "bgCard",
        border = "edge",
        baseline = nil,             -- value to draw horizontal reference at
        baselineColor = "muted",
        fill = false,               -- fill area between line and baseline/bottom
        fillColor = nil,            -- nil -> dim(color)
        showLast = false,
        lastColor = "fg",
        visible = true,
    }, Sparkline)
    if props then for k, v in pairs(props) do o[k] = v end end
    -- Defensive copy so caller-side mutation of the original array
    -- doesn't alias into the widget state.
    local copy = {}
    if props and props.values then
        for i, v in ipairs(props.values) do copy[i] = v end
    end
    o.values = copy
    return o
end

function Sparkline:push(v)
    if v == nil then return end
    local cap = self.capacity or 64
    if cap < 1 then cap = 1 end
    self.values[#self.values + 1] = v
    while #self.values > cap do
        table.remove(self.values, 1)
    end
end

function Sparkline:clear()
    self.values = {}
end

function Sparkline:setValues(arr)
    local copy = {}
    if arr then
        for i, v in ipairs(arr) do copy[i] = v end
    end
    self.values = copy
end

function Sparkline:draw(monitor)
    if self.visible == false then return end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    if w <= 0 or h <= 0 then return end

    local bgc = resolveColor(self.bg, 0)
    if bgc ~= 0 then monitor:drawRect(x, y, w, h, bgc) end

    local vals = self.values or {}
    local n = #vals

    local effMin = self.min
    local effMax = self.max
    if effMin == nil or effMax == nil then
        if n > 0 then
            local dmin, dmax = vals[1], vals[1]
            for i = 2, n do
                local v = vals[i]
                if v < dmin then dmin = v end
                if v > dmax then dmax = v end
            end
            if dmin == dmax then
                dmin = dmin - 0.5
                dmax = dmax + 0.5
            else
                local span = dmax - dmin
                dmin = dmin - span * 0.05
                dmax = dmax + span * 0.05
            end
            if effMin == nil then effMin = dmin end
            if effMax == nil then effMax = dmax end
        else
            effMin = effMin or 0
            effMax = effMax or 1
        end
    end
    local span = effMax - effMin
    if span <= 0 then span = 1 end

    local lineColor = resolveColor(self.color, 0xFF48C2FF)
    local fillC
    if self.fillColor ~= nil then
        fillC = resolveColor(self.fillColor, lineColor)
    else
        fillC = monitor:dim(lineColor)
    end
    if self.enabled == false then
        lineColor = monitor:dim(lineColor)
        fillC = monitor:dim(fillC)
    end

    local function xAt(i, count)
        if count <= 1 then return x + math.floor(w / 2) end
        return x + 1 + math.floor((i - 1) * (w - 3) / (count - 1) + 0.5)
    end
    local function yAt(v)
        local t = (v - effMin) / span
        if t < 0 then t = 0 elseif t > 1 then t = 1 end
        return y + h - 2 - math.floor(t * (h - 3) + 0.5)
    end

    if self.baseline ~= nil then
        local bcline = resolveColor(self.baselineColor or "muted", 0xFF7A8597)
        if self.enabled == false then bcline = monitor:dim(bcline) end
        local by = yAt(self.baseline)
        monitor:drawLine(x + 1, by, x + w - 2, by, bcline)
    end

    if n >= 1 then
        if self.fill then
            local baseY
            if self.baseline ~= nil then
                baseY = yAt(self.baseline)
            else
                baseY = y + h - 2
            end
            for i = 1, n do
                local xi = xAt(i, n)
                local yi = yAt(vals[i])
                local top, bot
                if yi <= baseY then top, bot = yi, baseY else top, bot = baseY, yi end
                monitor:drawLine(xi, top, xi, bot, fillC)
            end
        end
        if n >= 2 then
            for i = 1, n - 1 do
                local x1, y1 = xAt(i, n), yAt(vals[i])
                local x2, y2 = xAt(i + 1, n), yAt(vals[i + 1])
                monitor:drawLine(x1, y1, x2, y2, lineColor)
            end
        else
            monitor:setPixel(xAt(1, 1), yAt(vals[1]), lineColor)
        end
    end

    local bc = resolveColor(self.border, 0)
    if bc ~= 0 then monitor:drawRectOutline(x, y, w, h, bc, 1) end

    if self.showLast and n >= 1 then
        local txt = string.format("%d", math.floor(vals[n] + 0.5))
        local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
        local textPx = #txt * pxPerCol
        local startPx = x + w - 2 - textPx
        local textCol = math.max(0, math.floor(startPx / pxPerCol))
        local textRow = math.max(0, math.floor((y + 2) / pxPerRow))
        local fg = resolveColor(self.lastColor or "fg", 0xFFFFFFFF)
        if self.enabled == false then fg = monitor:dim(fg) end
        monitor:drawText(textCol, textRow, txt, fg, 0)
    end
end

-- ============================================================
-- Icon -- small pixel-art symbol for logos, status markers, dashboard
-- glyphs. `shape` picks the rendered form:
--   rect     -- filled rectangle (content box)
--   circle   -- filled ellipse, 20:9-aspect-stretched so it rounds in
--               world space (matches Indicator)
--   diamond  -- filled rhombus, scan-line rasterized
--   triangle -- upward isosceles triangle, scan-line rasterized
--   bits     -- custom bitmap: 2D array of 0/1 in `bits`, rows x cols,
--               each non-zero cell drawn as a filled rect scaled to fit
-- Optional `bg` fills the bounding rect first; optional `border` draws
-- an outline after the shape. Pure display, non-interactive.
-- ============================================================

local Icon = setmetatable({}, { __index = Widget })
Icon.__index = Icon

-- Same 20:9 aspect factor used by Indicator so circles look round.
local _ICON_ASPECT_NUM = 20
local _ICON_ASPECT_DEN = 9

function M.Icon(props)
    local o = setmetatable({
        kind = "Icon",
        x = 0, y = 0, width = 0, height = 0,
        shape = "rect",
        color = "info",
        bg = nil,
        border = nil,
        bits = nil,
        -- shape="item"     props: item     is a registry id like "minecraft:redstone"
        -- shape="fluid"    props: fluid    is a registry id like "minecraft:water"
        -- shape="chemical" props: chemical is a Mekanism chemical id like
        --                        "mekanism:hydrogen". Renders as a no-op when
        --                        Mekanism is absent.
        item = nil,
        fluid = nil,
        chemical = nil,
        visible = true,
    }, Icon)
    if props then for k, v in pairs(props) do o[k] = v end end
    if props and props.bits then
        local copy = {}
        for i, row in ipairs(props.bits) do
            local rowCopy = {}
            for j, v in ipairs(row) do rowCopy[j] = v end
            copy[i] = rowCopy
        end
        o.bits = copy
    end
    return o
end

function Icon:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    if w <= 0 or h <= 0 then return end

    local fg = resolveColor(self.color or "info", 0xFF4FA3FF)
    if self.enabled == false then fg = monitor:dim(fg) end

    if self.bg ~= nil then
        local bgc = resolveColor(self.bg, 0)
        if bgc ~= 0 then monitor:drawRect(x, y, w, h, bgc) end
    end

    local shape = self.shape or "rect"

    if shape == "rect" then
        monitor:drawRect(x, y, w, h, fg)
    elseif shape == "circle" then
        local cx = x + math.floor(w / 2)
        local cy = y + math.floor(h / 2)
        local ry = math.floor(h / 2)
        if ry < 1 then ry = 1 end
        local rx = math.floor(ry * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5)
        local maxRx = math.floor(w / 2)
        if rx > maxRx then rx = maxRx end
        if rx < 1 then rx = 1 end
        monitor:fillEllipse(cx, cy, rx, ry, fg)
    elseif shape == "diamond" then
        local cx = x + math.floor(w / 2)
        local hw = math.floor(w / 2)
        local hh = math.floor(h / 2)
        if hw < 1 then hw = 1 end
        if hh < 1 then hh = 1 end
        for row = 0, h - 1 do
            local dy = math.abs(row - hh)
            local ratio = 1 - dy / hh
            if ratio < 0 then ratio = 0 end
            local halfW = math.floor(hw * ratio + 0.5)
            monitor:drawLine(cx - halfW, y + row, cx + halfW, y + row, fg, 1)
        end
    elseif shape == "triangle" then
        local cx = x + math.floor(w / 2)
        local hw = math.floor(w / 2)
        for row = 0, h - 1 do
            local ratio = (h > 1) and (row / (h - 1)) or 0
            local halfW = math.floor(hw * ratio + 0.5)
            monitor:drawLine(cx - halfW, y + row, cx + halfW, y + row, fg, 1)
        end
    elseif shape == "bits" then
        if self.bits then
            local rows = #self.bits
            if rows > 0 then
                local cols = #self.bits[1]
                if cols > 0 then
                    local pxPerCol = math.max(1, math.floor(w / cols))
                    local pxPerRow = math.max(1, math.floor(h / rows))
                    local totalW = pxPerCol * cols
                    local totalH = pxPerRow * rows
                    local ox = x + math.floor((w - totalW) / 2)
                    local oy = y + math.floor((h - totalH) / 2)
                    for ri = 1, rows do
                        local rowBits = self.bits[ri]
                        for ci = 1, cols do
                            if rowBits[ci] and rowBits[ci] ~= 0 then
                                monitor:drawRect(
                                    ox + (ci - 1) * pxPerCol,
                                    oy + (ri - 1) * pxPerRow,
                                    pxPerCol, pxPerRow, fg)
                            end
                        end
                    end
                end
            end
        end
    elseif shape == "item" or shape == "fluid" or shape == "chemical" then
        local resId
        if shape == "fluid" then resId = self.fluid
        elseif shape == "chemical" then resId = self.chemical
        else resId = self.item end
        if resId and resId ~= "" then
            -- Lazy clear: fires once per render pass, first time any Icon:item/fluid/chemical draws.
            -- Prevents accumulation across frames; scripts with no icons pay nothing.
            if M._iconClearPending[monitor] then
                monitor:clearIcons()
                M._iconClearPending[monitor] = false
            end
            -- Aspect-correct the draw rect so it looks visually square on screen.
            -- Pixel-buffer pixels are 20:9 (W:H) per block — a square widget rect
            -- renders as a vertical stripe. Shrink the longer axis to compensate.
            local iw, ih = w, h
            if w * _ICON_ASPECT_DEN > h * _ICON_ASPECT_NUM then
                iw = math.floor(h * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5)
            else
                ih = math.floor(w * _ICON_ASPECT_DEN / _ICON_ASPECT_NUM + 0.5)
            end
            local ix = x + math.floor((w - iw) / 2)
            local iy = y + math.floor((h - ih) / 2)
            if shape == "fluid" then
                monitor:drawFluid(ix, iy, iw, ih, resId)
            elseif shape == "chemical" then
                monitor:drawChemical(ix, iy, iw, ih, resId)
            else
                monitor:drawItem(ix, iy, iw, ih, resId)
            end
        end
    end

    if self.border ~= nil then
        local bc = resolveColor(self.border, 0)
        if bc ~= 0 then monitor:drawRectOutline(x, y, w, h, bc, 1) end
    end
end

-- ============================================================
-- ItemSlot -- composite widget: resource texture + count label +
-- optional caption. Mimics an MC inventory slot: bordered square with
-- the texture centered, count in cell grid at bottom-right of icon,
-- caption drawn below the slot if provided.
--
-- Resource props (set exactly one):
--   item     -- item registry id like "minecraft:iron_ingot".
--   fluid    -- fluid registry id like "minecraft:water". Rendered as
--               the fluid's still-texture tinted by its client tint color.
--   chemical -- Mekanism chemical id like "mekanism:hydrogen". Rendered
--               as the chemical's icon sprite tinted by its color. Soft
--               dep: no-op when Mekanism is absent.
--
-- Display props:
--   count    -- number (auto-formatted: 9876 → "9.9k") or string. Nil
--               suppresses the count label entirely.
--   caption  -- optional secondary label drawn below the slot.
--   size     -- icon pixel size (square in visual space; aspect-corrected
--               internally). Defaults to 32.
--   bg, border -- slot chrome.
--   countColor, captionColor -- label color tokens.
-- ============================================================

local ItemSlot = setmetatable({}, { __index = Widget })
ItemSlot.__index = ItemSlot

function M.ItemSlot(props)
    local o = setmetatable({
        kind = "ItemSlot",
        x = 0, y = 0, width = 0, height = 0,
        item = nil,
        fluid = nil,
        chemical = nil,
        count = nil,
        caption = nil,
        size = 72,
        bg = "bgCard",
        border = "edge",
        countColor = "hi",
        captionColor = "muted",
        visible = true,
    }, ItemSlot)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

local function _fmtCount(c)
    if c == nil then return nil end
    if type(c) == "string" then return c end
    local n = tonumber(c) or 0
    if n >= 1e6 then return string.format("%.1fM", n / 1e6) end
    if n >= 1e4 then return string.format("%.1fk", n / 1e3) end
    return tostring(math.floor(n))
end

function ItemSlot:measure(monitor)
    local size = self.size or 64
    local w = (self.width or 0) > 0 and self.width or size
    local h = (self.height or 0) > 0 and self.height or size
    local hasCaption = self.caption ~= nil and self.caption ~= ""
    if hasCaption and monitor and type(monitor.getCellMetrics) == "function" then
        local _, _, _, pxPerRow = monitor:getCellMetrics()
        h = h + pxPerRow
    end
    return w, h
end

function ItemSlot:draw(monitor)
    if self.visible == false then return end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    if w <= 0 or h <= 0 then return end

    -- Slot chrome
    if self.bg ~= nil then
        local bgc = resolveColor(self.bg, 0)
        if bgc ~= 0 then monitor:drawRect(x, y, w, h, bgc) end
    end

    local caption = self.caption
    local hasCaption = caption ~= nil and caption ~= ""
    local captionRows = hasCaption and 1 or 0
    local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
    local captionPxH = captionRows * pxPerRow

    -- Cell-grid snap: first/last cells fully inside the slot's pixel bounds.
    -- Text drawn in cells outside this range would straddle the slot border.
    local firstCol = math.ceil(x / pxPerCol)
    local lastCol = math.floor((x + w) / pxPerCol) - 1
    local cols = math.max(0, lastCol - firstCol + 1)

    -- Icon rect: inset from slot edges so textures don't clip the border.
    local ICON_INSET = 2
    local ix0 = x + ICON_INSET
    local iy0 = y + ICON_INSET
    local iw0 = math.max(0, w - 2 * ICON_INSET)
    local ih0 = math.max(0, h - 2 * ICON_INSET)
    local iconAreaH = math.max(0, ih0 - captionPxH)
    local iconBoxW = math.min(iw0, self.size or 64)
    local iconBoxH = math.min(iconAreaH, self.size or 64)
    if iconBoxW > 0 and iconBoxH > 0 then
        local iw, ih = iconBoxW, iconBoxH
        if iw * _ICON_ASPECT_DEN > ih * _ICON_ASPECT_NUM then
            iw = math.floor(ih * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5)
        else
            ih = math.floor(iw * _ICON_ASPECT_DEN / _ICON_ASPECT_NUM + 0.5)
        end
        local ix = ix0 + math.floor((iw0 - iw) / 2)
        local iy = iy0 + math.floor((iconAreaH - ih) / 2)

        if self.item and self.item ~= "" then
            if M._iconClearPending[monitor] then
                monitor:clearIcons()
                M._iconClearPending[monitor] = false
            end
            monitor:drawItem(ix, iy, iw, ih, self.item)
        elseif self.fluid and self.fluid ~= "" then
            if M._iconClearPending[monitor] then
                monitor:clearIcons()
                M._iconClearPending[monitor] = false
            end
            monitor:drawFluid(ix, iy, iw, ih, self.fluid)
        elseif self.chemical and self.chemical ~= "" then
            if M._iconClearPending[monitor] then
                monitor:clearIcons()
                M._iconClearPending[monitor] = false
            end
            monitor:drawChemical(ix, iy, iw, ih, self.chemical)
        end

        -- Count: centered in the first full text row below the icon rect.
        -- Icon pass renders AFTER glyphs, so inside-icon rows get overdrawn.
        local cntText = _fmtCount(self.count)
        if cntText ~= nil and cntText ~= "" then
            if #cntText > cols then cntText = string.sub(cntText, 1, cols) end
            if cntText ~= "" then
                local cntFg = resolveColor(self.countColor or "hi", 0xFFFFFFFF)
                local firstRow = math.ceil(y / pxPerRow)
                local lastRow = math.max(firstRow,
                    math.floor((y + h) / pxPerRow) - 1)
                if hasCaption then
                    lastRow = math.max(firstRow, lastRow - 1)
                end
                local countRow = math.ceil((iy + ih) / pxPerRow)
                countRow = math.max(firstRow, math.min(countRow, lastRow))
                local startCol = firstCol + math.max(0,
                    math.floor((cols - #cntText) / 2))
                monitor:drawText(startCol, countRow, cntText, cntFg, 0)
            end
        end
    end

    -- Caption: centered in the bottom cell row, clipped to the cell-snapped
    -- slot width so glyphs can never straddle the border.
    if hasCaption then
        local capFg = resolveColor(self.captionColor or "muted", 0xFFB8C2D0)
        local capRow = math.floor((y + h - captionPxH) / pxPerRow)
        local capText = caption
        if #capText > cols then capText = string.sub(capText, 1, cols) end
        if capText ~= "" then
            local startCol = firstCol + math.max(0,
                math.floor((cols - #capText) / 2))
            monitor:drawText(startCol, capRow, capText, capFg, 0)
        end
    end

    if self.border ~= nil then
        local bc = resolveColor(self.border, 0)
        if bc ~= 0 then monitor:drawRectOutline(x, y, w, h, bc, 1) end
    end
end

-- ============================================================
-- Banner -- horizontal status strip with a colored left-edge accent.
-- Semantic `style` drives the accent via theme tokens (good/warn/bad/
-- info/none); explicit `color` wins. Background fills the whole strip,
-- text draws to the right of the accent, vertically centered in the
-- rect. Pure display; non-interactive.
-- ============================================================

local Banner = setmetatable({}, { __index = Widget })
Banner.__index = Banner

local _BANNER_STATE_TOKEN = {
    good = "good",
    warn = "warn",
    bad  = "bad",
    info = "info",
    none = "edge",
}

function M.Banner(props)
    local o = setmetatable({
        kind = "Banner",
        x = 0, y = 0, width = 0, height = 0,
        text = "",
        style = "info",
        color = nil,            -- ARGB or token; overrides style-token accent
        textColor = "fg",
        bg = "bgCard",
        edgeAccent = 4,         -- left-edge accent width in pixels
        padding = 4,            -- px gap between accent and text
        align = "left",         -- "left" | "center" | "right"
        visible = true,
    }, Banner)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

function Banner:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    if w <= 0 or h <= 0 then return end

    local _, _, pxPerCol, _ = monitor:getCellMetrics()
    -- User's h is a HINT — snapCellRect returns a cell-aligned band so text
    -- lands pixel-centered in its middle cell. See engine docs.
    local snappedY, snappedH, textRow = monitor:snapCellRect(y, h)

    -- Background (snapped to cell-aligned rect)
    local bg = resolveColor(self.bg, 0xFF121827)
    if bg ~= 0 then monitor:drawRect(x, snappedY, w, snappedH, bg) end

    -- Accent color: explicit color wins; else style token; else edge.
    local accentColor
    if self.color ~= nil then
        accentColor = resolveColor(self.color, 0xFF3498DB)
    else
        local token = _BANNER_STATE_TOKEN[self.style or "info"]
        accentColor = resolveColor(token or "edge", 0xFF2E3A4E)
    end

    local edge = self.edgeAccent or 4
    if edge < 0 then edge = 0 end
    if edge > w then edge = w end
    if edge > 0 then monitor:drawRect(x, snappedY, edge, snappedH, accentColor) end

    local text = tostring(self.text or "")
    if text == "" then return end

    local padding = self.padding or 4
    local textLeftPx = x + edge + padding
    local textRightPx = x + w - padding
    local textPx = #text * pxPerCol

    local startPx
    if self.align == "center" then
        startPx = textLeftPx + math.floor(((textRightPx - textLeftPx) - textPx) / 2)
    elseif self.align == "right" then
        startPx = textRightPx - textPx
    else
        startPx = textLeftPx
    end
    if startPx < textLeftPx then startPx = textLeftPx end

    -- ceil so the first glyph sits strictly to the right of accent+padding.
    local textCol = math.max(0, math.ceil(startPx / pxPerCol))

    local fg = resolveColor(self.textColor or "fg", 0xFFFFFFFF)
    monitor:drawText(textCol, textRow, text, fg, 0)
end

-- ============================================================
-- Button -- clickable surface with a centered label. Bg + top-half
-- gradient bevel + 2px border + centered text. `enabled=false` dims
-- every color 50% AND is filtered by the event dispatcher so
-- onClick won't fire.
--
-- Styles map to theme tokens: `primary` -> primary (blue), `ghost`
-- -> ghost (muted blue-grey), `danger` -> danger (red). `color`
-- overrides the style's base color. Border color is auto-derived as
-- a lightened shade of the base unless `borderColor` is set.
--
-- Like Banner, the button's vertical bounds snap to the largest odd
-- cell count that fits within `height` so the label is pixel-centered
-- inside the visible rect. User-requested `height` is a hint.
-- ============================================================

local _BUTTON_STYLE = {
    primary = "primary",
    ghost   = "ghost",
    danger  = "danger",
}

local Button = setmetatable({}, { __index = Widget })
Button.__index = Button

function M.Button(props)
    local o = setmetatable({
        kind = "Button",
        x = 0, y = 0, width = 0, height = 0,
        label = "",
        onClick = nil,
        style = "primary",          -- "primary" | "ghost" | "danger"
        enabled = true,
        visible = true,
        color = nil,                -- ARGB/token; overrides style base
        textColor = "fg",
        borderColor = nil,          -- nil = auto (lightened base)
        borderThickness = 2,
    }, Button)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

function Button:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    if w <= 0 or h <= 0 then return end

    local cols, _, pxPerCol, _ = monitor:getCellMetrics()
    local snappedY, snappedH, textRow = monitor:snapCellRect(y, h)

    local baseColor
    if self.color ~= nil then
        baseColor = resolveColor(self.color, 0xFF3498DB)
    else
        local token = _BUTTON_STYLE[self.style or "primary"] or "primary"
        baseColor = resolveColor(token, 0xFF3498DB)
    end

    local borderColor
    if self.borderColor ~= nil then
        borderColor = resolveColor(self.borderColor, monitor:lighten(baseColor, 40))
    else
        borderColor = monitor:lighten(baseColor, 40)
    end

    local textColor = resolveColor(self.textColor or "fg", 0xFFFFFFFF)

    if self.enabled == false then
        baseColor = monitor:dim(baseColor)
        borderColor = monitor:dim(borderColor)
        textColor = monitor:dim(textColor)
    end

    -- Base fill
    monitor:drawRect(x, snappedY, w, snappedH, baseColor)

    -- Top-half gradient: subtle bevel highlight (lighter at top).
    local topHalf = math.floor(snappedH / 2)
    if topHalf > 0 then
        monitor:drawGradientV(x, snappedY, w, topHalf, monitor:lighten(baseColor, 30), baseColor)
    end

    -- Border
    local thickness = self.borderThickness or 2
    if thickness < 0 then thickness = 0 end
    if thickness > 0 then
        monitor:drawRectOutline(x, snappedY, w, snappedH, borderColor, thickness)
    end

    -- Text-cell cleanup: the text grid persists across redraws, so shrinking
    -- the label (e.g. "POWER: OFF" -> "POWER: ON") would leave stale trailing
    -- glyphs. Blank the full button cell-band at textRow first.
    local leftCol = math.max(0, math.floor(x / pxPerCol))
    local rightCol = math.min(cols - 1, math.floor((x + w - 1) / pxPerCol))
    local bandCells = rightCol - leftCol + 1
    if bandCells > 0 then
        monitor:fillText(leftCol, textRow, bandCells, " ", 0, 0)
    end

    -- Centered label
    local label = tostring(self.label or "")
    if label == "" then return end
    local textPx = #label * pxPerCol
    local startPx = x + math.floor((w - textPx) / 2)
    if startPx < x then startPx = x end
    local textCol = math.max(0, math.floor(startPx / pxPerCol))
    monitor:drawText(textCol, textRow, label, textColor, 0)
end

-- ============================================================
-- Toggle -- boolean switch rendered as a Button whose color + label
-- reflect `value`. Clicking flips the value and fires `onChange`.
--
-- Props:
--   value       boolean (default false)
--   label       optional prefix, e.g. "POWER" -> "POWER: ON" / "POWER: OFF"
--   onLabel     label when value=true  (default "ON")
--   offLabel    label when value=false (default "OFF")
--   onColor     theme token / ARGB when ON  (default "good")
--   offColor    theme token / ARGB when OFF (default "ghost")
--   onChange    function(newValue, event) fired AFTER the flip
--
-- Toggle owns its own `onClick` and sets it after the user's props are
-- merged, so `onClick` in props is silently ignored. Observers should
-- use `onChange`.
-- ============================================================

local Toggle = setmetatable({}, { __index = Widget })
Toggle.__index = Toggle

function M.Toggle(props)
    local o = setmetatable({
        kind = "Toggle",
        x = 0, y = 0, width = 0, height = 0,
        value = false,
        label = nil,
        onLabel = "ON",
        offLabel = "OFF",
        onColor = "good",
        offColor = "ghost",
        textColor = "fg",
        onChange = nil,
        enabled = true,
        visible = true,
        borderThickness = 2,
    }, Toggle)
    if props then for k, v in pairs(props) do o[k] = v end end
    -- Authoritative click handler: flips value, invalidates, fires onChange.
    o.onClick = function(e)
        if o.enabled == false then return end
        o.value = not o.value
        M.invalidate()
        if type(o.onChange) == "function" then
            local ok, err = pcall(o.onChange, o.value, e)
            if not ok then print("[ui] Toggle onChange error: " .. tostring(err)) end
        end
    end
    return o
end

function Toggle:_stateLabel()
    local base = self.value and (self.onLabel or "ON") or (self.offLabel or "OFF")
    if self.label and self.label ~= "" then
        return tostring(self.label) .. ": " .. base
    end
    return base
end

function Toggle:draw(monitor)
    if self.visible == false then return end
    local x = self.x or 0
    local y = self.y or 0
    local w = self.width or 0
    local h = self.height or 0
    if w <= 0 or h <= 0 then return end

    local cols, _, pxPerCol, _ = monitor:getCellMetrics()
    local snappedY, snappedH, textRow = monitor:snapCellRect(y, h)

    local colorToken = self.value and (self.onColor or "good") or (self.offColor or "ghost")
    local baseColor = resolveColor(colorToken, self.value and 0xFF2ECC71 or 0xFF2E3A4E)
    local borderColor = monitor:lighten(baseColor, 40)
    local textColor = resolveColor(self.textColor or "fg", 0xFFFFFFFF)

    if self.enabled == false then
        baseColor = monitor:dim(baseColor)
        borderColor = monitor:dim(borderColor)
        textColor = monitor:dim(textColor)
    end

    monitor:drawRect(x, snappedY, w, snappedH, baseColor)
    local topHalf = math.floor(snappedH / 2)
    if topHalf > 0 then
        monitor:drawGradientV(x, snappedY, w, topHalf, monitor:lighten(baseColor, 30), baseColor)
    end
    local thickness = self.borderThickness or 2
    if thickness > 0 then
        monitor:drawRectOutline(x, snappedY, w, snappedH, borderColor, thickness)
    end

    local leftCol = math.max(0, math.floor(x / pxPerCol))
    local rightCol = math.min(cols - 1, math.floor((x + w - 1) / pxPerCol))
    local bandCells = rightCol - leftCol + 1
    if bandCells > 0 then
        monitor:fillText(leftCol, textRow, bandCells, " ", 0, 0)
    end

    local label = self:_stateLabel()
    if label == "" then return end
    local textPx = #label * pxPerCol
    local startPx = x + math.floor((w - textPx) / 2)
    if startPx < x then startPx = x end
    local textCol = math.max(0, math.floor(startPx / pxPerCol))
    monitor:drawText(textCol, textRow, label, textColor, 0)
end

-- ============================================================
-- Spacer -- eats remaining space on the container's main axis. No
-- draw chrome. Default flex = 1. Use `Spacer{ flex = 2 }` to claim
-- a larger share of the remainder.
-- ============================================================

local Spacer = setmetatable({}, { __index = Widget })
Spacer.__index = Spacer

function M.Spacer(props)
    local o = setmetatable({
        kind = "Spacer",
        x = 0, y = 0, width = 0, height = 0,
        flex = 1,
        visible = true,
    }, Spacer)
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

-- Spacer draws nothing; layout uses it purely to claim flex space.
function Spacer:draw(monitor) end

-- ============================================================
-- Container base -- shared layout + draw walkers for VBox, HBox, Card.
--
-- Layout algorithm (one-pass, Flutter-style):
--   1. Compute the container's inner rect (outer rect minus padding).
--   2. For each child: if `flex` is set and > 0, remember it for pass 2.
--      Otherwise treat its current width/height as a fixed claim along the
--      main axis.
--   3. Distribute the remainder across flex children proportionally to
--      their flex weights. Non-flex children keep their explicit size.
--   4. Assign (x, y, width, height) to each child and recurse into
--      containers (layout() is idempotent, so repeat layout calls are safe).
--
-- Cross-axis: non-flex children get their declared cross-axis size if set,
-- else they stretch to fill the inner cross extent. This matches the
-- common dashboard case (stacked cards, each spanning the container width).
-- ============================================================

local Container = setmetatable({}, { __index = Widget })
Container.__index = Container

local function _containerDefaults(kind, props)
    local o = {
        kind = kind,
        x = 0, y = 0, width = 0, height = 0,
        padding = 0,
        gap = 0,
        children = {},
        visible = true,
    }
    if props then for k, v in pairs(props) do o[k] = v end end
    return o
end

-- Cards must be cell-grid-aligned so their contained Labels sit symmetrically:
-- cells are a fixed 12-px unit, and drawText positions text at cell boundaries,
-- so a Card whose (width - 2*pad) or (height - 2*pad) doesn't match its
-- content's cell parity will render the label closer to one edge than the
-- other. Grow the Card just enough to match parity.
local function _cardAlignedSize(contentPx, pad, unit)
    local contentCells = math.max(1, math.ceil(contentPx / unit))
    local desired = contentPx + 2 * pad
    local totalCells = math.max(contentCells, math.ceil(desired / unit))
    if (totalCells - contentCells) % 2 ~= 0 then totalCells = totalCells + 1 end
    return totalCells * unit
end

-- Round y up to the next cell boundary. Used to cell-align Card positions
-- after a non-aligned parent container hands them an off-grid y.
local function _snapUp(v, unit)
    local rem = v % unit
    if rem == 0 then return v end
    return v + (unit - rem)
end

-- VBox children stack along Y; HBox children stack along X; Card is a VBox
-- with extra chrome (bg rect + border). Orientation is derived from `kind`
-- so all three share one layout impl.
local function _mainAxis(kind)
    if kind == "HBox" then return "h" else return "v" end  -- VBox + Card both vertical
end

-- Hug-content size: sum of visible children on the main axis (plus gaps +
-- padding), max on the cross axis. Recurses into child containers via
-- their own measure(). Children without measure() fall back to explicit
-- width/height (0 if unset — matches pre-hug layout).
function Container:measure(monitor)
    local axis = _mainAxis(self.kind)
    local pad = self.padding or 0
    local gap = self.gap or 0
    local mainSum = 0
    local crossMax = 0
    local visibleCount = 0
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false then
            visibleCount = visibleCount + 1
            local dw, dh
            if type(c.measure) == "function" then
                dw, dh = c:measure(monitor)
            else
                dw = c.width or 0
                dh = c.height or 0
            end
            if axis == "v" then
                if dw > crossMax then crossMax = dw end
                mainSum = mainSum + dh
            else
                if dh > crossMax then crossMax = dh end
                mainSum = mainSum + dw
            end
        end
    end
    local gaps = gap * math.max(0, visibleCount - 1)
    -- Cross-axis "0" is a stretch signal: if no child declared any cross-axis
    -- size, we have nothing to hug to, so report 0 and let the parent stretch
    -- us to its inner extent. Main axis always reports hug-sum (content-driven).
    local w, h
    if axis == "v" then
        w = crossMax > 0 and (crossMax + 2 * pad) or 0
        h = mainSum + gaps + 2 * pad
    else
        w = mainSum + gaps + 2 * pad
        h = crossMax > 0 and (crossMax + 2 * pad) or 0
    end
    if self.kind == "Card" and monitor and type(monitor.getCellMetrics) == "function" then
        local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
        local contentW = (axis == "v") and crossMax or (mainSum + gaps)
        local contentH = (axis == "v") and (mainSum + gaps) or crossMax
        w = contentW > 0 and _cardAlignedSize(contentW, pad, pxPerCol) or 0
        h = contentH > 0 and _cardAlignedSize(contentH, pad, pxPerRow) or 0
    end
    return w, h
end

function Container:layout(monitor)
    if self.visible == false then return end
    local axis = _mainAxis(self.kind)
    local pad = self.padding or 0
    local gap = self.gap or 0

    local innerX = (self.x or 0) + pad
    local innerY = (self.y or 0) + pad
    local innerW = math.max(0, (self.width or 0) - 2 * pad)
    local innerH = math.max(0, (self.height or 0) - 2 * pad)

    local children = self.children or {}
    local visibleCount = 0
    for _, c in ipairs(children) do
        if c.visible ~= false then visibleCount = visibleCount + 1 end
    end
    local gaps = gap * math.max(0, visibleCount - 1)

    -- Pass 1: tally flex + fixed claims on the main axis. Hug-content rule:
    -- if a child has no flex and no explicit main-axis size, ask it to
    -- measure itself. That lets `Card{ children={Label{text="Hi"}} }` size
    -- to the label instead of collapsing to 0.
    local totalFixed = 0
    local totalFlex = 0
    for _, c in ipairs(children) do
        if c.visible ~= false then
            local flex = c.flex or 0
            if flex > 0 then
                totalFlex = totalFlex + flex
            else
                local explicit
                if axis == "v" then explicit = c.height or 0
                else explicit = c.width or 0 end
                if explicit <= 0 and monitor and type(c.measure) == "function" then
                    local dw, dh = c:measure(monitor)
                    if axis == "v" then c.height = dh; explicit = dh
                    else c.width = dw; explicit = dw end
                end
                totalFixed = totalFixed + explicit
            end
        end
    end

    local mainExtent = axis == "v" and innerH or innerW
    local remainder = math.max(0, mainExtent - totalFixed - gaps)

    -- Pass 2: assign rects + recurse into children that also lay out.
    local cursor = axis == "v" and innerY or innerX
    for _, c in ipairs(children) do
        if c.visible ~= false then
            local flex = c.flex or 0
            local mainSize
            if flex > 0 then
                mainSize = totalFlex > 0 and math.floor(remainder * flex / totalFlex) or 0
            else
                mainSize = axis == "v" and (c.height or 0) or (c.width or 0)
            end

            if axis == "v" then
                c.x = innerX
                c.y = cursor
                -- Cross-axis: honor explicit width > measured width > stretch.
                -- A measure of 0 means "no content-hug preference" -- stretch
                -- to the parent's innerW. This lets a VBox of measure-less
                -- widgets (e.g. Toggles) inherit the parent width down the
                -- tree instead of collapsing to 0.
                if (c.width or 0) <= 0 then
                    local dw = 0
                    if monitor and type(c.measure) == "function" then
                        dw = c:measure(monitor) or 0
                    end
                    c.width = dw > 0 and dw or innerW
                end
                c.height = mainSize
            else
                c.x = cursor
                c.y = innerY
                c.width = mainSize
                if (c.height or 0) <= 0 then
                    local dh = 0
                    if monitor and type(c.measure) == "function" then
                        local _mw
                        _mw, dh = c:measure(monitor)
                        dh = dh or 0
                    end
                    c.height = dh > 0 and dh or innerH
                end
            end

            if type(c.layout) == "function" then c:layout(monitor) end
            cursor = cursor + mainSize + gap
        end
    end
end

function Container:draw(monitor)
    if self.visible == false then return end
    -- Card-only chrome: bg rect + optional border. VBox/HBox are invisible
    -- grouping shells unless the user sets `bg` explicitly.
    if self.kind == "Card" or self.bg ~= nil or self.border ~= nil then
        local bg = resolveColor(self.bg, (self.kind == "Card" and 0xFF121827) or 0)
        if bg ~= 0 then monitor:drawRect(self.x, self.y, self.width, self.height, bg) end
        local border = self.border
        if border == nil and self.kind == "Card" then border = "edge" end
        if border ~= nil then
            local bc = resolveColor(border, 0)
            if bc ~= 0 then monitor:drawRectOutline(self.x, self.y, self.width, self.height, bc, 1) end
        end
    end
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false and type(c.draw) == "function" then
            c:draw(monitor)
        end
    end
end

-- Containers hit-test via tree walk; see findLeafAt below.
function Container:hittest(px, py)
    if self.visible == false then return false end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    return px >= x and px < x + w and py >= y and py < y + h
end

function M.VBox(props)
    return setmetatable(_containerDefaults("VBox", props), Container)
end

function M.HBox(props)
    return setmetatable(_containerDefaults("HBox", props), Container)
end

function M.Card(props)
    -- Card-specific defaults applied before the user's props so the user
    -- can still override any of them -- including setting padding=0 or
    -- border=0 explicitly. Mirrors the JS side's merge-before-override.
    local merged = { padding = 4, bg = "bgCard", border = "edge" }
    if props then for k, v in pairs(props) do merged[k] = v end end
    return setmetatable(_containerDefaults("Card", merged), Container)
end

-- ============================================================
-- Stack: overlay container. Every child fills the Stack's inner rect;
-- children are drawn back-to-front (first in list = behind, last = on
-- top). Hit-test walks front-to-back so the topmost hit wins. Useful
-- for layering a Banner over a Card, a tint over an Icon, or a Label
-- over a Bar. `flex` is ignored; children always receive the full
-- inner rect.
-- ============================================================

local Stack = setmetatable({}, { __index = Widget })
Stack.__index = Stack

function Stack:measure(monitor)
    local pad = self.padding or 0
    local mw, mh = 0, 0
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false then
            local dw, dh
            if type(c.measure) == "function" then
                dw, dh = c:measure(monitor)
            else
                dw, dh = c.width or 0, c.height or 0
            end
            if dw > mw then mw = dw end
            if dh > mh then mh = dh end
        end
    end
    local w = mw > 0 and (mw + 2 * pad) or 0
    local h = mh > 0 and (mh + 2 * pad) or 0
    return w, h
end

function Stack:layout(monitor)
    if self.visible == false then return end
    local pad = self.padding or 0
    local innerX = (self.x or 0) + pad
    local innerY = (self.y or 0) + pad
    local innerW = math.max(0, (self.width or 0) - 2 * pad)
    local innerH = math.max(0, (self.height or 0) - 2 * pad)
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false then
            c.x, c.y = innerX, innerY
            c.width, c.height = innerW, innerH
            if type(c.layout) == "function" then c:layout(monitor) end
        end
    end
end

function Stack:draw(monitor)
    if self.visible == false then return end
    -- Stack chrome: same bg/border rules as VBox/HBox -- invisible unless
    -- user opts in. Children draw on top.
    if self.bg ~= nil or self.border ~= nil then
        local bg = resolveColor(self.bg, 0)
        if bg ~= 0 then monitor:drawRect(self.x, self.y, self.width, self.height, bg) end
        if self.border ~= nil then
            local bc = resolveColor(self.border, 0)
            if bc ~= 0 then monitor:drawRectOutline(self.x, self.y, self.width, self.height, bc, 1) end
        end
    end
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false and type(c.draw) == "function" then
            c:draw(monitor)
        end
    end
end

function Stack:hittest(px, py)
    if self.visible == false then return false end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    return px >= x and px < x + w and py >= y and py < y + h
end

function M.Stack(props)
    local o = {
        kind = "Stack",
        x = 0, y = 0, width = 0, height = 0,
        padding = 0,
        children = {},
        visible = true,
    }
    if props then for k, v in pairs(props) do o[k] = v end end
    return setmetatable(o, Stack)
end

-- ============================================================
-- Flow: responsive row-wrap container. Packs children left-to-right
-- into rows, wrapping to the next row whenever the cursor + next
-- child would exceed the container's inner width. Row height equals
-- the tallest child in that row; the Flow grows its own height to
-- cover every row. `flex` is ignored (wrap semantics don't mix with
-- flex-grow — use Spacer or explicit widths instead).
-- ============================================================

local Flow = setmetatable({}, { __index = Widget })
Flow.__index = Flow

-- Measure a flow as if everything fit on one row. The real layout
-- happens in Flow:layout() where the container's assigned width is
-- known. Parents that want hug-width get the one-row estimate; Flows
-- narrower than that wrap on layout.
function Flow:measure(monitor)
    local pad = self.padding or 0
    local gap = self.gap or 0
    local sum, maxH, count = 0, 0, 0
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false then
            count = count + 1
            local dw, dh
            if type(c.measure) == "function" then
                dw, dh = c:measure(monitor)
            else
                dw = c.width or 0
                dh = c.height or 0
            end
            sum = sum + dw
            if dh > maxH then maxH = dh end
        end
    end
    local gaps = gap * math.max(0, count - 1)
    return sum + gaps + 2 * pad, maxH + 2 * pad
end

function Flow:layout(monitor)
    if self.visible == false then return end
    local pad = self.padding or 0
    local gap = self.gap or 0
    local innerX = (self.x or 0) + pad
    local innerY = (self.y or 0) + pad
    local innerW = math.max(0, (self.width or 0) - 2 * pad)

    -- Cell-align padding and gap when any child is a Card, so the row/column
    -- cursor stays on the cell grid and Cards never need their own position
    -- fudged. Pure VBox-of-VBox trees don't pay this cost.
    local hasCard = false
    for _, c in ipairs(self.children or {}) do
        if c.kind == "Card" then hasCard = true; break end
    end
    if hasCard and monitor and type(monitor.getCellMetrics) == "function" then
        local _, _, pxPerCol, pxPerRow = monitor:getCellMetrics()
        innerX = _snapUp(innerX, pxPerCol)
        innerY = _snapUp(innerY, pxPerRow)
        if gap > 0 then
            -- Snap gap up to cell-multiple so row/col transitions stay aligned.
            gap = _snapUp(gap, math.min(pxPerCol, pxPerRow))
        end
    end

    -- Pack children into rows. A row is { items = {...}, width, height }.
    local rows = {}
    local row = { items = {}, width = 0, height = 0 }
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false then
            local dw, dh
            if type(c.measure) == "function" then
                dw, dh = c:measure(monitor)
            else
                dw = c.width or 0
                dh = c.height or 0
            end
            local prospective = row.width + (#row.items > 0 and gap or 0) + dw
            if #row.items > 0 and prospective > innerW then
                rows[#rows + 1] = row
                row = { items = {}, width = 0, height = 0 }
                prospective = dw
            end
            row.items[#row.items + 1] = { child = c, w = dw, h = dh }
            row.width = prospective
            if dh > row.height then row.height = dh end
        end
    end
    if #row.items > 0 then rows[#rows + 1] = row end

    -- Position children, track total height used.
    local cursorY = innerY
    for ri, r in ipairs(rows) do
        local cursorX = innerX
        for _, entry in ipairs(r.items) do
            local c = entry.child
            c.x = cursorX
            c.y = cursorY
            c.width = entry.w
            c.height = entry.h
            if type(c.layout) == "function" then c:layout(monitor) end
            cursorX = cursorX + entry.w + gap
        end
        cursorY = cursorY + r.height
        if ri < #rows then cursorY = cursorY + gap end
    end

    -- Grow our own height to fit the packed rows, unless the user pinned it.
    if not self._explicitHeight then
        self.height = (cursorY - (self.y or 0)) + pad
    end
end

function Flow:draw(monitor)
    if self.visible == false then return end
    if self.bg ~= nil or self.border ~= nil then
        local bg = resolveColor(self.bg, 0)
        if bg ~= 0 then monitor:drawRect(self.x, self.y, self.width, self.height, bg) end
        if self.border ~= nil then
            local bc = resolveColor(self.border, 0)
            if bc ~= 0 then monitor:drawRectOutline(self.x, self.y, self.width, self.height, bc, 1) end
        end
    end
    for _, c in ipairs(self.children or {}) do
        if c.visible ~= false and type(c.draw) == "function" then c:draw(monitor) end
    end
end

function Flow:hittest(px, py)
    if self.visible == false then return false end
    local x, y = self.x or 0, self.y or 0
    local w, h = self.width or 0, self.height or 0
    return px >= x and px < x + w and py >= y and py < y + h
end

function M.Flow(props)
    local o = _containerDefaults("Flow", props)
    -- Flag set when the user pinned an explicit height so layout() knows
    -- not to grow into a different value.
    o._explicitHeight = props and props.height and props.height > 0 or false
    return setmetatable(o, Flow)
end

-- ============================================================
-- Mount / unmount / render. Each mount() call pushes one root widget
-- onto a per-monitor list. A container root gets auto-laid-out to the
-- monitor's pixel bounds if width/height weren't set, and layout()
-- is invoked so descendants receive concrete rects.
-- ============================================================

local function _isContainer(widget)
    local k = widget.kind
    return k == "VBox" or k == "HBox" or k == "Card" or k == "Flow" or k == "Stack"
end

function M.mount(monitor, widget)
    assert(monitor, "ui.mount: monitor is required")
    assert(widget, "ui.mount: widget is required")
    local state = _monitors[monitor]
    if not state then
        state = { widgets = {} }
        _monitors[monitor] = state
    end
    state.widgets[#state.widgets + 1] = widget

    -- Auto-size container roots to the monitor's full pixel area on any axis
    -- the user left unset. Each dim fills independently so a Flow with only
    -- height=60 still gets full width (and keeps its pinned height).
    if _isContainer(widget) then
        widget.x = widget.x or 0
        widget.y = widget.y or 0
        if (widget.width or 0) <= 0 or (widget.height or 0) <= 0 then
            local pw, ph = monitor:getPixelSize()
            if (widget.width or 0) <= 0 then widget.width = pw end
            if (widget.height or 0) <= 0 then widget.height = ph end
        end
        if type(widget.layout) == "function" then widget:layout(monitor) end
    end

    M.invalidate()
    return widget
end

function M.unmount(monitor)
    _monitors[monitor] = nil
    M.invalidate()
end

local function renderOne(monitor, state)
    for _, w in ipairs(state.widgets) do
        if w.visible ~= false and type(w.draw) == "function" then
            w:draw(monitor)
        end
    end
end

function M.render(monitor)
    if monitor then
        local state = _monitors[monitor]
        if state then
            M._iconClearPending[monitor] = true
            renderOne(monitor, state)
        end
    else
        for m, s in pairs(_monitors) do
            M._iconClearPending[m] = true
            renderOne(m, s)
        end
    end
    _dirty = false
end

-- ============================================================
-- Event loop.
--
-- ui.run() -- blocks on os.pullEvent, dispatches to widgets, re-renders when dirty.
-- ui.tick(ev) -- one-shot; for power users who own their os.pullEvent loop.
-- ui.exit() -- flips the running flag; loop returns on next iteration.
--
-- Dispatch rule: monitor_touch events walk the widget tree depth-last.
-- First widget whose hittest returns true AND whose enabled is not false
-- receives onClick. Propagation stops unless the handler calls
-- e:stopPropagation() (currently only used to disambiguate overlapping
-- widgets in the same list).
-- ============================================================

local function _findLeafAt(widget, px, py)
    if widget.visible == false then return nil end
    local children = widget.children
    if children and #children > 0 then
        for i = #children, 1, -1 do
            local hit = _findLeafAt(children[i], px, py)
            if hit then return hit end
        end
    end
    if type(widget.hittest) == "function" and widget:hittest(px, py) then
        return widget
    end
    return nil
end

local function dispatchTouch(col, row, px, py, player)
    for monitor, state in pairs(_monitors) do
        local hit
        for i = #state.widgets, 1, -1 do
            local root = state.widgets[i]
            hit = _findLeafAt(root, px, py)
            if hit and hit.enabled ~= false and type(hit.onClick) == "function" then
                break
            end
            hit = nil
        end
        if hit then
            local ev = {
                type = "click",
                widget = hit,
                monitor = monitor,
                col = col, row = row, px = px, py = py,
                player = player,
                _stopped = false,
            }
            function ev:stopPropagation() self._stopped = true end
            local ok, err = pcall(hit.onClick, ev)
            if not ok then print("[ui] onClick error: " .. tostring(err)) end
        end
    end
end

function M.tick(ev)
    if type(ev) ~= "table" then return end
    local name = ev[1]
    if name == "monitor_touch" then
        dispatchTouch(ev[2], ev[3], ev[4] or 0, ev[5] or 0, ev[6])
    end
    if _dirty then M.render() end
end

function M.exit() _running = false end

function M.run()
    M.render()
    _running = true
    while _running do
        local ev = { os.pullEvent() }
        M.tick(ev)
    end
end

return M
