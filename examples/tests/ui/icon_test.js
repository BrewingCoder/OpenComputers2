// icon_test.js -- JS mirror of icon_test.lua.
//
// Five icons showcasing each shape; identical layout to the Lua port.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var PLUS = [
    [0,0,1,0,0],
    [0,0,1,0,0],
    [1,1,1,1,1],
    [0,0,1,0,0],
    [0,0,1,0,0],
];

function slot(label, iconWidget) {
    return ui.VBox({ padding: 0, gap: 4, children: [
        iconWidget,
        ui.Label({ text: label, align: "center", color: "muted" }),
    ]});
}

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "icon_test -- five shapes: rect, circle, diamond, triangle, bits",
                style: "info", height: 28 }),
    ui.Card({ padding: 8, children: [
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("RECT",     ui.Icon({ width: 140, height: 80, shape: "rect",     color: "info",
                                       bg: "bgCard", border: "edge" })),
            slot("CIRCLE",   ui.Icon({ width: 140, height: 80, shape: "circle",   color: "good",
                                       bg: "bgCard", border: "edge" })),
            slot("DIAMOND",  ui.Icon({ width: 140, height: 80, shape: "diamond",  color: "warn",
                                       bg: "bgCard", border: "edge" })),
            slot("TRIANGLE", ui.Icon({ width: 140, height: 80, shape: "triangle", color: "bad",
                                       bg: "bgCard", border: "edge" })),
            slot("PLUS",     ui.Icon({ width: 140, height: 80, shape: "bits", bits: PLUS,
                                       color: "hi", bg: "bgCard", border: "edge" })),
        ]})
    ]})
]});

ui.mount(mon, root);
ui.render(mon);
print("icon_test running - five shapes rendered; ctrl-C to exit.");

ui.run();
