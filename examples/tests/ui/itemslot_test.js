// itemslot_test.js -- JS mirror of itemslot_test.lua.
//
// Composite ItemSlot: texture + count label + caption.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

function slot(label, s) {
    return ui.VBox({ padding: 0, gap: 4, children: [
        s,
        ui.Label({ text: label, align: "center", color: "muted" }),
    ]});
}

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "itemslot_test -- composite: texture + count + caption",
                style: "info", height: 28 }),

    ui.Card({ padding: 8, children: [
        ui.Label({ text: "row 1 -- count only", color: "muted" }),
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("REDSTONE", ui.ItemSlot({ size: 72,
                item: "minecraft:redstone", count: 64 })),
            slot("DIAMOND",  ui.ItemSlot({ size: 72,
                item: "minecraft:diamond",  count: 8 })),
            slot("IRON",     ui.ItemSlot({ size: 72,
                item: "minecraft:iron_ingot", count: 420 })),
            slot("GOLD",     ui.ItemSlot({ size: 72,
                item: "minecraft:gold_ingot", count: 12500 })),
            slot("TORCH",    ui.ItemSlot({ size: 72,
                item: "minecraft:torch",    count: 2500000 })),
        ]})
    ]}),

    ui.Card({ padding: 8, children: [
        ui.Label({ text: "row 2 -- count + caption (storage dashboard)", color: "muted" }),
        ui.HBox({ padding: 0, gap: 10, children: [
            ui.ItemSlot({ size: 72,
                item: "minecraft:redstone",   count: 64,   caption: "IN" }),
            ui.ItemSlot({ size: 72,
                item: "minecraft:diamond",    count: 8,    caption: "VAULT" }),
            ui.ItemSlot({ size: 72,
                item: "minecraft:iron_ingot", count: 420,  caption: "SMELT" }),
            ui.ItemSlot({ size: 72,
                item: "minecraft:coal",       count: 1500, caption: "FUEL" }),
            ui.ItemSlot({ size: 72,
                item: "minecraft:diamond_pickaxe", count: 1, caption: "TOOL" }),
        ]})
    ]}),

    ui.Card({ padding: 8, children: [
        ui.Label({ text: "row 3 -- edge cases", color: "muted" }),
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("EMPTY",    ui.ItemSlot({ size: 40 })),
            slot("NO COUNT", ui.ItemSlot({ size: 72,
                item: "minecraft:stone", caption: "STONE" })),
            slot("BIG",      ui.ItemSlot({ size: 72,
                item: "minecraft:coal",  count: 9876543 })),
            slot("CUSTOM",   ui.ItemSlot({ size: 72,
                item: "minecraft:gold_ingot", count: "MAX" })),
            slot("BOGUS",    ui.ItemSlot({ size: 72,
                item: "minecraft:this_item_does_not_exist", count: 1 })),
        ]})
    ]})
]});

ui.mount(mon, root);
ui.render(mon);
print("itemslot_test running - item + count + caption rendered; ctrl-C to exit.");

ui.run();
