// sparkline_test.js -- JS mirror of sparkline_test.lua.
//
// Three streaming sparklines validating API symmetry with the Lua port.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var s1 = ui.Sparkline({ width: 880, height: 70, capacity: 120,
                         color: "info" });
var s2 = ui.Sparkline({ width: 880, height: 70, capacity: 120,
                         min: 0, max: 100, color: "good",
                         fill: true, showLast: true });
var s3 = ui.Sparkline({ width: 880, height: 70, capacity: 60,
                         min: 0, max: 100, color: "warn",
                         baseline: 50, fill: true, showLast: true });

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "sparkline_test -- three time series streaming",
                style: "info", height: 28 }),
    ui.Card({ padding: 6, children: [
        ui.VBox({ padding: 0, gap: 6, children: [s1, s2, s3] })
    ]})
]});

ui.mount(mon, root);
print("sparkline_test running - watch traces scroll; ctrl-C to exit.");

var t = 0;
setInterval(function() {
    t += 0.15;
    s1.push(Math.sin(t * 0.8) * 20 + Math.sin(t * 2.3) * 6);
    s2.push(50 + 45 * Math.sin(t * 0.5));
    s3.push(50 + 30 * Math.sin(t * 1.2) + (Math.random() - 0.5) * 15);
    ui.invalidate();
}, 150);

ui.run();
