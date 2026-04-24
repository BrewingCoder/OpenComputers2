// /rom/examples/ui/indicator.js -- visual showcase for ui_v1.Indicator (JS).
//
// Mirror of /rom/examples/ui/indicator.lua.
//   run /rom/examples/ui/indicator.js

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

// [1] Banner
ui.mount(mon, ui.Label({
    x:0, y:0, width:480, height:12,
    text:"ui_v1 Indicator showcase",
    color:"hi", bg:"bgCard", align:"center",
}));

// [2] Five state LEDs without labels.
ui.mount(mon, ui.Label({ x:8, y:24, width:280, height:12,
    text:"states (no label): on / off / warn / bad / info",
    color:"muted", align:"left" }));
var states = ["on","off","warn","bad","info"];
for (var i = 0; i < states.length; i++) {
    ui.mount(mon, ui.Indicator({ x:8 + i * 50, y:40, width:40, height:16, state:states[i] }));
}

// [3] Same states with labels.
ui.mount(mon, ui.Label({ x:8, y:68, width:280, height:12,
    text:"states (with labels):", color:"muted", align:"left" }));
for (var j = 0; j < states.length; j++) {
    ui.mount(mon, ui.Indicator({
        x:8 + j * 90, y:84, width:80, height:16,
        state:states[j], label:states[j].toUpperCase(),
    }));
}

// [4] Size sweep, all "on". LED horizontal footprint is ~size * 20/9 due to
// aspect correction, so space accordingly.
ui.mount(mon, ui.Label({ x:8, y:112, width:280, height:12,
    text:"size sweep: 4 / 8 / 12 / 16 / 24:", color:"muted", align:"left" }));
var sizes = [4, 8, 12, 16, 24];
var x = 8;
for (var k = 0; k < sizes.length; k++) {
    var sz = sizes[k];
    var footprint = Math.ceil(sz * 20 / 9);
    ui.mount(mon, ui.Indicator({
        x:x, y:130, width:footprint + 4, height:28,
        size:sz, state:"on",
    }));
    x += footprint + 12;
}

// [5] Hex + label-color override.
ui.mount(mon, ui.Label({ x:8, y:172, width:280, height:12,
    text:"hex 0xFFFF00FF + labelColor=hi:", color:"muted", align:"left" }));
ui.mount(mon, ui.Indicator({
    x:8, y:188, width:200, height:16,
    state:"off", color:0xFFFF00FF | 0, label:"CUSTOM", labelColor:"hi",
}));

// [6] visible=false.
ui.mount(mon, ui.Label({ x:8, y:212, width:280, height:12,
    text:"below: visible=false (blank)", color:"muted", align:"left" }));
ui.mount(mon, ui.Indicator({
    x:8, y:228, width:200, height:16,
    state:"bad", label:"HIDDEN", visible:false,
}));

// [7] set() mutation.
var mut = ui.Indicator({ x:8, y:252, width:200, height:16, state:"off" });
mut.set({ state:"warn", label:"MUTATED" });
ui.mount(mon, mut);
ui.mount(mon, ui.Label({ x:8, y:270, width:280, height:12,
    text:"mutated via set() -> warn + label", color:"muted", align:"left" }));

// [8] Fuel bank grid.
ui.mount(mon, ui.Label({ x:300, y:112, width:180, height:12,
    text:"fuel bank (9 cells):", color:"muted", align:"left" }));
var fuel = ["on","on","warn","on","bad","on","off","on","on"];
for (var f = 0; f < fuel.length; f++) {
    var col = f % 3;
    var row = Math.floor(f / 3);
    ui.mount(mon, ui.Indicator({
        x:300 + col * 60, y:130 + row * 24, width:48, height:20,
        size:12, state:fuel[f],
    }));
}

ui.render();
print("rendered; swapping theme in 3s...");
sleep(3);

ui.setTheme({
    bg: 0xFF0A0F1A | 0, bgCard: 0xFF121827 | 0, fg: 0xFFFFFFFF | 0,
    hi: 0xFFE6F0FF | 0, muted: 0xFF7A8597 | 0,
    good: 0xFFFF8800 | 0,  // orange instead of green
    warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0xFF2E3A4E | 0, primary: 0xFF3498DB | 0, ghost: 0xFF2E3A4E | 0, danger: 0xFFE74C3C | 0,
});
ui.render();
print("'on' LEDs now orange for 5s");
sleep(5);
ui.setTheme();
ui.render();
print("theme reset; done");

print("-- holding display. run `kill` from another shell to clear --");
while (true) sleep(60);
