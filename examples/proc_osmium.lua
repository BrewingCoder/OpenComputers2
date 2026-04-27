-- proc_osmium.lua — manual loop reference (HISTORICAL).
--
-- This script predates the `inventory.*` and `recipes(...)` globals, and is
-- kept around as an educational example of the explicit per-slot loop. For
-- new code, use `examples/smelt.lua` (one machine, auto-discovered) or call
-- the globals directly:
--
--   local chest = inventory.find()
--   local sm    = recipes("mekanism:raw_osmium").consumers[1]
--   inventory.put("mekanism:raw_osmium", 64, chest, sm)
--   inventory.drain(sm, "mekanism:ingot_osmium", { chest })
--
-- Rig (computer 2, channel "proc1"):
--   * Sophisticated chest with raw_osmium, OC2 adapter on a face, inventory part installed.
--   * Mek Ultimate Smelter, OC2 adapter on a face configured as **mixed I/O** in Mek's
--     side config — only the **bridge** part installed there. The bridge surfaces both
--     status (getActive/getEnergy) AND inventory ops (push/pull/list/find) off the same
--     IItemHandler exposed on that face, so we don't need a second inventory adapter.
--
-- Smelter inventory exposes input + output slots through one IItemHandler. We don't
-- classify slots up-front: pushing raw_osmium without targetSlot lets Mek route it
-- (rejects raw items into output slots, accepts into input), and we drain only slots
-- whose content is osmium_ingot (output side).
--
-- Throughput note: Ultimate Smelter at 50 ops/tick × 1 tick/recipe = 1000 items/sec
-- of pure smelting — a chest of ~263 raws burns in ~5 ticks of compute. The bottleneck
-- is keeping output drained so the machine doesn't stall on full output slots, so
-- the OUTPUT DRAIN runs first every iteration before we top up input.

local RAW   = "mekanism:raw_osmium"
local INGOT = "mekanism:ingot_osmium"

-- ---------- discovery ----------
local chest   = peripheral.find("inventory")
local smelter = peripheral.find("bridge")
if chest == nil   then error("no inventory peripheral on this channel — install an inventory part on the chest's adapter face") end
if smelter == nil then error("no bridge peripheral on this channel — install a bridge part on the smelter's adapter face") end

-- The bridge handle also exposes the InventoryPeripheral surface (size/list/push/pull)
-- when the underlying machine has an IItemHandler. Sanity-check before we run.
if smelter.size == nil or smelter:size() == 0 then
    error("bridge peripheral '" .. smelter.name .. "' does not expose an IItemHandler — " ..
          "is the adapter face's side-config set to input/output (or mixed)?")
end

print(string.format("chest=%s (%d slots), smelter=%s (%d slots, %s)",
                    chest.name, chest:size(), smelter.name, smelter:size(),
                    smelter:call("getMachineType") or smelter.target))

-- ---------- loop ----------
local t0          = os.clock()
local pushedTotal = 0
local pulledTotal = 0
local idle        = 0
local IDLE_BREAK  = 8       -- ~8 idle ticks (≈400ms) → done
local TICK        = 0.05    -- 1 MC tick

while true do
    -- 1) DRAIN OUTPUT — bottleneck-critical, runs first.
    local pulled = 0
    for slot, item in pairs(smelter:list()) do
        if item and item.id == INGOT then
            pulled = pulled + smelter:push(slot, chest)
        end
    end

    -- 2) FEED INPUT — push one raw stack per iteration; if input is full
    --    Mek rejects (push returns 0) and we drain harder next iteration.
    local pushed = 0
    local cs = chest:find(RAW)
    if cs and cs > 0 then
        pushed = chest:push(cs, smelter)
    end

    pushedTotal = pushedTotal + pushed
    pulledTotal = pulledTotal + pulled

    -- 3) Termination: nothing moved AND chest is out of raw → wait a few
    --    ticks for the last in-flight items to finish smelting + drain,
    --    then exit. Keep looping otherwise.
    if pushed == 0 and pulled == 0 then
        idle = idle + 1
        if idle >= IDLE_BREAK and (chest:find(RAW) or -1) < 0 then break end
        sleep(TICK)
    else
        idle = 0
        sleep(TICK)   -- yield 1 tick — smelter state only updates per tick anyway
    end
end

local dt = os.clock() - t0
print(string.format("done: pushed %d raw, pulled %d ingots in %.2fs (%.0f items/s out)",
                    pushedTotal, pulledTotal, dt,
                    (dt > 0) and (pulledTotal / dt) or 0))
