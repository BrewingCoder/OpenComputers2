// stock.js — JS twin of stock.lua. Isy-style inventory stocking demo.
//
// Run on a Computer that shares a wifi channel with a network of Adapter
// inventory parts. Each part's `data` field (set in the right-click GUI)
// holds rules for THAT inventory:
//
//   # comments and blank lines are ignored
//   stock minecraft:iron_ingot 64       -- keep at least 64 here
//   min   minecraft:cobblestone 128     -- alias for stock
//   max   minecraft:dirt 64             -- never hold more than 64
//
// Inventories whose `data` is empty are the source pool. Items flow IN
// from the pool to satisfy stock/min, and OUT to the pool when max trips.
//
// This script is a DEMO of `peripheral.data` — the part field is not parsed
// by the engine, so the grammar lives entirely here. Copy/edit/replace as
// you need.

(function () {
    var INTERVAL = 5;  // seconds between cycles

    var RULE_RE = /^(\S+)\s+(\S+)\s+(-?\d+)$/;

    function parseRules(text) {
        var rules = [];
        var lines = String(text).split(/\r?\n/);
        for (var i = 0; i < lines.length; i++) {
            var stripped = lines[i].replace(/#.*$/, '').replace(/^\s+|\s+$/g, '');
            if (stripped === '') continue;
            var m = stripped.match(RULE_RE);
            if (m) {
                var verb = m[1], item = m[2], n = Number(m[3]);
                if (verb === 'stock' || verb === 'min') {
                    rules.push({ kind: 'min', item: item, n: n });
                } else if (verb === 'max') {
                    rules.push({ kind: 'max', item: item, n: n });
                } else {
                    print("warn: unknown verb '" + verb + "' (line: " + stripped + ')');
                }
            } else {
                print('warn: bad rule (line: ' + stripped + ')');
            }
        }
        return rules;
    }

    function countItem(inv, itemId) {
        var total = 0;
        var snaps = inv.list();
        for (var i = 0; i < snaps.length; i++) {
            var s = snaps[i];
            if (s && s.id === itemId) total += s.count;
        }
        return total;
    }

    function applyRules(stocker, rules, sources) {
        for (var i = 0; i < rules.length; i++) {
            var rule = rules[i];
            var have = countItem(stocker, rule.item);
            if (rule.kind === 'min' && have < rule.n) {
                var need = rule.n - have;
                var movedIn = 0;
                for (var j = 0; j < sources.length; j++) {
                    if (movedIn >= need) break;
                    movedIn += inventory.put(rule.item, need - movedIn, sources[j], stocker);
                }
                if (movedIn > 0) {
                    print(stocker.name + ': +' + movedIn + ' ' + rule.item
                        + ' (-> ' + (have + movedIn) + '/' + rule.n + ')');
                }
            } else if (rule.kind === 'max' && have > rule.n) {
                var excess = have - rule.n;
                var movedOut = 0;
                for (var k = 0; k < sources.length; k++) {
                    if (movedOut >= excess) break;
                    movedOut += inventory.put(rule.item, excess - movedOut, stocker, sources[k]);
                }
                if (movedOut > 0) {
                    print(stocker.name + ': -' + movedOut + ' ' + rule.item
                        + ' (-> ' + (have - movedOut) + ', max ' + rule.n + ')');
                }
            }
        }
    }

    print('stock.js: loop every ' + INTERVAL + 's; ctrl-C to stop');
    while (true) {
        var stockers = [];
        var sources = [];
        var invs = inventory.list();
        for (var i = 0; i < invs.length; i++) {
            var inv = invs[i];
            if (inv.data && inv.data !== '') {
                stockers.push({ peripheral: inv, rules: parseRules(inv.data) });
            } else {
                sources.push(inv);
            }
        }
        if (sources.length === 0) {
            print('warn: no source pool — at least one inventory needs an empty `data`');
        } else {
            for (var s = 0; s < stockers.length; s++) {
                applyRules(stockers[s].peripheral, stockers[s].rules, sources);
            }
        }
        sleep(INTERVAL);
    }
})();
