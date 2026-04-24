-- chemicalslot_test.lua -- composite ItemSlot with chemical prop (Mekanism soft-dep).
--
-- Row 1: five ItemSlots with chemical textures + count labels (mB amounts).
-- Row 2: counts + captions (Mekanism chem tank dashboard pattern).
-- Row 3: edge cases — no count, big count, empty, bogus id.
--
-- Tests drawChemical primitive + ItemSlot chemical routing. Without Mekanism
-- loaded, icon draws are silent no-ops — slot chrome + labels still render.

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
    ui.Banner{ text="chemicalslot_test -- Mekanism chemical textures + count + caption",
               style="info", height=28 },

    ui.Card{ padding=8, children={
        ui.Label{ text="row 1 -- chemicals w/ mB counts", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            slot("H2",   ui.ItemSlot{ size=72,
                chemical="mekanism:hydrogen", count=1000 }),
            slot("O2",   ui.ItemSlot{ size=72,
                chemical="mekanism:oxygen",  count=500 }),
            slot("STEAM",ui.ItemSlot{ size=72,
                chemical="mekanism:steam",   count=64 }),
            slot("H2O2", ui.ItemSlot{ size=72,
                chemical="mekanism:hydrogen_chloride", count=128000 }),
            slot("Po",   ui.ItemSlot{ size=72,
                chemical="mekanism:polonium", count=9500000 }),
        }},
    }},

    ui.Card{ padding=8, children={
        ui.Label{ text="row 2 -- count + caption (chem tank dashboard)", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            ui.ItemSlot{ size=72,
                chemical="mekanism:hydrogen", count=1000, caption="H2" },
            ui.ItemSlot{ size=72,
                chemical="mekanism:oxygen",   count=500,  caption="O2" },
            ui.ItemSlot{ size=72,
                chemical="mekanism:sulfuric_acid", count=64000, caption="H2SO4" },
            ui.ItemSlot{ size=72,
                chemical="mekanism:lithium",  count=250, caption="Li" },
            ui.ItemSlot{ size=72,
                chemical="mekanism:uranium_hexafluoride", count=1, caption="UF6" },
        }},
    }},

    ui.Card{ padding=8, children={
        ui.Label{ text="row 3 -- edge cases", color="muted" },
        ui.HBox{ padding=0, gap=10, children={
            slot("EMPTY",    ui.ItemSlot{ size=40 }),
            slot("NO COUNT", ui.ItemSlot{ size=72,
                chemical="mekanism:hydrogen", caption="CLEAR" }),
            slot("BIG",      ui.ItemSlot{ size=72,
                chemical="mekanism:oxygen", count=9876543 }),
            slot("CUSTOM",   ui.ItemSlot{ size=72,
                chemical="mekanism:hydrogen", count="MAX" }),
            slot("BOGUS",    ui.ItemSlot{ size=72,
                chemical="mekanism:does_not_exist", count=1 }),
        }},
    }},
}}

ui.mount(mon, root)
ui.render(mon)
print("chemicalslot_test running - chemical + count + caption rendered.")
print("(Mekanism soft-dep: icons are no-ops if Mekanism isn't loaded.)")
print("ctrl-C to exit.")

ui.run()
