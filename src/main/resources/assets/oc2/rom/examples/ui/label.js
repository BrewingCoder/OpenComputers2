// /rom/examples/ui/label.js -- visual showcase for ui_v1.Label (JS side).
//
// Mirror of /rom/examples/ui/label.lua. Run:
//   run /rom/examples/ui/label.js

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

// Wipe every layer before we render. All three steps are load-bearing:
//   1. clearPixels(opaque-black)     wipes the HD pixel buffer
//   2. setBackgroundColor(black)     sets the bg color that clear() uses
//   3. clear()                       repaints every text cell with the bg
// Skip any of these and stale reactor-dashboard artifacts bleed through.
mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

ui.mount(mon, ui.Label({
    x:0, y:0, width:320, height:12,
    text:"ui_v1 Label showcase",
    color:"hi", bg:"bgCard", align:"center",
}));

var aligns = ["left","center","right"];
for (var i = 0; i < aligns.length; i++) {
    ui.mount(mon, ui.Label({
        x:0, y:24 + i*12, width:320, height:12,
        text:"align="+aligns[i],
        color:"fg", bg:"bgCard", align:aligns[i],
    }));
}

var tokens = ["good","warn","bad","info","hi","muted","primary"];
for (var j = 0; j < tokens.length; j++) {
    ui.mount(mon, ui.Label({
        x:8, y:72 + j*12, width:200, height:12,
        text:"color="+tokens[j],
        color:tokens[j], align:"left",
    }));
}

ui.mount(mon, ui.Label({
    x:8, y:168, width:200, height:12,
    text:"hex color 0xFFFF00FF",
    color:0xFFFF00FF | 0, align:"left",
}));

ui.mount(mon, ui.Label({
    x:8, y:180, width:200, height:12,
    text:"INVISIBLE -- should NOT appear",
    color:"bad", visible:false,
}));

var mut = ui.Label({ x:8, y:192, width:200, height:12, text:"old", color:"muted" });
mut.set({ text:"mutated via set()", color:"good" });
ui.mount(mon, mut);

ui.mount(mon, ui.Label({
    x:0, y:204, width:320, height:12,
    text:"right hex 0xFF00FFFF",
    color:0xFF00FFFF | 0, align:"right",
}));

ui.mount(mon, ui.Label({
    x:8, y:216, width:200, height:12,
    text:"no bg prop",
    color:"fg", align:"left",
}));

ui.render();
print("rendered; swapping theme in 3s...");
sleep(3);

ui.setTheme({
    bg:     0xFF0A0F1A | 0,
    bgCard: 0xFF121827 | 0,
    fg:     0xFFFFFFFF | 0,
    hi:     0xFFE6F0FF | 0,
    muted:  0xFF7A8597 | 0,
    good:   0xFFFF8800 | 0,   // orange
    warn:   0xFFF1C40F | 0,
    bad:    0xFFE74C3C | 0,
    info:   0xFF3498DB | 0,
    edge:   0xFF2E3A4E | 0,
    primary:0xFF3498DB | 0,
    ghost:  0xFF2E3A4E | 0,
    danger: 0xFFE74C3C | 0,
});
ui.render();
print("'good'-color labels should now be orange for 5s");

sleep(5);
ui.setTheme();  // no arg -> reset to DEFAULT_THEME
ui.render();
print("theme reset; done");

// Hold the peripheral lease open so the monitor keeps showing our labels.
// When the script exits, MonitorBlockEntity's onRelease wipes the text + pixel
// buffers (so a later script doesn't inherit stale content). That's the right
// call in general, but for a SHOWCASE you want the output to stay on-screen
// until you decide to tear it down -- so we park here until the user kills us.
print("-- holding display. run `kill` from another shell to clear --");
while (true) { sleep(60); }
