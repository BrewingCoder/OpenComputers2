-- stack_test.lua -- Stack overlay container visual check.
--
-- Three layered scenarios demonstrating back-to-front draw order:
--   1. Rect layers -- blue rect with green tint + red center.
--   2. Icon over Card -- Card bg with a diamond Icon centered.
--   3. Bar with Label overlay -- 60% Bar behind a centered label
--      proving you can label a Bar without re-implementing one.
--
-- Click the top-right slot to rotate which layer is on top; the topmost
-- layer should get the click (hit-test walks front-to-back).

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
assert(mon, "no monitor found")

mon:clear()
mon:clearPixels(0xFF0A0F1A)

local clicks = 0

local function slot(title, body)
    return ui.VBox{ padding=0, gap=4, children={
        body,
        ui.Label{ text=title, align="center", color="muted" },
    }}
end

local clickLabel = ui.Label{ id="clicks", text="0 clicks", align="center", color="hi" }

local interactiveStack = ui.Stack{ width=140, height=80, children={
    ui.Icon{ shape="rect", color=0xFF233042, bg="bgCard", border="edge" },
    ui.Button{ label="CLICK",
        onClick=function(e)
            clicks = clicks + 1
            clickLabel:set({ text = clicks .. " clicks" })
        end,
    },
}}

local root = ui.VBox{ padding=8, gap=6, children={
    ui.Banner{ text="stack_test -- overlay layers; click right slot to count",
               style="info", height=28 },
    ui.Card{ padding=8, children={
        ui.HBox{ padding=0, gap=10, children={
            -- Layered rects
            slot("RECTS", ui.Stack{ width=140, height=80, children={
                ui.Icon{ shape="rect", color=0xFF1B4FB0 },
                ui.Icon{ shape="rect", color=0xFF2ECC7188 }, -- semi-transparent green tint
                ui.Icon{ shape="circle", color="bad" },
            }}),
            -- Diamond over Card bg
            slot("OVER CARD", ui.Stack{ width=140, height=80, children={
                ui.Icon{ shape="rect", color="bgCard", border="edge" },
                ui.Icon{ shape="diamond", color="warn" },
            }}),
            -- Bar with overlaid label (the "why you need Stack" case)
            slot("BAR+LABEL", ui.Stack{ width=140, height=80, children={
                ui.Bar{ orientation="h", value=60, min=0, max=100, color="good" },
                ui.Label{ text="60%", align="center", color="hi" },
            }}),
            -- Interactive -- topmost Button intercepts clicks
            slot("CLICK ME", interactiveStack),
        }},
    }},
    ui.Label{ text="Click the rightmost slot: counter increments.",
              align="center", color="muted" },
    clickLabel,
}}

ui.mount(mon, root)
ui.render(mon)
print("stack_test running -- four overlay examples; click rightmost slot.")

ui.run()
