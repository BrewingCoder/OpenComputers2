-- fluidslot_test.lua -- composite ItemSlot with fluid prop.
--
-- Row 1: five ItemSlots with fluid textures + count labels (mB amounts).
-- Row 2: counts + captions (tank dashboard pattern).
-- Row 3: edge cases — no count, big count, empty, bogus id.
--
-- Tests drawFluid primitive + ItemSlot fluid routing.

local ui = require("ui_v1")
local mon = peripheral.find("monitor") or error("no monitor")
mon:clear()
mon:clearPixels(0xFF0A0F1A)

local function slot(label, s)
    return ui.VBox{ padding=0, gap=4, children={
        s,
        ui.Label{ text=label, align="center", color="muted" },
    }}
end

local root = ui.VBox{ padding=8, gap=6, children={
    ui.Banner{ text="fluidslot_test -- fluid textures + count + caption",
               style="info", height=28 },

    ui.Card{ padding=8, children={
        ui.Label{ text="row 1 -- fluids w/ mB counts", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            slot("WATER", ui.ItemSlot{ size=72,
                fluid="minecraft:water", count=1000 }),
            slot("LAVA",  ui.ItemSlot{ size=72,
                fluid="minecraft:lava", count=500 }),
            slot("MILK",  ui.ItemSlot{ size=72,
                fluid="minecraft:milk", count=64 }),
            slot("BIG",   ui.ItemSlot{ size=72,
                fluid="minecraft:water", count=128000 }),
            slot("HUGE",  ui.ItemSlot{ size=72,
                fluid="minecraft:lava", count=9500000 }),
        }},
    }},

    ui.Card{ padding=8, children={
        ui.Label{ text="row 2 -- count + caption (tank dashboard)", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            ui.ItemSlot{ size=72,
                fluid="minecraft:water", count=1000, caption="COOL" },
            ui.ItemSlot{ size=72,
                fluid="minecraft:lava",  count=500,  caption="HEAT" },
            ui.ItemSlot{ size=72,
                fluid="minecraft:water", count=64000, caption="BOIL" },
            ui.ItemSlot{ size=72,
                fluid="minecraft:milk",  count=250, caption="CALF" },
            ui.ItemSlot{ size=72,
                fluid="minecraft:lava",  count=1, caption="DROP" },
        }},
    }},

    ui.Card{ padding=8, children={
        ui.Label{ text="row 3 -- edge cases", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            slot("EMPTY",    ui.ItemSlot{ size=40 }),
            slot("NO COUNT", ui.ItemSlot{ size=72,
                fluid="minecraft:water", caption="CLEAR" }),
            slot("BIG",      ui.ItemSlot{ size=72,
                fluid="minecraft:lava", count=9876543 }),
            slot("CUSTOM",   ui.ItemSlot{ size=72,
                fluid="minecraft:water", count="MAX" }),
            slot("BOGUS",    ui.ItemSlot{ size=72,
                fluid="minecraft:does_not_exist", count=1 }),
        }},
    }},
}}

ui.mount(mon, root)
ui.render(mon)
print("fluidslot_test running - fluid + count + caption rendered.")
print("ctrl-C to exit.")

ui.run()
