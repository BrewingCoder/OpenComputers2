-- craft.lua — fire one of the Crafter Part's programmed Recipe Cards using
-- ingredients from the inventory on this channel.
--
-- Usage:
--   run craft.lua                   -- list every programmed slot + output
--   run craft.lua <slot>            -- craft once from that slot
--   run craft.lua <slot> <count>    -- craft <count> times
--
-- Layout this expects:
--   * one Adapter with a Crafter Part facing a vanilla crafting_table
--   * one inventory peripheral on the same channel holding the ingredients
--     (and where the crafted output ends up)

local args = { ... }
local slotArg = tonumber(args[1])
local countArg = tonumber(args[2]) or 1

local crafter = peripheral.find("crafter")
if not crafter then error("no crafter peripheral on this channel") end

if not slotArg then
    print("crafter: " .. crafter.name)
    for _, snap in ipairs(crafter:list()) do
        if snap then
            local out = snap.output or "(blank)"
            print(string.format("  [%2d] %s x%d", snap.slot, out, snap.outputCount))
        end
    end
    return
end

local source = inventory.find()
if not source then error("no inventory peripheral on this channel") end

local made = crafter:craft(slotArg, countArg, source)
print(string.format("crafted %d / %d", made, countArg))
