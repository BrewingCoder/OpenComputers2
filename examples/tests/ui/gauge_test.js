// gauge_test.js -- JS mirror of gauge_test.lua.
//
// Three animated gauges validating API symmetry with the Lua port.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var g1 = ui.Gauge({ width: 220, height: 100, value: 0,  showValue: true });
var g2 = ui.Gauge({ width: 220, height: 100, value: 40, label: "TEMP",
                    startDeg: 0, sweepDeg: 360, thickness: 10, color: "info" });
var g3 = ui.Gauge({ width: 220, height: 100, value: 70, showValue: true,
                    startDeg: 270, sweepDeg: 180, color: "bad", thickness: 12 });

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "gauge_test -- three dials animating", style: "info", height: 28 }),
    ui.Card({ padding: 6, children: [
        ui.HBox({ padding: 0, gap: 8, children: [g1, g2, g3] })
    ]})
]});

ui.mount(mon, root);
print("gauge_test running - watch dials sweep; ctrl-C to exit.");

var t = 0;
setInterval(function() {
    t += 0.2;
    g1.set({ value: 50 + 49 * Math.sin(t * 1.0) });
    g2.set({ value: 50 + 49 * Math.sin(t * 0.6 + 1.0) });
    g3.set({ value: 50 + 49 * Math.sin(t * 1.4 + 2.0) });
}, 200);

ui.run();
