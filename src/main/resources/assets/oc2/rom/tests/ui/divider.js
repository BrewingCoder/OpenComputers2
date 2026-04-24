// /rom/tests/ui/divider.js -- headless assertions for ui_v1.Divider (JS side).
//
// Mirror of /rom/tests/ui/divider.lua. No monitor required.
//
//   run /rom/tests/ui/divider.js

var ui = require("ui_v1");
var pass = 0, fail = 0;
function eq(name, got, want) {
    if (got === want) {
        pass++;
        print("  PASS  " + name);
    } else {
        fail++;
        print("  FAIL  " + name + " -- got " + got + " want " + want);
    }
}

print("== ui_v1.Divider tests ==");

// Library surface
eq("Divider is function", typeof ui.Divider, "function");

// Defaults
var d = ui.Divider({});
eq("default kind",        d.kind,        "Divider");
eq("default x",           d.x,           0);
eq("default y",           d.y,           0);
eq("default width",       d.width,       0);
eq("default height",      d.height,      0);
eq("default orientation", d.orientation, "h");
eq("default color",       d.color,       "edge");
eq("default thickness",   d.thickness,   1);
eq("default visible",     d.visible,     true);

// Constructor props
var m = ui.Divider({
    x:10, y:20, width:100, height:6,
    orientation:"v", color:"hi", thickness:3, visible:false,
});
eq("ctor x",           m.x,           10);
eq("ctor y",           m.y,           20);
eq("ctor width",       m.width,       100);
eq("ctor height",      m.height,      6);
eq("ctor orientation", m.orientation, "v");
eq("ctor color",       m.color,       "hi");
eq("ctor thickness",   m.thickness,   3);
eq("ctor visible",     m.visible,     false);

// set / get
var b = ui.Divider({ color:"edge", thickness:1 });
b.set({ color:"bad", thickness:4, orientation:"v" });
eq("set color",       b.get("color"),       "bad");
eq("set thickness",   b.get("thickness"),   4);
eq("set orientation", b.get("orientation"), "v");

// hittest (inherited from Widget mixin)
var h = ui.Divider({ x:10, y:10, width:100, height:8 });
eq("hittest inside", h.hittest(50, 12),  true);
eq("hittest right",  h.hittest(200, 12), false);
eq("hittest below",  h.hittest(50, 100), false);
eq("hittest left",   h.hittest(5, 12),   false);
eq("hittest above",  h.hittest(50, 5),   false);
var hi = ui.Divider({ x:10, y:10, width:100, height:8, visible:false });
eq("hittest invisible", hi.hittest(50, 12), false);

// Theme swap still affects Divider's default color tokens
ui.setTheme({
    bg: 0, bgCard: 0, fg: 0xFF111111 | 0, hi: 0xFFFFFFFF | 0, muted: 0xFF888888 | 0,
    good: 0xFF2ECC71 | 0, warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0xFF112233 | 0, primary: 0xFF3498DB | 0, ghost: 0xFF2E3A4E | 0, danger: 0xFFE74C3C | 0,
});
eq("setTheme resolves edge for Divider", ui._resolveColor("edge", 0), 0xFF112233 | 0);
ui.setTheme();
eq("setTheme reset", ui._resolveColor("edge", 0), 0xFF2E3A4E | 0);

// isDirty / invalidate
ui.invalidate();
eq("isDirty after invalidate", ui.isDirty(), true);

print("== " + pass + " passed, " + fail + " failed ==");
if (fail > 0) throw new Error(fail + " assertions failed");
