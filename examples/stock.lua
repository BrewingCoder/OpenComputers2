-- stock.lua — Isy-style inventory stocking demo.
--
-- Run on a Computer that shares a wifi channel with a network of Adapter
-- inventory parts. Each part's `data` field (set in the right-click GUI)
-- holds rules for THAT inventory:
--
--   # comments and blank lines are ignored
--   stock minecraft:iron_ingot 64       -- keep at least 64 here
--   min   minecraft:cobblestone 128     -- alias for stock
--   max   minecraft:dirt 64             -- never hold more than 64
--
-- Inventories whose `data` is empty are the source pool. Items flow IN
-- from the pool to satisfy stock/min, and OUT to the pool when max trips.
--
-- This script is a DEMO of `peripheral.data` — the part field is not parsed
-- by the engine, so the grammar lives entirely here. Copy/edit/replace as
-- you need.

local INTERVAL = 5  -- seconds between cycles

local function trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end

local function parseRules(text)
    local rules = {}
    for line in text:gmatch("[^\r\n]+") do
        local stripped = trim(line:gsub("#.*$", ""))
        if stripped ~= "" then
            local verb, item, n = stripped:match("^(%S+)%s+(%S+)%s+(%-?%d+)$")
            local target = tonumber(n)
            if verb == "stock" or verb == "min" then
                table.insert(rules, { kind = "min", item = item, n = target })
            elseif verb == "max" then
                table.insert(rules, { kind = "max", item = item, n = target })
            elseif verb then
                print("warn: unknown verb '" .. verb .. "' (line: " .. stripped .. ")")
            else
                print("warn: bad rule (line: " .. stripped .. ")")
            end
        end
    end
    return rules
end

local function countItem(inv, itemId)
    local total = 0
    for _, snap in ipairs(inv:list()) do
        if snap and snap.id == itemId then total = total + snap.count end
    end
    return total
end

local function applyRules(stocker, rules, sources)
    for _, rule in ipairs(rules) do
        local have = countItem(stocker, rule.item)
        if rule.kind == "min" and have < rule.n then
            local need = rule.n - have
            local moved = 0
            for _, src in ipairs(sources) do
                if moved >= need then break end
                moved = moved + inventory.put(rule.item, need - moved, src, stocker)
            end
            if moved > 0 then
                print(string.format("%s: +%d %s (-> %d/%d)",
                    stocker.name, moved, rule.item, have + moved, rule.n))
            end
        elseif rule.kind == "max" and have > rule.n then
            local excess = have - rule.n
            local moved = 0
            for _, src in ipairs(sources) do
                if moved >= excess then break end
                moved = moved + inventory.put(rule.item, excess - moved, stocker, src)
            end
            if moved > 0 then
                print(string.format("%s: -%d %s (-> %d, max %d)",
                    stocker.name, moved, rule.item, have - moved, rule.n))
            end
        end
    end
end

print("stock.lua: loop every " .. INTERVAL .. "s; ctrl-C to stop")
while true do
    local stockers, sources = {}, {}
    for _, inv in ipairs(inventory.list()) do
        if inv.data and inv.data ~= "" then
            table.insert(stockers, { peripheral = inv, rules = parseRules(inv.data) })
        else
            table.insert(sources, inv)
        end
    end
    if #sources == 0 then
        print("warn: no source pool — at least one inventory needs an empty `data`")
    else
        for _, s in ipairs(stockers) do
            applyRules(s.peripheral, s.rules, sources)
        end
    end
    sleep(INTERVAL)
end
