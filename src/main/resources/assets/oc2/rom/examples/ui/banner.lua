-- /rom/examples/ui/banner.lua -- visual showcase for ui_v1.Banner.
--
--   run /rom/examples/ui/banner.lua
--
-- Setup: computer wired to a 2x2 (or larger) monitor group.
--
-- Covers every Banner prop:
--   [1] default style banners (good/warn/bad/info/none) with text
--   [2] alignment: left / center / right in same style
--   [3] edgeAccent width variations
--   [4] color override beats style mapping
--   [5] empty text: bg+accent still render
--   [6] visible=false (blank)
--   [7] set() mutation: info -> bad, new text
--   [8] theme swap (on -> orange)

local ui = require("ui_v1")
local mon = peripheral.find("monitor")
if not mon then error("no monitor found") end

mon:clearPixels(0xFF000000)
mon:setBackgroundColor(0xFF000000)
mon:clear()

-- [1] Five styles, one per row.
local styles = { "good", "warn", "bad", "info", "none" }
for i, s in ipairs(styles) do
    ui.mount(mon, ui.Banner{
        x=8, y=8 + (i - 1) * 20, width=240, height=16,
        style=s, text=string.upper(s).." status",
    })
end

-- [2] Alignment variations.
ui.mount(mon, ui.Label{ x=260, y=8, width=180, height=12,
    text="alignment:", color="muted", align="left" })
ui.mount(mon, ui.Banner{
    x=260, y=24, width=200, height=16,
    style="info", text="LEFT", align="left",
})
ui.mount(mon, ui.Banner{
    x=260, y=44, width=200, height=16,
    style="info", text="CENTER", align="center",
})
ui.mount(mon, ui.Banner{
    x=260, y=64, width=200, height=16,
    style="info", text="RIGHT", align="right",
})

-- [3] edgeAccent width sweep.
ui.mount(mon, ui.Label{ x=8, y=116, width=280, height=12,
    text="edgeAccent: 0 / 2 / 4 / 8 / 16:", color="muted", align="left" })
local accents = { 0, 2, 4, 8, 16 }
for i, a in ipairs(accents) do
    ui.mount(mon, ui.Banner{
        x=8, y=132 + (i - 1) * 20, width=200, height=16,
        style="good", edgeAccent=a, text="edge="..a,
    })
end

-- [4] color override.
ui.mount(mon, ui.Label{ x=260, y=116, width=200, height=12,
    text="color override (magenta):", color="muted", align="left" })
ui.mount(mon, ui.Banner{
    x=260, y=132, width=200, height=16,
    style="good", color=0xFFFF00FF, text="OVERRIDE",
})

-- [5] empty text.
ui.mount(mon, ui.Label{ x=260, y=152, width=200, height=12,
    text="empty text (accent only):", color="muted", align="left" })
ui.mount(mon, ui.Banner{
    x=260, y=168, width=200, height=16,
    style="bad",
})

-- [6] visible=false.
ui.mount(mon, ui.Label{ x=260, y=188, width=200, height=12,
    text="below: visible=false (blank)", color="muted", align="left" })
ui.mount(mon, ui.Banner{
    x=260, y=204, width=200, height=16,
    style="bad", text="HIDDEN", visible=false,
})

-- [7] set() mutation.
local mut = ui.Banner{ x=260, y=224, width=200, height=16,
                       style="info", text="BEFORE" }
mut:set{ style="bad", text="AFTER set()" }
ui.mount(mon, mut)

ui.render()
print("rendered; swapping theme in 3s...")
sleep(3)

ui.setTheme{
    bg=0xFF0A0F1A, bgCard=0xFF121827, fg=0xFFFFFFFF,
    hi=0xFFE6F0FF, muted=0xFF7A8597,
    good=0xFFFF8800,  -- orange instead of green
    warn=0xFFF1C40F, bad=0xFFE74C3C, info=0xFF3498DB,
    edge=0xFF2E3A4E, primary=0xFF3498DB, ghost=0xFF2E3A4E, danger=0xFFE74C3C,
}
ui.render()
print("'good' accent now orange for 5s")
sleep(5)
ui.setTheme()
ui.render()
print("theme reset; done")

print("-- holding display. run `kill` from another shell to clear --")
while true do sleep(60) end
