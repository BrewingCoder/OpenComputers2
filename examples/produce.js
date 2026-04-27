// produce.js — JS twin of produce.lua. "I need N of <output>; figure it out."
//
// Usage:
//   run produce.js minecraft:copper_ingot 64
//   run produce.js mekanism:ingot_osmium 32
//
// Single-step only — no recipe chaining yet.

(function () {
    var outputId = args[0];
    var count = (args[1] != null) ? Number(args[1]) : null;

    if (!outputId || count == null || isNaN(count)) {
        print('usage: produce <itemId> <count>');
        print('  e.g. produce minecraft:copper_ingot 64');
        return;
    }

    var source = inventory.find();
    if (!source) throw new Error('no inventory peripheral on this channel');

    var q = recipes(outputId);
    var machine = q.producers[0];
    if (!machine) {
        print('no attached machine can produce ' + outputId);
        return;
    }

    var inputId = null;
    for (var i = 0; i < q.inputs.length; i++) {
        var id = q.inputs[i];
        var cs = source.find(id);
        if (cs != null && cs > 0) { inputId = id; break; }
    }
    if (!inputId) {
        print('no input items for ' + outputId + ' found in source');
        return;
    }

    var lib = require('recipes');
    var t0 = Date.now();
    var result = lib.smelt(inputId, source, source, { max: count });
    var dt = (Date.now() - t0) / 1000;

    var pulled = result[0];
    var err = result[1];
    if (pulled == null) {
        print('produce failed: ' + err);
        return;
    }

    print('produced: ' + pulled + ' ' + outputId
        + ' in ' + dt.toFixed(2) + 's ('
        + ((dt > 0) ? (pulled / dt).toFixed(0) : '0') + ' items/s)');
})();
