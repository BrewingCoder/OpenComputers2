-- smelt.lua — ore → ingot processing using the built-in `recipes` and
-- `inventory` globals. No imports.
--
-- Usage from the shell:
--   run smelt.lua minecraft:raw_copper          -- smelt all raw_copper in chest
--   run smelt.lua minecraft:raw_copper 64       -- stop after 64 inputs pushed
--   run smelt.lua mekanism:raw_osmium           -- works for any vanilla smelting
--
-- The chest is whatever `inventory.find()` resolves to on this channel; the
-- smelter is whichever attached bridge says `canConsume(inputId)` first.

local args = { ... }
local inputId = args[1]
local maxItems = tonumber(args[2])

if not inputId then
    print("usage: smelt <itemId> [maxItems]")
    print("  e.g. smelt minecraft:raw_copper")
    print("       smelt mekanism:raw_osmium 64")
    return
end

local source = inventory.find()
if not source then error("no inventory peripheral on this channel") end

local q = recipes(inputId)
local machine = q.consumers[1]
if not machine then
    print("no attached machine can smelt " .. inputId)
    return
end
local outputs = q.outputs
if #outputs == 0 then
    print(machine.name .. " refused getOutputFor(" .. inputId .. ")")
    return
end

local t0 = os.clock()
local pulledTotal = 0
local pushedTotal = 0
local idle = 0

while true do
    local pulled = 0
    for _, oid in ipairs(outputs) do
        pulled = pulled + inventory.drain(machine, oid, { source })
    end
    pulledTotal = pulledTotal + pulled

    local pushed = 0
    if (not maxItems) or pushedTotal < maxItems then
        local cap = maxItems and (maxItems - pushedTotal) or 64
        pushed = inventory.put(inputId, cap, source, machine)
        pushedTotal = pushedTotal + pushed
    end

    if pushed == 0 and pulled == 0 then
        idle = idle + 1
        local outOfInput = (source:find(inputId) or -1) < 0
        local capReached = maxItems and pushedTotal >= maxItems
        if idle >= 8 and (outOfInput or capReached) then break end
        sleep(0.05)
    else
        idle = 0
        sleep(0.05)
    end
end

local dt = os.clock() - t0
print(string.format("smelted: %d outputs from %s in %.2fs (%.0f items/s)",
    pulledTotal, inputId, dt, (dt > 0) and (pulledTotal / dt) or 0))
