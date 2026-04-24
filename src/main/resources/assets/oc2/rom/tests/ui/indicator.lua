-- /rom/tests/ui/indicator.lua -- headless assertions for ui_v1.Indicator.
--
--   run /rom/tests/ui/indicator.lua

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

print("== ui_v1.Indicator tests ==")

-- Library surface
eq("Indicator is function", type(ui.Indicator), "function")

-- Defaults
local d = ui.Indicator{}
eq("default kind",       d.kind,       "Indicator")
eq("default x",          d.x,          0)
eq("default y",          d.y,          0)
eq("default width",      d.width,      0)
eq("default height",     d.height,     0)
eq("default size",       d.size,       8)
eq("default state",      d.state,      "off")
eq("default label",      d.label,      nil)
eq("default color",      d.color,      nil)
eq("default offColor",   d.offColor,   "muted")
eq("default labelColor", d.labelColor, "fg")
eq("default gap",        d.gap,        4)
eq("default visible",    d.visible,    true)

-- Constructor props
local m = ui.Indicator{
    x=10, y=20, width=100, height=12,
    size=12, state="warn", label="HEAT",
    color="bad", offColor="edge", labelColor="hi",
    gap=6, visible=false,
}
eq("ctor x",          m.x,          10)
eq("ctor y",          m.y,          20)
eq("ctor width",      m.width,      100)
eq("ctor height",     m.height,     12)
eq("ctor size",       m.size,       12)
eq("ctor state",      m.state,      "warn")
eq("ctor label",      m.label,      "HEAT")
eq("ctor color",      m.color,      "bad")
eq("ctor offColor",   m.offColor,   "edge")
eq("ctor labelColor", m.labelColor, "hi")
eq("ctor gap",        m.gap,        6)
eq("ctor visible",    m.visible,    false)

-- set / get
local b = ui.Indicator{ state="off" }
b:set{ state="on", label="PUMP", size=10 }
eq("set state", b:get("state"), "on")
eq("set label", b:get("label"), "PUMP")
eq("set size",  b:get("size"),  10)

-- hittest (inherited from Widget)
local h = ui.Indicator{ x=10, y=10, width=80, height=12 }
eq("hittest inside", h:hittest(50, 12), true)
eq("hittest right",  h:hittest(200, 12), false)
eq("hittest below",  h:hittest(50, 100), false)
eq("hittest left",   h:hittest(5, 12),   false)
eq("hittest above",  h:hittest(50, 5),   false)
local hi = ui.Indicator{ x=10, y=10, width=80, height=12, visible=false }
eq("hittest invisible", hi:hittest(50, 12), false)

-- Theme swap still affects Indicator state-mapped colors
ui.setTheme{
    bg=0, bgCard=0, fg=0xFF111111, hi=0xFFFFFFFF, muted=0xFF888888,
    good=0xFF112233, warn=0xFFF1C40F, bad=0xFFE74C3C, info=0xFF3498DB,
    edge=0xFF2E3A4E, primary=0xFF3498DB, ghost=0xFF2E3A4E, danger=0xFFE74C3C,
}
eq("setTheme resolves good for Indicator", ui._resolveColor("good", 0), 0xFF112233)
ui.setTheme()  -- reset
eq("setTheme reset", ui._resolveColor("good", 0), 0xFF2ECC71)

ui.invalidate()
eq("isDirty after invalidate", ui.isDirty(), true)

print("== " .. pass .. " passed, " .. fail .. " failed ==")
if fail > 0 then error(fail .. " assertions failed") end
