// stack_test.js -- JS mirror of stack_test.lua.
//
// Four Stack layered scenarios: rect layers, icon-over-card,
// bar+label overlay, interactive button intercepting clicks.

var ui = require("ui_v1");
var mon = peripheral.find("monitor");
if (!mon) throw new Error("no monitor found");

mon.clear();
mon.clearPixels(0xFF0A0F1A | 0);

var clicks = 0;

function slot(title, body) {
    return ui.VBox({ padding: 0, gap: 4, children: [
        body,
        ui.Label({ text: title, align: "center", color: "muted" }),
    ]});
}

var clickLabel = ui.Label({ id: "clicks", text: "0 clicks", align: "center", color: "hi" });

var interactiveStack = ui.Stack({ width: 140, height: 80, children: [
    ui.Icon({ shape: "rect", color: (0xFF233042 | 0), bg: "bgCard", border: "edge" }),
    ui.Button({ label: "CLICK",
        onClick: function(e) {
            clicks = clicks + 1;
            clickLabel.set({ text: clicks + " clicks" });
        },
    }),
]});

var root = ui.VBox({ padding: 8, gap: 6, children: [
    ui.Banner({ text: "stack_test -- overlay layers; click right slot to count",
                style: "info", height: 28 }),
    ui.Card({ padding: 8, children: [
        ui.HBox({ padding: 0, gap: 10, children: [
            slot("RECTS", ui.Stack({ width: 140, height: 80, children: [
                ui.Icon({ shape: "rect", color: (0xFF1B4FB0 | 0) }),
                ui.Icon({ shape: "rect", color: (0xFF2ECC7188 | 0) }),
                ui.Icon({ shape: "circle", color: "bad" }),
            ]})),
            slot("OVER CARD", ui.Stack({ width: 140, height: 80, children: [
                ui.Icon({ shape: "rect", color: "bgCard", border: "edge" }),
                ui.Icon({ shape: "diamond", color: "warn" }),
            ]})),
            slot("BAR+LABEL", ui.Stack({ width: 140, height: 80, children: [
                ui.Bar({ orientation: "h", value: 60, min: 0, max: 100, color: "good" }),
                ui.Label({ text: "60%", align: "center", color: "hi" }),
            ]})),
            slot("CLICK ME", interactiveStack),
        ]}),
    ]}),
    ui.Label({ text: "Click the rightmost slot: counter increments.",
               align: "center", color: "muted" }),
    clickLabel,
]});

ui.mount(mon, root);
ui.render(mon);
print("stack_test running -- four overlay examples; click rightmost slot.");

ui.run();
