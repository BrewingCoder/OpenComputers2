// /rom/tests/ui/label.js -- headless assertions for ui_v1.Label (JS side).
//
// Mirror of /rom/tests/ui/label.lua. No monitor required.
//
//   run /rom/tests/ui/label.js

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

print("== ui_v1.Label tests ==");

// Library surface
eq("VERSION", ui.VERSION, "v1");
eq("Label is function", typeof ui.Label, "function");
eq("mount is function", typeof ui.mount, "function");

// Defaults
var d = ui.Label({});
eq("default kind",    d.kind,    "Label");
eq("default x",       d.x,       0);
eq("default y",       d.y,       0);
eq("default width",   d.width,   0);
eq("default height",  d.height,  0);
eq("default text",    d.text,    "");
eq("default color",   d.color,   "fg");
eq("default bg",      d.bg,      null);
eq("default align",   d.align,   "left");
eq("default visible", d.visible, true);

// Constructor props
var m = ui.Label({ x:10, y:20, width:100, height:16, text:"Hi",
    color:"good", bg:"bgCard", align:"center", visible:false });
eq("ctor x",       m.x,       10);
eq("ctor y",       m.y,       20);
eq("ctor width",   m.width,   100);
eq("ctor height",  m.height,  16);
eq("ctor text",    m.text,    "Hi");
eq("ctor color",   m.color,   "good");
eq("ctor bg",      m.bg,      "bgCard");
eq("ctor align",   m.align,   "center");
eq("ctor visible", m.visible, false);

// set / get
var l = ui.Label({ text:"old", color:"muted" });
l.set({ text:"new", color:"warn" });
eq("set text",  l.get("text"),  "new");
eq("set color", l.get("color"), "warn");

// hittest
var h = ui.Label({ x:10, y:10, width:100, height:20, text:"hit" });
eq("hittest inside", h.hittest(50, 15),  true);
eq("hittest right",  h.hittest(200, 15), false);
eq("hittest below",  h.hittest(50, 100), false);
eq("hittest left",   h.hittest(5, 15),   false);
eq("hittest above",  h.hittest(50, 5),   false);
var hi = ui.Label({ x:10, y:10, width:100, height:20, visible:false });
eq("hittest invisible", hi.hittest(50, 15), false);

// resolveColor
eq("resolve token good",    ui._resolveColor("good", 0),      0xFF2ECC71 | 0);
eq("resolve token hi",      ui._resolveColor("hi", 0),        0xFFE6F0FF | 0);
eq("resolve token warn",    ui._resolveColor("warn", 0),      0xFFF1C40F | 0);
eq("resolve hex",           ui._resolveColor(0xFF123456, 0),  0xFF123456 | 0);
eq("resolve unknown token", ui._resolveColor("nope", 0xDEADBEEF | 0), 0xDEADBEEF | 0);
eq("resolve nil",           ui._resolveColor(null, 0xCAFEBABE | 0),   0xCAFEBABE | 0);

// Theme swap + reset
ui.setTheme({
    bg: 0, bgCard: 0, fg: 0xFF111111 | 0, hi: 0xFFFFFFFF | 0, muted: 0xFF888888 | 0,
    good: 0xFF222222 | 0, warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0, primary: 0, ghost: 0, danger: 0,
});
eq("setTheme applied fg",   ui.getTheme().fg,            0xFF111111 | 0);
eq("setTheme resolve good", ui._resolveColor("good", 0), 0xFF222222 | 0);
ui.setTheme();
eq("setTheme reset good",   ui._resolveColor("good", 0), 0xFF2ECC71 | 0);

// isDirty / invalidate
ui.invalidate();
eq("isDirty after invalidate", ui.isDirty(), true);

print("== " + pass + " passed, " + fail + " failed ==");
if (fail > 0) throw new Error(fail + " assertions failed");
