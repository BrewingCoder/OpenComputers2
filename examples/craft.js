// craft.js — JS twin of craft.lua. Fire one of the Crafter Part's programmed
// Recipe Cards using ingredients from the inventory on this channel.
//
// Usage:
//   run craft.js                  // list every programmed slot + output
//   run craft.js <slot>           // craft once from that slot
//   run craft.js <slot> <count>   // craft <count> times

(function () {
    var slotArg  = (args[0] != null) ? Number(args[0]) : null;
    var countArg = (args[1] != null) ? Number(args[1]) : 1;

    var crafter = peripheral.find('crafter');
    if (!crafter) throw new Error('no crafter peripheral on this channel');

    if (slotArg == null || isNaN(slotArg)) {
        print('crafter: ' + crafter.name);
        var snaps = crafter.list();
        for (var i = 0; i < snaps.length; i++) {
            var s = snaps[i];
            if (s != null) {
                var out = s.output != null ? s.output : '(blank)';
                print('  [' + s.slot + '] ' + out + ' x' + s.outputCount);
            }
        }
        return;
    }

    var source = inventory.find();
    if (!source) throw new Error('no inventory peripheral on this channel');

    var made = crafter.craft(slotArg, countArg, source);
    print('crafted ' + made + ' / ' + countArg);
})();
