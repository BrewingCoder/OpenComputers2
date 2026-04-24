// /rom/examples/ui/bar.js -- visual showcase for ui_v1.Bar (JS side).
//
// Mirror of /rom/examples/ui/bar.lua. Run:
//   run /rom/examples/ui/bar.js

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

// Wipe every layer before we render -- all three ops are load-bearing.
mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

// Banner
ui.mount(mon, ui.Label({
    x:0, y:0, width:480, height:12,
    text:"ui_v1 Bar showcase",
    color:"hi", bg:"bgCard", align:"center",
}));

// [2] Fill-percentage sweep.
var pcts = [0, 25, 50, 75, 100];
for (var i = 0; i < pcts.length; i++) {
    var bx = 8 + i * 92;
    ui.mount(mon, ui.Label({ x:bx, y:24, width:88, height:12,
        text: pcts[i] + "%", color:"muted", align:"left" }));
    ui.mount(mon, ui.Bar({
        x:bx, y:40, width:88, height:16,
        value:pcts[i], min:0, max:100,
    }));
}

// [3] Color token palette.
var colors = ["good","warn","bad","info","hi","primary"];
for (var j = 0; j < colors.length; j++) {
    var cx = 8 + j * 78;
    ui.mount(mon, ui.Label({ x:cx, y:68, width:76, height:12,
        text:colors[j], color:"muted", align:"left" }));
    ui.mount(mon, ui.Bar({
        x:cx, y:84, width:76, height:16,
        value:65, color:colors[j],
    }));
}

// [4] Marker bar.
ui.mount(mon, ui.Label({ x:8, y:112, width:200, height:12,
    text:"marker at 70% (white line)", color:"muted", align:"left" }));
ui.mount(mon, ui.Bar({
    x:8, y:128, width:300, height:16,
    value:45, marker:70, markerColor:"hi",
}));

// [5] showPct=true.
ui.mount(mon, ui.Label({ x:320, y:112, width:200, height:12,
    text:"showPct = true", color:"muted", align:"left" }));
ui.mount(mon, ui.Bar({
    x:320, y:128, width:200, height:16,
    value:42, showPct:true,
}));

// [6] Vertical bars.
ui.mount(mon, ui.Label({ x:8, y:156, width:200, height:12,
    text:"vertical bars:", color:"muted", align:"left" }));
var vpcts = [20, 40, 60, 80, 100];
for (var k = 0; k < vpcts.length; k++) {
    var vx = 8 + k * 40;
    ui.mount(mon, ui.Bar({
        x:vx, y:172, width:28, height:80,
        value:vpcts[k], orientation:"v",
    }));
    ui.mount(mon, ui.Label({ x:vx, y:256, width:28, height:12,
        text: vpcts[k] + "%", color:"muted", align:"center" }));
}

// [7] visible=false.
ui.mount(mon, ui.Label({ x:240, y:172, width:200, height:12,
    text:"below: visible=false (blank)", color:"muted", align:"left" }));
ui.mount(mon, ui.Bar({
    x:240, y:188, width:200, height:16,
    value:80, color:"bad", visible:false,
}));

// [8] set() mutation.
var mut = ui.Bar({ x:240, y:212, width:200, height:16,
    value:0, color:"muted" });
mut.set({ value:60, color:"bad" });
ui.mount(mon, mut);
ui.mount(mon, ui.Label({ x:240, y:230, width:200, height:12,
    text:"mutated via set() -> 60% bad", color:"muted", align:"left" }));

// [9] Hex color override (magenta).
ui.mount(mon, ui.Label({ x:460, y:172, width:200, height:12,
    text:"hex 0xFFFF00FF (magenta)", color:"muted", align:"left" }));
ui.mount(mon, ui.Bar({
    x:460, y:188, width:200, height:16,
    value:75, color:0xFFFF00FF | 0,
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
print("'good' bars now orange for 5s");
sleep(5);
ui.setTheme();
ui.render();
print("theme reset; done");

print("-- holding display. run `kill` from another shell to clear --");
while (true) { sleep(60); }
