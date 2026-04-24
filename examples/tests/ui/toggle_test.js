// toggle_test.js -- JS mirror of toggle_test.lua.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var status = ui.Banner({ text: "status: IDLE", style: "info", height: 28 });
var toggles;  // filled in below; onChange closures capture by reference

function refreshStatus() {
    var power = toggles[0].value ? "RUNNING" : "IDLE";
    var pump  = toggles[1].value ? "flowing" : "stopped";
    var valve = toggles[2].value ? "OPEN" : "CLOSED";
    status.set({
        text: "status: " + power + " | pump " + pump + " | valve " + valve,
        style: toggles[0].value ? "good" : "info",
    });
}

function onAnyChange(v, e) {
    refreshStatus();
    var who = e.widget.label || e.widget.onLabel || "?";
    print("[toggle] " + who + " -> " + v);
}

toggles = [
    ui.Toggle({ label: "POWER",  height: 28, onChange: onAnyChange }),
    ui.Toggle({ label: "PUMP",   height: 28, onChange: onAnyChange }),
    ui.Toggle({ label: "VALVE",  height: 28,
                onLabel: "OPEN", offLabel: "CLOSED",
                onColor: "info", offColor: "warn",
                onChange: onAnyChange }),
    ui.Toggle({ label: "E-STOP", height: 28,
                onColor: "good", offColor: "bad",
                onChange: onAnyChange }),
    ui.Toggle({ label: "LOCKED", height: 28, value: true, enabled: false,
                onChange: onAnyChange }),
];

var root = ui.VBox({ padding: 8, gap: 6, children: [
    status,
    ui.Card({ padding: 6, children: [
        ui.VBox({ padding: 0, gap: 6, children: toggles })
    ]})
]});

ui.mount(mon, root);
refreshStatus();
print("toggle_test running - tap each toggle to flip. LOCKED should ignore taps.");
ui.run();
