// icon_item_test.js -- JS mirror of icon_item_test.lua.
//
// Six item-texture icons + four aspect/edge cases.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

function slot(label, iconWidget) {
    return ui.VBox({ padding: 0, gap: 4, children: [
        iconWidget,
        ui.Label({ text: label, align: "center", color: "muted" }),
    ]});
}

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "icon_item_test -- item textures drawn above text layer",
                style: "info", height: 28 }),
    ui.Card({ padding: 8, children: [
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("REDSTONE", ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:redstone", bg: "bgCard", border: "edge" })),
            slot("DIAMOND",  ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:diamond",  bg: "bgCard", border: "edge" })),
            slot("IRON",     ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:iron_ingot", bg: "bgCard", border: "edge" })),
            slot("GOLD",     ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:gold_ingot", bg: "bgCard", border: "edge" })),
            slot("TORCH",    ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:torch",    bg: "bgCard", border: "edge" })),
            slot("PICKAXE",  ui.Icon({ width: 120, height: 120, shape: "item",
                                       item: "minecraft:diamond_pickaxe", bg: "bgCard", border: "edge" })),
        ]})
    ]}),
    ui.Card({ padding: 8, children: [
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("WIDE",   ui.Icon({ width: 200, height: 60, shape: "item",
                                     item: "minecraft:stone", bg: "bgCard", border: "edge" })),
            slot("TALL",   ui.Icon({ width: 60, height: 200, shape: "item",
                                     item: "minecraft:coal", bg: "bgCard", border: "edge" })),
            slot("EMPTY",  ui.Icon({ width: 120, height: 120, shape: "item",
                                     bg: "bgCard", border: "edge" })),
            slot("BOGUS",  ui.Icon({ width: 120, height: 120, shape: "item",
                                     item: "minecraft:this_item_does_not_exist",
                                     bg: "bgCard", border: "edge" })),
        ]})
    ]})
]});

ui.mount(mon, root);
ui.render(mon);
print("icon_item_test running - item textures rendered; ctrl-C to exit.");

ui.run();
