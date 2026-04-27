-- produce.lua — "I need N of <output>; figure it out."
--
-- Usage:
--   run produce.lua minecraft:copper_ingot 64
--   run produce.lua mekanism:ingot_osmium 32
--
-- The script asks every attached bridge "can you produce this?" and the
-- first machine that says yes runs the recipe. Inputs are sourced from
-- whatever `inventory.find()` resolves to on this channel.
--
-- Single-step only — no recipe chaining yet.

local args = { ... }
local outputId = args[1]
local count = tonumber(args[2])

if not outputId or not count then
    print("usage: produce <itemId> <count>")
    print("  e.g. produce minecraft:copper_ingot 64")
    return
end

local source = inventory.find()
if not source then error("no inventory peripheral on this channel") end

local q = recipes(outputId)
local machine = q.producers[1]
if not machine then
    print("no attached machine can produce " .. outputId)
    return
end

-- Pick the first input form whose items are already in `source`.
local inputId
for _, id in ipairs(q.inputs) do
    local cs = source:find(id)
    if cs and cs > 0 then inputId = id; break end
end
if not inputId then
    print("no input items for " .. outputId .. " found in source")
    return
end

-- Defer to smelt.lua's loop body — same pattern, capped.
local recipes_lib = require("recipes")
local t0 = os.clock()
local pulled, err = recipes_lib.smelt(inputId, source, source, { max = count })
local dt = os.clock() - t0

if not pulled then
    print("produce failed: " .. tostring(err))
    return
end

print(string.format("produced: %d %s in %.2fs (%.0f items/s)",
    pulled, outputId, dt, (dt > 0) and (pulled / dt) or 0))
