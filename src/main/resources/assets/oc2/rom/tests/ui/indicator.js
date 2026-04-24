// /rom/tests/ui/indicator.js -- headless assertions for ui_v1.Indicator (JS side).
//
// Mirror of /rom/tests/ui/indicator.lua.
//
//   run /rom/tests/ui/indicator.js

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

print("== ui_v1.Indicator tests ==");

// Library surface
eq("Indicator is function", typeof ui.Indicator, "function");

// Defaults
var d = ui.Indicator({});
eq("default kind",       d.kind,       "Indicator");
eq("default x",          d.x,          0);
eq("default y",          d.y,          0);
eq("default width",      d.width,      0);
eq("default height",     d.height,     0);
eq("default size",       d.size,       8);
eq("default state",      d.state,      "off");
eq("default label",      d.label,      null);
eq("default color",      d.color,      null);
eq("default offColor",   d.offColor,   "muted");
eq("default labelColor", d.labelColor, "fg");
eq("default gap",        d.gap,        4);
eq("default visible",    d.visible,    true);

// Constructor props
var m = ui.Indicator({
    x:10, y:20, width:100, height:12,
    size:12, state:"warn", label:"HEAT",
    color:"bad", offColor:"edge", labelColor:"hi",
    gap:6, visible:false,
});
eq("ctor x",          m.x,          10);
eq("ctor y",          m.y,          20);
eq("ctor width",      m.width,      100);
eq("ctor height",     m.height,     12);
eq("ctor size",       m.size,       12);
eq("ctor state",      m.state,      "warn");
eq("ctor label",      m.label,      "HEAT");
eq("ctor color",      m.color,      "bad");
eq("ctor offColor",   m.offColor,   "edge");
eq("ctor labelColor", m.labelColor, "hi");
eq("ctor gap",        m.gap,        6);
eq("ctor visible",    m.visible,    false);

// set / get
var b = ui.Indicator({ state:"off" });
b.set({ state:"on", label:"PUMP", size:10 });
eq("set state", b.get("state"), "on");
eq("set label", b.get("label"), "PUMP");
eq("set size",  b.get("size"),  10);

// hittest (inherited from Widget mixin)
var h = ui.Indicator({ x:10, y:10, width:80, height:12 });
eq("hittest inside", h.hittest(50, 12), true);
eq("hittest right",  h.hittest(200, 12), false);
eq("hittest below",  h.hittest(50, 100), false);
eq("hittest left",   h.hittest(5, 12),   false);
eq("hittest above",  h.hittest(50, 5),   false);
var hi = ui.Indicator({ x:10, y:10, width:80, height:12, visible:false });
eq("hittest invisible", hi.hittest(50, 12), false);

// Theme swap still affects Indicator state-mapped colors
ui.setTheme({
    bg: 0, bgCard: 0, fg: 0xFF111111 | 0, hi: 0xFFFFFFFF | 0, muted: 0xFF888888 | 0,
    good: 0xFF112233 | 0, warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0xFF2E3A4E | 0, primary: 0xFF3498DB | 0, ghost: 0xFF2E3A4E | 0, danger: 0xFFE74C3C | 0,
});
eq("setTheme resolves good for Indicator", ui._resolveColor("good", 0), 0xFF112233 | 0);
ui.setTheme();
eq("setTheme reset", ui._resolveColor("good", 0), 0xFF2ECC71 | 0);

ui.invalidate();
eq("isDirty after invalidate", ui.isDirty(), true);

print("== " + pass + " passed, " + fail + " failed ==");
if (fail > 0) throw new Error(fail + " assertions failed");
