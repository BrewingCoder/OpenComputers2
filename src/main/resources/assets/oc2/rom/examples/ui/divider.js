// /rom/examples/ui/divider.js -- visual showcase for ui_v1.Divider (JS).
//
// Mirror of /rom/examples/ui/divider.lua. Shipped in ROM so every computer has it:
//   run /rom/examples/ui/divider.js
//
// Setup: computer wired to a 2x2 (or larger) monitor group.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

// Wipe every layer before we render.
mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

// [1] Banner
ui.mount(mon, ui.Label({
    x:0, y:0, width:480, height:12,
    text:"ui_v1 Divider showcase",
    color:"hi", bg:"bgCard", align:"center",
}));

// [2] Horizontal dividers of increasing thickness.
ui.mount(mon, ui.Label({ x:8, y:24, width:300, height:12,
    text:"horizontal thickness 1 / 3 / 6:", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:8, y:44,  width:320, height:4,  thickness:1 }));
ui.mount(mon, ui.Divider({ x:8, y:60,  width:320, height:6,  thickness:3 }));
ui.mount(mon, ui.Divider({ x:8, y:80,  width:320, height:10, thickness:6 }));

// [3] Color token palette.
var tokens = ["edge","muted","good","warn","bad","info","hi"];
for (var i = 0; i < tokens.length; i++) {
    var by = 104 + i * 14;
    ui.mount(mon, ui.Label({ x:8, y:by, width:60, height:12,
        text:tokens[i], color:"muted", align:"left" }));
    ui.mount(mon, ui.Divider({
        x:76, y:by + 5, width:240, height:2, thickness:2, color:tokens[i],
    }));
}

// [4] Vertical dividers of increasing thickness.
ui.mount(mon, ui.Label({ x:360, y:24, width:200, height:12,
    text:"vertical thickness 1 / 3 / 6:", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:370, y:44, width:4,  height:120, orientation:"v", thickness:1 }));
ui.mount(mon, ui.Divider({ x:400, y:44, width:6,  height:120, orientation:"v", thickness:3 }));
ui.mount(mon, ui.Divider({ x:430, y:44, width:10, height:120, orientation:"v", thickness:6 }));

// [5] Vertical divider with color token.
ui.mount(mon, ui.Label({ x:360, y:176, width:180, height:12,
    text:"vertical 'hi':", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:400, y:196, width:8, height:60,
    orientation:"v", thickness:4, color:"hi" }));

// [6] visible=false.
ui.mount(mon, ui.Label({ x:8, y:208, width:320, height:12,
    text:"below: visible=false (blank)", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:8, y:224, width:320, height:4, thickness:2, visible:false }));

// [7] set() mutation.
var mut = ui.Divider({ x:8, y:240, width:320, height:6, thickness:1, color:"edge" });
mut.set({ color:"bad", thickness:4 });
ui.mount(mon, mut);
ui.mount(mon, ui.Label({ x:8, y:252, width:320, height:12,
    text:"mutated via set() -> bad, thickness=4", color:"muted", align:"left" }));

// [8] Hex color override (magenta).
ui.mount(mon, ui.Label({ x:8, y:272, width:320, height:12,
    text:"hex 0xFFFF00FF (magenta):", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:8, y:290, width:320, height:4, thickness:2, color:0xFFFF00FF | 0 }));

// [9] Thickness clamp: thickness=0 still renders 1 px.
ui.mount(mon, ui.Label({ x:8, y:302, width:320, height:12,
    text:"thickness=0 (clamps to 1):", color:"muted", align:"left" }));
ui.mount(mon, ui.Divider({ x:8, y:320, width:320, height:4, thickness:0 }));

ui.render();
print("rendered; swapping theme in 3s...");
sleep(3);

ui.setTheme({
    bg: 0xFF0A0F1A | 0, bgCard: 0xFF121827 | 0, fg: 0xFFFFFFFF | 0,
    hi: 0xFFE6F0FF | 0, muted: 0xFF7A8597 | 0,
    good: 0xFF2ECC71 | 0, warn: 0xFFF1C40F | 0, bad: 0xFFE74C3C | 0, info: 0xFF3498DB | 0,
    edge: 0xFFFF8800 | 0,  // orange instead of dark slate
    primary: 0xFF3498DB | 0, ghost: 0xFF2E3A4E | 0, danger: 0xFFE74C3C | 0,
});
ui.render();
print("'edge' dividers now orange for 5s");
sleep(5);
ui.setTheme();
ui.render();
print("theme reset; done");

// Hold the lease so the monitor keeps showing the result until killed.
print("-- holding display. run `kill` from another shell to clear --");
while (true) sleep(60);
