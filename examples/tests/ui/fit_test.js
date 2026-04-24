// fit_test.js -- JS mirror of fit_test.lua. See that file for expectations.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var root = ui.VBox({
    padding: 8,
    gap: 6,
    children: [
        ui.Card({ children: [ ui.Label({ text: "HUG-SIZED", color: "good", align: "center" }) ] }),
        ui.Card({ children: [ ui.Label({ text: "a slightly longer label inside a Card", color: "hi", align: "center" }) ] }),
        ui.Banner({ text: "flex Card below stretches to fill remainder", style: "info" }),
        ui.Card({ flex: 1, children: [
            ui.Label({ text: "flex=1 (fills rest of VBox)", color: "warn", align: "center" })
        ]}),
    ],
});

ui.mount(mon, root);
ui.render();

print("fit_test: cards 1 & 2 should be same tight height, card 3 fills.");
print("card1 size = " + root.children[0].width + "x" + root.children[0].height);
print("card2 size = " + root.children[1].width + "x" + root.children[1].height);
print("card4 size = " + root.children[3].width + "x" + root.children[3].height + " (should be tall)");

// Hold the lease so the painted pixels stay on screen while a human inspects.
while (true) sleep(5);
