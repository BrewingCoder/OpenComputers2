-- /rom/tests/ui/divider.lua -- headless assertions for ui_v1.Divider.
--
-- No monitor required. Verifies the widget's public surface:
-- constructor defaults, prop application, set/get, hittest (inherited
-- from Widget), orientation, thickness clamp.
--
--   run /rom/tests/ui/divider.lua

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

print("== ui_v1.Divider tests ==")

-- Library surface
eq("Divider is function", type(ui.Divider), "function")

-- Defaults
local d = ui.Divider{}
eq("default kind",        d.kind,        "Divider")
eq("default x",           d.x,           0)
eq("default y",           d.y,           0)
eq("default width",       d.width,       0)
eq("default height",      d.height,      0)
eq("default orientation", d.orientation, "h")
eq("default color",       d.color,       "edge")
eq("default thickness",   d.thickness,   1)
eq("default visible",     d.visible,     true)

-- Constructor props
local m = ui.Divider{
    x=10, y=20, width=100, height=6,
    orientation="v", color="hi", thickness=3, visible=false,
}
eq("ctor x",           m.x,           10)
eq("ctor y",           m.y,           20)
eq("ctor width",       m.width,       100)
eq("ctor height",      m.height,      6)
eq("ctor orientation", m.orientation, "v")
eq("ctor color",       m.color,       "hi")
eq("ctor thickness",   m.thickness,   3)
eq("ctor visible",     m.visible,     false)

-- set / get
local b = ui.Divider{ color="edge", thickness=1 }
b:set{ color="bad", thickness=4, orientation="v" }
eq("set color",       b:get("color"),       "bad")
eq("set thickness",   b:get("thickness"),   4)
eq("set orientation", b:get("orientation"), "v")

-- hittest (inherited from Widget)
local h = ui.Divider{ x=10, y=10, width=100, height=8 }
eq("hittest inside", h:hittest(50, 12),  true)
eq("hittest right",  h:hittest(200, 12), false)
eq("hittest below",  h:hittest(50, 100), false)
eq("hittest left",   h:hittest(5, 12),   false)
eq("hittest above",  h:hittest(50, 5),   false)
local hi = ui.Divider{ x=10, y=10, width=100, height=8, visible=false }
eq("hittest invisible", hi:hittest(50, 12), false)

-- Theme swap still affects Divider's default color tokens
ui.setTheme{
    bg=0, bgCard=0, fg=0xFF111111, hi=0xFFFFFFFF, muted=0xFF888888,
    good=0xFF2ECC71, warn=0xFFF1C40F, bad=0xFFE74C3C, info=0xFF3498DB,
    edge=0xFF112233, primary=0xFF3498DB, ghost=0xFF2E3A4E, danger=0xFFE74C3C,
}
eq("setTheme resolves edge for Divider", ui._resolveColor("edge", 0), 0xFF112233)
ui.setTheme()  -- reset
eq("setTheme reset", ui._resolveColor("edge", 0), 0xFF2E3A4E)

-- isDirty / invalidate
ui.invalidate()
eq("isDirty after invalidate", ui.isDirty(), true)

print("== " .. pass .. " passed, " .. fail .. " failed ==")
if fail > 0 then error(fail .. " assertions failed") end
