-- recipes.lua — thin back-compat shim over the built-in `inventory` and
-- `recipes` globals. New scripts should use the globals directly:
--
--   local sm  = recipes("minecraft:raw_iron").consumers[1]
--   local got = inventory.put("minecraft:raw_iron", 64, chest, sm)
--   inventory.drain(sm, "minecraft:iron_ingot", { chest })
--
-- This module preserves the older `recipes.smelt` / `recipes.produce` API
-- for scripts that already depend on it. Implementation collapses from
-- ~170 lines to ~80 by deferring discovery + ranking to Kotlin.

local M = {}

function M.findMachineFor(inputId)
    return recipes(inputId).consumers[1]
end

function M.findProducerFor(outputId)
    return recipes(outputId).producers[1]
end

-- ============================================================
-- smelt — drive one machine through one input recipe to completion.
--
--   recipes.smelt(inputId, source [, sink [, opts]])
--
--   inputId : "minecraft:raw_copper" etc.
--   source  : InventoryPeripheral that holds the inputs
--   sink    : where to deposit outputs (default: source — same chest)
--   opts    :
--     max       : stop after pushing this many input items (nil = drain source)
--     idleBreak : ticks of idle before exit (default 8 ≈ 400ms)
--     tick      : sleep between iterations (default 0.05s = 1 MC tick)
-- ============================================================

function M.smelt(inputId, source, sink, opts)
    opts = opts or {}
    local idleBreak = opts.idleBreak or 8
    local tickSleep = opts.tick or 0.05
    local maxItems  = opts.max

    local q = recipes(inputId)
    local machine = q.consumers[1]
    if not machine then
        return nil, "no attached machine can smelt " .. tostring(inputId)
    end
    local outputs = q.outputs
    if #outputs == 0 then
        return nil, machine.name .. " refused getOutputFor(" .. tostring(inputId) .. ")"
    end

    sink = sink or source

    local pulled_total = 0
    local pushed_total = 0
    local idle = 0

    while true do
        local pulled = 0
        for _, oid in ipairs(outputs) do
            pulled = pulled + inventory.drain(machine, oid, { sink })
        end
        pulled_total = pulled_total + pulled

        local pushed = 0
        if (not maxItems) or pushed_total < maxItems then
            local cap = maxItems and (maxItems - pushed_total) or 64
            pushed = inventory.put(inputId, cap, source, machine)
            pushed_total = pushed_total + pushed
        end

        if pushed == 0 and pulled == 0 then
            idle = idle + 1
            local cs = source:find(inputId) or -1
            local out_of_input = cs < 0
            local cap_reached  = maxItems and pushed_total >= maxItems
            if idle >= idleBreak and (out_of_input or cap_reached) then break end
            sleep(tickSleep)
        else
            idle = 0
            sleep(tickSleep)
        end
    end

    return pulled_total
end

-- ============================================================
-- produce — "I need X items of outputId; figure it out."
--
-- Single-step only. If no machine on the channel produces `outputId` from
-- something already in `source`, returns `nil, error`. Multi-step chaining
-- (e.g. ore → raw → ingot via Crusher + Smelter) is a Phase 2 feature.
-- ============================================================

function M.produce(outputId, count, source, sink)
    sink = sink or source
    local q = recipes(outputId)
    local machine = q.producers[1]
    if not machine then
        return nil, "no attached machine can produce " .. tostring(outputId)
    end
    for _, inputId in ipairs(q.inputs) do
        local cs = source:find(inputId)
        if cs and cs > 0 then
            return M.smelt(inputId, source, sink, { max = count })
        end
    end
    return nil, "no input items for " .. tostring(outputId) .. " found in source"
end

return M
