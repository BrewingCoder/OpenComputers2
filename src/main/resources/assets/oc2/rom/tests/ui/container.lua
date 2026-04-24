-- /rom/tests/ui/container.lua -- headless assertions for ui_v1 layout
-- containers (VBox, HBox, Spacer, Card).
--
-- No monitor required. Verifies constructor defaults, prop application,
-- set/get, hittest, and the measure-layout pass (fixed + flex distribution,
-- gap, padding, invisible-child skip, nested containers, Card chrome).
--
--   run /rom/tests/ui/container.lua

local ui = require("ui_v1")
local pass, fail = 0, 0
local function eq(name, got, want)
    if got == want then
        pass = pass + 1
        print("  PASS  " .. name)
    else
        fail = fail + 1
        print("  FAIL  " .. name .. " -- got " .. tostring(got) .. " want " .. tostring(want))
    end
end

print("== ui_v1.Container tests ==")

-- ----- Library surface -----
eq("VBox is function",   type(ui.VBox),   "function")
eq("HBox is function",   type(ui.HBox),   "function")
eq("Spacer is function", type(ui.Spacer), "function")
eq("Card is function",   type(ui.Card),   "function")

-- ----- Defaults: VBox -----
local v = ui.VBox{}
eq("VBox default kind",     v.kind,     "VBox")
eq("VBox default padding",  v.padding,  0)
eq("VBox default gap",      v.gap,      0)
eq("VBox default visible",  v.visible,  true)
eq("VBox default children", type(v.children), "table")
eq("VBox default bg",       v.bg,       nil)

-- ----- Defaults: HBox -----
local h = ui.HBox{}
eq("HBox default kind",    h.kind,    "HBox")
eq("HBox default padding", h.padding, 0)

-- ----- Defaults: Spacer -----
local sp = ui.Spacer{}
eq("Spacer default kind", sp.kind, "Spacer")
eq("Spacer default flex", sp.flex, 1)

-- ----- Defaults: Card -----
local c = ui.Card{}
eq("Card default kind",    c.kind,    "Card")
eq("Card default padding", c.padding, 4)
eq("Card default bg",      c.bg,      "bgCard")
eq("Card default border",  c.border,  "edge")

-- ----- Constructor props override defaults -----
local cv = ui.VBox{ x=10, y=20, width=200, height=100, padding=6, gap=4, bg="bg" }
eq("VBox ctor x",       cv.x,       10)
eq("VBox ctor y",       cv.y,       20)
eq("VBox ctor width",   cv.width,   200)
eq("VBox ctor height",  cv.height,  100)
eq("VBox ctor padding", cv.padding, 6)
eq("VBox ctor gap",     cv.gap,     4)
eq("VBox ctor bg",      cv.bg,      "bg")

local cc = ui.Card{ padding=0, bg="good", border=0 }
eq("Card ctor padding=0 preserved", cc.padding, 0)
eq("Card ctor bg override",         cc.bg,      "good")
eq("Card ctor border=0 preserved",  cc.border,  0)

-- ----- set / get -----
local s = ui.VBox{ padding=2, gap=0 }
s:set{ padding=8, gap=3 }
eq("VBox set padding", s:get("padding"), 8)
eq("VBox set gap",     s:get("gap"),     3)

-- ----- hittest (shared with Widget, but container exposes its own) -----
local hc = ui.HBox{ x=10, y=10, width=100, height=20 }
eq("HBox hittest inside", hc:hittest(50, 15),  true)
eq("HBox hittest right",  hc:hittest(200, 15), false)
eq("HBox hittest below",  hc:hittest(50, 100), false)
eq("HBox hittest left",   hc:hittest(5, 15),   false)
eq("HBox hittest above",  hc:hittest(50, 5),   false)
local hi = ui.HBox{ x=10, y=10, width=100, height=20, visible=false }
eq("HBox hittest invisible", hi:hittest(50, 15), false)

-- ----- Layout: VBox stacks fixed-height children top-to-bottom -----
do
    local a = ui.Label{ height=20 }
    local b = ui.Label{ height=30 }
    local cbox = ui.VBox{ x=0, y=0, width=200, height=200, children={a, b} }
    cbox:layout()
    eq("VBox fixed a.y",      a.y,      0)
    eq("VBox fixed a.height", a.height, 20)
    eq("VBox fixed a.width",  a.width,  200)  -- cross-axis stretches
    eq("VBox fixed b.y",      b.y,      20)
    eq("VBox fixed b.height", b.height, 30)
end

-- ----- Layout: HBox stacks fixed-width children left-to-right -----
do
    local a = ui.Label{ width=40 }
    local b = ui.Label{ width=60 }
    local rbox = ui.HBox{ x=10, y=20, width=200, height=48, children={a, b} }
    rbox:layout()
    eq("HBox fixed a.x",      a.x,      10)
    eq("HBox fixed a.width",  a.width,  40)
    eq("HBox fixed a.height", a.height, 48)  -- cross-axis stretches
    eq("HBox fixed b.x",      b.x,      50)
    eq("HBox fixed b.width",  b.width,  60)
end

-- ----- Layout: Spacer eats remainder in VBox -----
do
    local top = ui.Label{ height=10 }
    local spc = ui.Spacer{}
    local bot = ui.Label{ height=20 }
    local box = ui.VBox{ x=0, y=0, width=100, height=100, children={top, spc, bot} }
    box:layout()
    eq("VBox spacer top.y",      top.y,      0)
    eq("VBox spacer top.height", top.height, 10)
    eq("VBox spacer spc.y",      spc.y,      10)
    eq("VBox spacer spc.height", spc.height, 70)
    eq("VBox spacer bot.y",      bot.y,      80)
    eq("VBox spacer bot.height", bot.height, 20)
end

-- ----- Layout: multiple flex children split remainder proportionally -----
do
    local a = ui.Label{ flex=1 }
    local b = ui.Label{ flex=3 }
    local box = ui.HBox{ x=0, y=0, width=120, height=40, children={a, b} }
    box:layout()
    eq("HBox flex a.width", a.width, 30)
    eq("HBox flex b.width", b.width, 90)
end

-- ----- Layout: fixed + flex children -----
do
    local fixed = ui.Label{ height=40 }
    local flex  = ui.Label{ flex=1 }
    local box = ui.VBox{ x=0, y=0, width=50, height=100, children={fixed, flex} }
    box:layout()
    eq("VBox fixed+flex fixed.y",      fixed.y,      0)
    eq("VBox fixed+flex fixed.height", fixed.height, 40)
    eq("VBox fixed+flex flex.y",       flex.y,       40)
    eq("VBox fixed+flex flex.height",  flex.height,  60)
end

-- ----- Layout: gap inserts space between visible children -----
do
    local a = ui.Label{ height=10 }
    local b = ui.Label{ height=10 }
    local c = ui.Label{ height=10 }
    local box = ui.VBox{ x=0, y=0, width=50, height=100, gap=5, children={a, b, c} }
    box:layout()
    eq("VBox gap a.y", a.y, 0)
    eq("VBox gap b.y", b.y, 15)
    eq("VBox gap c.y", c.y, 30)
end

-- ----- Layout: padding shrinks inner rect -----
do
    local child = ui.Label{ height=10 }
    local box = ui.VBox{ x=0, y=0, width=100, height=50, padding=8, children={child} }
    box:layout()
    eq("VBox padding child.x",     child.x,     8)
    eq("VBox padding child.y",     child.y,     8)
    eq("VBox padding child.width", child.width, 84)
end

-- ----- Layout: invisible children skipped -----
do
    local a = ui.Label{ height=10 }
    local ghost = ui.Label{ height=999, visible=false }
    local c = ui.Label{ height=20 }
    local box = ui.VBox{ x=0, y=0, width=50, height=100, children={a, ghost, c} }
    box:layout()
    eq("VBox invisible a.y", a.y, 0)
    eq("VBox invisible c.y", c.y, 10)
end

-- ----- Layout: Card padding + children -----
do
    local child = ui.Label{ height=10 }
    local card = ui.Card{ x=0, y=0, width=80, height=40, children={child} }
    card:layout()
    eq("Card child.x",     child.x,     4)
    eq("Card child.y",     child.y,     4)
    eq("Card child.width", child.width, 72)
end

-- ----- Layout: nested HBox inside VBox -----
do
    local a = ui.Label{ width=30 }
    local b = ui.Label{ width=50 }
    local row = ui.HBox{ height=20, children={a, b} }
    local top = ui.Label{ height=10 }
    local box = ui.VBox{ x=0, y=0, width=100, height=100, children={top, row} }
    box:layout()
    eq("nested top.y",    top.y,    0)
    eq("nested top.height", top.height, 10)
    eq("nested row.y",    row.y,    10)
    eq("nested row.width", row.width, 100)
    eq("nested a.x",      a.x,      0)
    eq("nested a.y",      a.y,      10)
    eq("nested a.height", a.height, 20)
    eq("nested b.x",      b.x,      30)
end

print("== " .. pass .. " passed, " .. fail .. " failed ==")
if fail > 0 then error(fail .. " assertions failed") end
