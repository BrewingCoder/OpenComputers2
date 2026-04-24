// /rom/examples/ui/container.js -- visual showcase for ui_v1
// layout containers (VBox, HBox, Spacer, Card). Mirror of container.lua.
//
//   run /rom/examples/ui/container.js

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clearPixels(0xFF000000 | 0);
mon.setBackgroundColor(0xFF000000 | 0);
mon.clear();

function section(x, y, w, text) {
    ui.mount(mon, ui.Label({
        x:x, y:y, width:w, height:12,
        text:text, color:"muted", align:"left",
    }));
}

// [1] VBox + HBox with fixed-size children
section(4, 4, 300, "[1] VBox + HBox, fixed children (3 colored rows / cols)");
ui.mount(mon, ui.VBox({
    x:4, y:20, width:140, height:60, padding:2, gap:2,
    children: [
        ui.Label({ height:16, text:" VBox top",    bg:"good", color:"fg", align:"left" }),
        ui.Label({ height:16, text:" VBox middle", bg:"info", color:"fg", align:"left" }),
        ui.Label({ height:16, text:" VBox bottom", bg:"warn", color:"fg", align:"left" }),
    ],
}));
ui.mount(mon, ui.HBox({
    x:150, y:20, width:200, height:60, padding:2, gap:2,
    children: [
        ui.Label({ width:60, text:"L", bg:"good", color:"fg", align:"center" }),
        ui.Label({ width:60, text:"M", bg:"info", color:"fg", align:"center" }),
        ui.Label({ width:60, text:"R", bg:"warn", color:"fg", align:"center" }),
    ],
}));

// [2] Spacer eats remainder
section(4, 90, 300, "[2] Spacer pushes siblings to opposite ends");
ui.mount(mon, ui.VBox({
    x:4, y:106, width:140, height:60,
    children: [
        ui.Label({ height:16, text:" top",    bg:"good", color:"fg", align:"left" }),
        ui.Spacer({}),
        ui.Label({ height:16, text:" bottom", bg:"bad",  color:"fg", align:"left" }),
    ],
}));
ui.mount(mon, ui.HBox({
    x:150, y:106, width:200, height:60,
    children: [
        ui.Label({ width:40, text:"L", bg:"good", color:"fg", align:"center" }),
        ui.Spacer({}),
        ui.Label({ width:40, text:"R", bg:"bad",  color:"fg", align:"center" }),
    ],
}));

// [3] Flex 1:3
section(4, 176, 300, "[3] HBox flex 1:3 (a takes 1/4, b takes 3/4)");
ui.mount(mon, ui.HBox({
    x:4, y:192, width:340, height:24,
    children: [
        ui.Label({ flex:1, text:"1", bg:"good", color:"fg", align:"center" }),
        ui.Label({ flex:3, text:"3", bg:"info", color:"fg", align:"center" }),
    ],
}));

// [4] padding + gap
section(4, 224, 340, "[4] VBox padding=8 + gap=4 (inset + separated rows)");
ui.mount(mon, ui.VBox({
    x:4, y:240, width:340, height:80, padding:8, gap:4, bg:"bgCard", border:"edge",
    children: [
        ui.Label({ height:14, text:"  row 1", bg:"good", color:"fg", align:"left" }),
        ui.Label({ height:14, text:"  row 2", bg:"info", color:"fg", align:"left" }),
        ui.Label({ height:14, text:"  row 3", bg:"warn", color:"fg", align:"left" }),
    ],
}));

// [5] Default Card
section(360, 4, 260, "[5] Card (default: padding 4, bgCard, edge border)");
ui.mount(mon, ui.Card({
    x:360, y:20, width:260, height:60,
    children: [
        ui.Label({ height:14, text:" Card holds any child tree", color:"hi", align:"left" }),
        ui.Label({ height:14, text:" inner rect = outer - pad*2", color:"fg", align:"left" }),
    ],
}));

// [6] Card custom bg, borderless
section(360, 90, 260, "[6] Card bg='good', border=0 (no outline)");
ui.mount(mon, ui.Card({
    x:360, y:106, width:260, height:40, bg:"good", border:0,
    children: [
        ui.Label({ height:14, text:" Borderless accent card", color:"bg", align:"center" }),
    ],
}));

// [7] Nested HBox inside Card
section(360, 156, 260, "[7] Nested tree: Card > VBox > HBox");
ui.mount(mon, ui.Card({
    x:360, y:172, width:260, height:96,
    children: [
        ui.VBox({
            gap:2,
            children: [
                ui.Label({ height:14, text:"HEADER", color:"hi", align:"center", bg:"edge" }),
                ui.HBox({
                    height:20, gap:2,
                    children: [
                        ui.Label({ flex:1, text:"A", bg:"good", color:"fg", align:"center" }),
                        ui.Label({ flex:1, text:"B", bg:"info", color:"fg", align:"center" }),
                        ui.Label({ flex:1, text:"C", bg:"warn", color:"fg", align:"center" }),
                    ],
                }),
                ui.Label({ height:14, text:"FOOTER", color:"muted", align:"center" }),
            ],
        }),
    ],
}));

// [8] visible=false
section(360, 280, 260, "[8] middle child visible=false (gap closes)");
ui.mount(mon, ui.VBox({
    x:360, y:296, width:260, height:50, gap:2,
    children: [
        ui.Label({ height:14, text:" visible A", bg:"good", color:"fg", align:"left" }),
        ui.Label({ height:14, text:" HIDDEN",    bg:"bad",  color:"fg", align:"left", visible:false }),
        ui.Label({ height:14, text:" visible C", bg:"info", color:"fg", align:"left" }),
    ],
}));

// [9] set() mutation on a nested tree
section(4, 330, 340, "[9] set() mutation: live padding + bg swap");
var mutCard = ui.Card({ x:4, y:346, width:340, height:60, padding:2, bg:"bgCard",
    children: [
        ui.Label({ height:14, text:" padding=2 (tight)", color:"hi", align:"left" }),
    ],
});
ui.mount(mon, mutCard);

ui.render();
print("initial layout rendered; mutating in 3s...");
sleep(3);

mutCard.set({ padding:14, bg:"info" });
mutCard.layout();
ui.render();
print("padding=14, bg=info; reverting in 3s");
sleep(3);

mutCard.set({ padding:2, bg:"bgCard" });
mutCard.layout();
ui.render();
print("reverted; done");

print("-- holding display. run `kill` from another shell to clear --");
while (true) sleep(60);
