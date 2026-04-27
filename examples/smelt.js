// smelt.js — JS twin of smelt.lua. Ore → ingot processing.
//
// Usage:
//   run smelt.js minecraft:raw_copper          // smelt all raw_copper in chest
//   run smelt.js minecraft:raw_copper 64       // stop after 64 inputs pushed
//   run smelt.js mekanism:raw_osmium

(function () {
    var inputId = args[0];
    var maxItems = (args[1] != null) ? Number(args[1]) : null;

    if (!inputId) {
        print('usage: smelt <itemId> [maxItems]');
        print('  e.g. smelt minecraft:raw_copper');
        return;
    }

    var source = inventory.find();
    if (!source) throw new Error('no inventory peripheral on this channel');

    var q = recipes(inputId);
    var machine = q.consumers[0];
    if (!machine) {
        print('no attached machine can smelt ' + inputId);
        return;
    }
    var outputs = q.outputs;
    if (outputs.length === 0) {
        print(machine.name + ' refused getOutputFor(' + inputId + ')');
        return;
    }

    var t0 = Date.now();
    var pulledTotal = 0;
    var pushedTotal = 0;
    var idle = 0;

    while (true) {
        var pulled = 0;
        for (var i = 0; i < outputs.length; i++) {
            pulled += inventory.drain(machine, outputs[i], [source]);
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
            if (idle >= 8 && (outOfInput || capReached)) break;
            sleep(0.05);
        } else {
            idle = 0;
            sleep(0.05);
        }
    }

    var dt = (Date.now() - t0) / 1000;
    print('smelted: ' + pulledTotal + ' outputs from ' + inputId
        + ' in ' + dt.toFixed(2) + 's ('
        + ((dt > 0) ? (pulledTotal / dt).toFixed(0) : '0') + ' items/s)');
})();
