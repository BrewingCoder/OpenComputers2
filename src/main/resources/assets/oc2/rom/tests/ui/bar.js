// /rom/tests/ui/bar.js -- headless assertions for ui_v1.Bar (JS side).
//
// Mirror of /rom/tests/ui/bar.lua. No monitor required.
//
//   run /rom/tests/ui/bar.js

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

print("== ui_v1.Bar tests ==");

// Library surface
eq("Bar is function", typeof ui.Bar, "function");

// Defaults
var d = ui.Bar({});
eq("default kind",        d.kind,        "Bar");
eq("default x",           d.x,           0);
eq("default y",           d.y,           0);
eq("default width",       d.width,       0);
eq("default height",      d.height,      0);
eq("default value",       d.value,       0);
eq("default min",         d.min,         0);
eq("default max",         d.max,         100);
eq("default color",       d.color,       "good");
eq("default bg",          d.bg,          "bgCard");
eq("default border",      d.border,      "edge");
eq("default markerColor", d.markerColor, "fg");
eq("default orientation", d.orientation, "h");
eq("default showPct",     d.showPct,     false);
eq("default visible",     d.visible,     true);
eq("default marker",      d.marker,      null);

// Constructor props
var m = ui.Bar({
    x:10, y:20, width:100, height:16,
    value:75, min:0, max:200,
    color:"warn", bg:"bg", border:"muted",
    marker:42, markerColor:"bad",
    orientation:"v", showPct:true, visible:false,
});
eq("ctor x",           m.x,           10);
eq("ctor y",           m.y,           20);
eq("ctor width",       m.width,       100);
eq("ctor height",      m.height,      16);
eq("ctor value",       m.value,       75);
eq("ctor min",         m.min,         0);
eq("ctor max",         m.max,         200);
eq("ctor color",       m.color,       "warn");
eq("ctor bg",          m.bg,          "bg");
eq("ctor border",      m.border,      "muted");
eq("ctor marker",      m.marker,      42);
eq("ctor markerColor", m.markerColor, "bad");
eq("ctor orientation", m.orientation, "v");
eq("ctor showPct",     m.showPct,     true);
eq("ctor visible",     m.visible,     false);

// set / get
var b = ui.Bar({ value:10, color:"good" });
b.set({ value:88, color:"bad", marker:50 });
eq("set value",  b.get("value"),  88);
eq("set color",  b.get("color"),  "bad");
eq("set marker", b.get("marker"), 50);

// hittest (inherited from Widget mixin)
var h = ui.Bar({ x:10, y:10, width:100, height:20, value:50 });
eq("hittest inside", h.hittest(50, 15),  true);
eq("hittest right",  h.hittest(200, 15), false);
eq("hittest below",  h.hittest(50, 100), false);
eq("hittest left",   h.hittest(5, 15),   false);
eq("hittest above",  h.hittest(50, 5),   false);
var hi = ui.Bar({ x:10, y:10, width:100, height:20, visible:false });
eq("hittest invisible", hi.hittest(50, 15), false);

// Theme swap still affects Bar's default color tokens
ui.setTheme({
    bg: 0, bgCard: 0, fg: 0xFF111111 | 0, hi: 0xFFFFFFFF | 0, muted: 0xFF888888 | 0,
    good: 0xFF222222 | 0, warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0xFF2E3A4E | 0, primary: 0xFF3498DB | 0, ghost: 0xFF2E3A4E | 0, danger: 0xFFE74C3C | 0,
});
eq("setTheme resolves good for Bar", ui._resolveColor("good", 0), 0xFF222222 | 0);
ui.setTheme();
eq("setTheme reset", ui._resolveColor("good", 0), 0xFF2ECC71 | 0);

// isDirty / invalidate
ui.invalidate();
eq("isDirty after invalidate", ui.isDirty(), true);

print("== " + pass + " passed, " + fail + " failed ==");
if (fail > 0) throw new Error(fail + " assertions failed");
