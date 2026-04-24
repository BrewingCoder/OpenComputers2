// /rom/examples/ui/button.js -- visual + interactive showcase for ui_v1.Button (JS).
//
// Mirror of /rom/examples/ui/button.lua.
//   run /rom/examples/ui/button.js
//
// Setup: computer wired to a 2x2 (or larger) monitor group.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

// [1] Style sweep.
ui.mount(mon, ui.Label({ x:8, y:8, width:200, height:12,
    text:"styles:", color:"muted", align:"left" }));
var styles = ["primary", "ghost", "danger"];
for (var i = 0; i < styles.length; i++) {
    ui.mount(mon, ui.Button({
        x:8, y:24 + i * 24, width:180, height:20,
        style:styles[i], label:styles[i].toUpperCase(),
    }));
}

// [2] enabled vs disabled.
ui.mount(mon, ui.Label({ x:200, y:8, width:200, height:12,
    text:"enabled vs disabled:", color:"muted", align:"left" }));
ui.mount(mon, ui.Button({ x:200, y:24, width:180, height:20,
    style:"primary", label:"ENABLED" }));
ui.mount(mon, ui.Button({ x:200, y:48, width:180, height:20,
    style:"primary", label:"DISABLED", enabled:false }));

// [3] color override + custom borderColor.
ui.mount(mon, ui.Label({ x:200, y:76, width:200, height:12,
    text:"color + borderColor overrides:", color:"muted", align:"left" }));
ui.mount(mon, ui.Button({ x:200, y:92, width:180, height:20,
    label:"MAGENTA", color:0xFF8E44AD | 0, borderColor:0xFFFFFFFF | 0 }));

// [4] borderThickness sweep.
ui.mount(mon, ui.Label({ x:8, y:104, width:200, height:12,
    text:"borderThickness: 0 / 1 / 2 / 4:", color:"muted", align:"left" }));
var ths = [0, 1, 2, 4];
for (var t = 0; t < ths.length; t++) {
    ui.mount(mon, ui.Button({
        x:8, y:120 + t * 24, width:180, height:20,
        style:"ghost", label:"t=" + ths[t], borderThickness:ths[t],
    }));
}

// [5] empty label.
ui.mount(mon, ui.Label({ x:200, y:124, width:200, height:12,
    text:"empty label (chrome only):", color:"muted", align:"left" }));
ui.mount(mon, ui.Button({ x:200, y:140, width:180, height:20,
    style:"primary", label:"" }));

// [6] visible=false.
ui.mount(mon, ui.Label({ x:200, y:168, width:200, height:12,
    text:"below: visible=false (blank)", color:"muted", align:"left" }));
ui.mount(mon, ui.Button({ x:200, y:184, width:180, height:20,
    style:"danger", label:"HIDDEN", visible:false }));

// [7] LIVE counter + power toggle.
ui.mount(mon, ui.Label({ x:8, y:224, width:200, height:12,
    text:"INTERACTIVE: click below ->", color:"muted", align:"left" }));

var count = 0;
var counterLabel = ui.Label({ x:8, y:240, width:180, height:20,
    text:"clicks: 0", color:"hi", align:"center" });
ui.mount(mon, counterLabel);

ui.mount(mon, ui.Button({
    x:8, y:264, width:180, height:20,
    style:"primary", label:"CLICK ME",
    onClick: function(e) {
        count++;
        counterLabel.set({ text: "clicks: " + count });
    },
}));

var power = true;
var powerBtn = ui.Button({
    x:200, y:264, width:180, height:20,
    style:"primary", label:"POWER: ON",
    onClick: function(e) {
        power = !power;
        powerBtn.set({
            style: power ? "primary" : "ghost",
            label: power ? "POWER: ON" : "POWER: OFF",
        });
    },
});
ui.mount(mon, powerBtn);

print("showcase mounted. right-click monitor to test interactions.");
print("kill the script from another shell to clear.");
ui.run();
