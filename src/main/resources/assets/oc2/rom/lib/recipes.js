// recipes.js — JS twin of recipes.lua. Thin back-compat shim over the
// built-in `inventory` and `recipes` globals.
//
// New scripts should use the globals directly:
//
//   var sm  = recipes('minecraft:raw_iron').consumers[0];
//   var got = inventory.put('minecraft:raw_iron', 64, chest, sm);
//   inventory.drain(sm, 'minecraft:iron_ingot', [chest]);
//
// This module preserves the older `recipes.smelt` / `recipes.produce` API
// for scripts that already depend on it.

var M = {};

M.findMachineFor = function (inputId) {
    return recipes(inputId).consumers[0];
};

M.findProducerFor = function (outputId) {
    return recipes(outputId).producers[0];
};

// ============================================================
// smelt(inputId, source [, sink [, opts]])
//
//   inputId : 'minecraft:raw_copper' etc.
//   source  : InventoryPeripheral that holds the inputs
//   sink    : where to deposit outputs (default: source)
//   opts    :
//     max       : stop after pushing this many input items
//     idleBreak : ticks of idle before exit (default 8 ≈ 400ms)
//     tick      : sleep between iterations (default 0.05s)
// ============================================================

M.smelt = function (inputId, source, sink, opts) {
    opts = opts || {};
    var idleBreak = opts.idleBreak || 8;
    var tickSleep = (opts.tick !== undefined) ? opts.tick : 0.05;
    var maxItems  = opts.max;

    var q = recipes(inputId);
    var machine = q.consumers[0];
    if (!machine) {
        return [null, 'no attached machine can smelt ' + inputId];
    }
    var outputs = q.outputs;
    if (outputs.length === 0) {
        return [null, machine.name + ' refused getOutputFor(' + inputId + ')'];
    }

    sink = sink || source;

    var pulledTotal = 0;
    var pushedTotal = 0;
    var idle = 0;

    while (true) {
        var pulled = 0;
        for (var i = 0; i < outputs.length; i++) {
            pulled += inventory.drain(machine, outputs[i], [sink]);
        }
        pulledTotal += pulled;

        var pushed = 0;
        if (maxItems == null || pushedTotal < maxItems) {
            var cap = (maxItems != null) ? (maxItems - pushedTotal) : 64;
            pushed = inventory.put(inputId, cap, source, machine);
            pushedTotal += pushed;
        }

        if (pushed === 0 && pulled === 0) {
            idle += 1;
            var cs = source.find(inputId);
            var outOfInput = (cs == null) || (cs < 0);
            var capReached = (maxItems != null) && (pushedTotal >= maxItems);
            if (idle >= idleBreak && (outOfInput || capReached)) break;
            sleep(tickSleep);
        } else {
            idle = 0;
            sleep(tickSleep);
        }
    }

    return [pulledTotal, null];
};

// ============================================================
// produce(outputId, count, source [, sink])
// Single-step only. Returns [count, null] on success, [null, errMsg] on failure.
// ============================================================

M.produce = function (outputId, count, source, sink) {
    sink = sink || source;
    var q = recipes(outputId);
    var machine = q.producers[0];
    if (!machine) {
        return [null, 'no attached machine can produce ' + outputId];
    }
    for (var i = 0; i < q.inputs.length; i++) {
        var inputId = q.inputs[i];
        var cs = source.find(inputId);
        if (cs != null && cs > 0) {
            return M.smelt(inputId, source, sink, { max: count });
        }
    }
    return [null, 'no input items for ' + outputId + ' found in source'];
};

module.exports = M;
