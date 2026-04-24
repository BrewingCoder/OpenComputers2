// flow_test.js -- JS mirror of flow_test.lua.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var styles = [ "good", "hi", "warn", "bad", "info" ];
var pads = [ 0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28 ];

var cards = [];
for (var i = 0; i < pads.length; i++) {
    cards.push(ui.Card({ padding: pads[i], children: [
        ui.Label({ text: "pad=" + pads[i], color: styles[i % styles.length], align: "center" })
    ]}));
}

var root = ui.Flow({ padding: 8, gap: 6, children: cards });
ui.mount(mon, root);
ui.render();

print("flow: " + cards.length + " cards, root " + root.width + "x" + root.height);
for (var j = 0; j < cards.length; j++) {
    var c = cards[j];
    print("  [" + j + "] pad=" + pads[j] + " x=" + c.x + " y=" + c.y + " w=" + c.width + " h=" + c.height);
}

while (true) sleep(5);
