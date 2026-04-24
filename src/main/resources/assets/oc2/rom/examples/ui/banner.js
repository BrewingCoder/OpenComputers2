// /rom/examples/ui/banner.js -- visual showcase for ui_v1.Banner (JS).
//
// Mirror of /rom/examples/ui/banner.lua.
//   run /rom/examples/ui/banner.js
//
// Setup: computer wired to a 2x2 (or larger) monitor group.
//
// Covers every Banner prop:
//   [1] default style banners (good/warn/bad/info/none) with text
//   [2] alignment: left / center / right in same style
//   [3] edgeAccent width variations
//   [4] color override beats style mapping
//   [5] empty text: bg+accent still render
//   [6] visible=false (blank)
//   [7] set() mutation: info -> bad, new text
//   [8] theme swap (on -> orange)

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

// [1] Five styles, one per row.
var styles = ["good", "warn", "bad", "info", "none"];
for (var i = 0; i < styles.length; i++) {
    ui.mount(mon, ui.Banner({
        x:8, y:8 + i * 20, width:240, height:16,
        style:styles[i], text:styles[i].toUpperCase() + " status",
    }));
}

// [2] Alignment variations.
ui.mount(mon, ui.Label({ x:260, y:8, width:180, height:12,
    text:"alignment:", color:"muted", align:"left" }));
ui.mount(mon, ui.Banner({
    x:260, y:24, width:200, height:16,
    style:"info", text:"LEFT", align:"left",
}));
ui.mount(mon, ui.Banner({
    x:260, y:44, width:200, height:16,
    style:"info", text:"CENTER", align:"center",
}));
ui.mount(mon, ui.Banner({
    x:260, y:64, width:200, height:16,
    style:"info", text:"RIGHT", align:"right",
}));

// [3] edgeAccent width sweep.
ui.mount(mon, ui.Label({ x:8, y:116, width:280, height:12,
    text:"edgeAccent: 0 / 2 / 4 / 8 / 16:", color:"muted", align:"left" }));
var accents = [0, 2, 4, 8, 16];
for (var j = 0; j < accents.length; j++) {
    ui.mount(mon, ui.Banner({
        x:8, y:132 + j * 20, width:200, height:16,
        style:"good", edgeAccent:accents[j], text:"edge=" + accents[j],
    }));
}

// [4] color override.
ui.mount(mon, ui.Label({ x:260, y:116, width:200, height:12,
    text:"color override (magenta):", color:"muted", align:"left" }));
ui.mount(mon, ui.Banner({
    x:260, y:132, width:200, height:16,
    style:"good", color:0xFFFF00FF | 0, text:"OVERRIDE",
}));

// [5] empty text.
ui.mount(mon, ui.Label({ x:260, y:152, width:200, height:12,
    text:"empty text (accent only):", color:"muted", align:"left" }));
ui.mount(mon, ui.Banner({
    x:260, y:168, width:200, height:16,
    style:"bad",
}));

// [6] visible=false.
ui.mount(mon, ui.Label({ x:260, y:188, width:200, height:12,
    text:"below: visible=false (blank)", color:"muted", align:"left" }));
ui.mount(mon, ui.Banner({
    x:260, y:204, width:200, height:16,
    style:"bad", text:"HIDDEN", visible:false,
}));

// [7] set() mutation.
var mut = ui.Banner({ x:260, y:224, width:200, height:16,
                      style:"info", text:"BEFORE" });
mut.set({ style:"bad", text:"AFTER set()" });
ui.mount(mon, mut);

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
print("'good' accent now orange for 5s");
sleep(5);
ui.setTheme();
ui.render();
print("theme reset; done");

print("-- holding display. run `kill` from another shell to clear --");
while (true) sleep(60);
