// ui_v1 -- retained-mode widget library for OC2 monitors (frozen API).
//
// Mirror of ui_v1.lua. Same widget surface, same prop names, same theme
// tokens. One language asymmetry (documented): JS only has ui.run(root)
// -- no ui.tick mode, because the JS host uses __uiRun for the event
// loop and doesn't expose continuations to user code.
//
//   var ui = require('ui_v1');
//   var monitor = peripheral.find('monitor');
//   var title = ui.Label({ x: 10, y: 10, width: 200, height: 20,
//                          text: 'Hello!', color: 'hi', align: 'center' });
//   ui.mount(monitor, title);
//   ui.render();
//
// Library surface — rendering + geometry helpers live on the monitor
// peripheral itself (getCellMetrics, snapCellRect, argb/lighten/dim,
// drawText/fillText, drawSmallText). Widgets pass the monitor handle
// down to their draw methods and call those directly.

var M = {};
M.VERSION = 'v1';

// ============================================================
// Theme
// ============================================================

M.DEFAULT_THEME = {
    bg:      0xFF0A0F1A | 0,
    bgCard:  0xFF121827 | 0,
    fg:      0xFFFFFFFF | 0,
    hi:      0xFFE6F0FF | 0,
    muted:   0xFF7A8597 | 0,
    good:    0xFF2ECC71 | 0,
    warn:    0xFFF1C40F | 0,
    bad:     0xFFE74C3C | 0,
    info:    0xFF3498DB | 0,
    edge:    0xFF2E3A4E | 0,
    primary: 0xFF3498DB | 0,
    ghost:   0xFF2E3A4E | 0,
    danger:  0xFFE74C3C | 0,
};

var _theme = M.DEFAULT_THEME;

M.setTheme = function(t) { _theme = t || M.DEFAULT_THEME; M.invalidate(); };
M.getTheme = function() { return _theme; };

// Resolve a color prop: undefined/null -> fallback; number -> int;
// string -> theme token lookup (fallback if unknown).
function resolveColor(v, fallback) {
    if (v === undefined || v === null) return fallback;
    if (typeof v === 'number') return v | 0;
    if (typeof v === 'string') {
        var hit = _theme[v];
        return hit !== undefined ? hit : fallback;
    }
    return fallback;
}
M._resolveColor = resolveColor;  // exposed for tests

// ============================================================
// Per-monitor mount state. JS has no weak refs we can use cleanly
// in Rhino ES5 mode -- a Map would leak if the monitor handle is
// replaced. Good enough for v1: scripts hold onto a single handle.
// ============================================================

var _monitors = [];  // [{ monitor, widgets, iconClearPending }]
var _dirty = true;
var _running = false;

function _findState(monitor) {
    for (var i = 0; i < _monitors.length; i++) {
        if (_monitors[i].monitor === monitor) return _monitors[i];
    }
    return null;
}

M.invalidate = function() { _dirty = true; };
M.isDirty = function() { return _dirty; };

// ============================================================
// Widget base -- a plain prop-bag with a few shared methods mixed in.
// ============================================================

function _mixWidget(o) {
    o.set = function(props) {
        if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
        M.invalidate();
        return o;
    };
    o.get = function(name) { return o[name]; };
    o.hittest = function(px, py) {
        if (o.visible === false) return false;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        return px >= x && px < x + w && py >= y && py < y + h;
    };
    return o;
}

// ============================================================
// Label
// ============================================================

M.Label = function(props) {
    var o = {
        kind: 'Label',
        x: 0, y: 0, width: 0, height: 0,
        text: '',
        color: 'fg',
        bg: null,
        align: 'left',
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    // Hug-content size: one text row tall, text.length cells wide. Parents
    // call this when the Label has no explicit width/height and no flex.
    o.measure = function(monitor) {
        var m = monitor.getCellMetrics();
        var pxPerCol = m[2], pxPerRow = m[3];
        var text = String(o.text == null ? '' : o.text);
        return [text.length * pxPerCol, pxPerRow];
    };
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;

        // `h` is a HINT. snapCellRect returns the largest odd-count cell band
        // that fits, centered on the user's midpoint, so bg + text co-register
        // on the same cell grid. Without this the bg rect drifts from the text
        // row whenever y isn't cell-aligned.
        var m = monitor.getCellMetrics();
        var pxPerCol = m[2];
        var snap = monitor.snapCellRect(y, h);
        var snappedY = snap[0], snappedH = snap[1], textRow = snap[2];

        if (o.bg !== null && o.bg !== undefined) {
            var bg = resolveColor(o.bg, 0);
            if (bg !== 0) monitor.drawRect(x, snappedY, w, snappedH, bg);
        }

        var text = String(o.text == null ? '' : o.text);
        var textPx = text.length * pxPerCol;

        var textX = x;
        if (o.align === 'center') textX = x + Math.floor((w - textPx) / 2);
        else if (o.align === 'right') textX = x + w - textPx;

        var textCol = Math.max(0, Math.floor(textX / pxPerCol));

        var fg = resolveColor(o.color, 0xFFFFFFFF | 0);
        monitor.drawText(textCol, textRow, text, fg, 0);
    };
    return o;
};

// ============================================================
// Bar -- horizontal or vertical fill bar. Mirror of ui_v1.lua Bar.
// Vertical bars fill bottom-up (fuller = more coverage at the bottom).
// ============================================================

function _clamp01(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

M.Bar = function(props) {
    var o = {
        kind: 'Bar',
        x: 0, y: 0, width: 0, height: 0,
        value: 0, min: 0, max: 100,
        color: 'good',
        bg: 'bgCard',
        border: 'edge',
        marker: null,
        markerColor: 'fg',
        orientation: 'h',
        showPct: false,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var min = o.min || 0;
        var max = o.max === undefined || o.max === null ? 100 : o.max;
        var range = max - min;
        if (range <= 0) range = 1;
        var pct = _clamp01(((o.value || 0) - min) / range);

        var bgc = resolveColor(o.bg, 0);
        if (bgc !== 0) monitor.drawRect(x, y, w, h, bgc);

        var fillc = resolveColor(o.color, 0xFF2ECC71 | 0);
        if (o.orientation === 'v') {
            var fh = Math.floor(h * pct);
            if (fh > 0) monitor.drawRect(x, y + h - fh, w, fh, fillc);
        } else {
            var fw = Math.floor(w * pct);
            if (fw > 0) monitor.drawRect(x, y, fw, h, fillc);
        }

        if (o.marker !== null && o.marker !== undefined) {
            var mv = _clamp01((o.marker - min) / range);
            var mc = resolveColor(o.markerColor, 0xFFFFFFFF | 0);
            if (o.orientation === 'v') {
                var my = y + h - Math.floor(h * mv) - 1;
                if (my < y) my = y;
                if (my >= y + h) my = y + h - 1;
                monitor.drawLine(x, my, x + w - 1, my, mc);
            } else {
                var mx = x + Math.floor(w * mv);
                if (mx >= x + w) mx = x + w - 1;
                if (mx < x) mx = x;
                monitor.drawLine(mx, y, mx, y + h - 1, mc);
            }
        }

        var bc = resolveColor(o.border, 0);
        if (bc !== 0) monitor.drawRectOutline(x, y, w, h, bc, 1);

        // Percent overlay. Pixel-space glyph so the label sits truly centered
        // inside the bar rect regardless of cell geometry. Engine-side
        // drawSmallText handles the 5x7 font blit; digits + '%' only.
        if (o.showPct) {
            var label = Math.floor(pct * 100 + 0.5) + '%';
            var fg = resolveColor('fg', 0xFFFFFFFF | 0);
            monitor.drawSmallText(Math.floor(x + w / 2), Math.floor(y + h / 2), label, fg);
        }
    };
    return o;
};

// ============================================================
// Divider -- thin line separator. Mirrors ui_v1.lua.
// ============================================================

M.Divider = function(props) {
    var o = {
        kind: 'Divider',
        x: 0, y: 0, width: 0, height: 0,
        orientation: 'h',
        color: 'edge',
        thickness: 1,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        var t = o.thickness || 1;
        if (t < 1) t = 1;
        if (w <= 0 || h <= 0) return;
        var c = resolveColor(o.color, 0xFF2E3A4E | 0);
        if (o.orientation === 'v') {
            var tx = x + Math.floor((w - t) / 2);
            monitor.drawRect(tx, y, t, h, c);
        } else {
            var ty = y + Math.floor((h - t) / 2);
            monitor.drawRect(x, ty, w, t, c);
        }
    };
    return o;
};

// ============================================================
// Indicator -- LED dot + optional label. Mirrors ui_v1.lua.
// ============================================================

var _IND_STATE_TOKEN = {
    on:   "good",
    warn: "warn",
    bad:  "bad",
    info: "info",
};

// Pixels are non-square (20 cols x 9 rows per block face, 12x12 px per cell),
// so a vertical pixel is (20/9)x taller than a horizontal pixel. Stretch the
// horizontal radius so the LED renders as a round disc in world space.
var _IND_ASPECT_NUM = 20;
var _IND_ASPECT_DEN = 9;

M.Indicator = function(props) {
    var o = {
        kind: 'Indicator',
        x: 0, y: 0, width: 0, height: 0,
        size: 8,
        state: 'off',
        label: null,
        color: null,
        offColor: 'muted',
        labelColor: 'fg',
        gap: 4,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var h = o.height || 0;
        var size = (o.size === null || o.size === undefined) ? 8 : o.size;
        if (size < 2) size = 2;

        var ledColor;
        if (o.color !== null && o.color !== undefined) {
            ledColor = resolveColor(o.color, 0xFF2ECC71 | 0);
        } else {
            var token = _IND_STATE_TOKEN[o.state || 'off'];
            if (token) {
                ledColor = resolveColor(token, 0xFF2ECC71 | 0);
            } else {
                ledColor = resolveColor(o.offColor || 'muted', 0xFF7A8597 | 0);
            }
        }

        var ry = Math.floor(size / 2);
        if (ry < 1) ry = 1;
        var rx = Math.floor(ry * _IND_ASPECT_NUM / _IND_ASPECT_DEN + 0.5);
        if (rx < 1) rx = 1;

        var m = monitor.getCellMetrics();
        var pxPerCol = m[2], pxPerRow = m[3];

        var hasLabel = (o.label !== null && o.label !== undefined && o.label !== '');
        var cx, cy;
        if (hasLabel) {
            // Snap LED center to the cell containing (x + rx, y + h/2) so the
            // LED-to-glyph gap is constant across x values (otherwise it
            // alternates with x mod pxPerCol).
            var cellCol = Math.floor((x + rx) / pxPerCol);
            var cellRow = Math.floor((y + Math.floor(h / 2)) / pxPerRow);
            cx = cellCol * pxPerCol + Math.floor(pxPerCol / 2);
            cy = cellRow * pxPerRow + Math.floor(pxPerRow / 2);
        } else {
            cx = x + rx;
            cy = y + Math.floor(h / 2);
        }

        monitor.fillEllipse(cx, cy, rx, ry, ledColor);

        if (hasLabel) {
            var gap = (o.gap === null || o.gap === undefined) ? 4 : o.gap;
            var textX = cx + rx + gap;
            var textCol = Math.max(0, Math.ceil(textX / pxPerCol));
            var textRow = Math.max(0, Math.floor((y + Math.floor(h / 2)) / pxPerRow));

            var fg = resolveColor(o.labelColor || 'fg', 0xFFFFFFFF | 0);
            monitor.drawText(textCol, textRow, String(o.label), fg, 0);
        }
    };
    return o;
};

// ============================================================
// Gauge -- circular dial. Mirrors ui_v1.lua Gauge. See that file for
// angle convention (clock: 0=up, 90=right; clockwise sweep) and design
// notes. Defaults describe a 270° speedometer sweep starting lower-left.
// ============================================================

M.Gauge = function(props) {
    var o = {
        kind: 'Gauge',
        x: 0, y: 0, width: 0, height: 0,
        value: 0, min: 0, max: 100,
        color: 'good',
        bg: 'bgCard',
        thickness: 6,
        startDeg: 225,
        sweepDeg: 270,
        label: null,
        labelColor: 'fg',
        showValue: false,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var min = o.min || 0;
        var max = (o.max === undefined || o.max === null) ? 100 : o.max;
        var range = max - min;
        if (range <= 0) range = 1;
        var pct = (o.value - min) / range;
        if (pct < 0) pct = 0; else if (pct > 1) pct = 1;

        var maxRY = Math.floor(h / 2) - 1;
        var maxRX = Math.floor(w / 2) - 1;
        if (maxRY < 3) maxRY = 3;
        if (maxRX < 3) maxRX = 3;
        var ry = maxRY;
        var rx = Math.floor(ry * _IND_ASPECT_NUM / _IND_ASPECT_DEN + 0.5);
        if (rx > maxRX) {
            rx = maxRX;
            ry = Math.floor(rx * _IND_ASPECT_DEN / _IND_ASPECT_NUM + 0.5);
            if (ry < 1) ry = 1;
        }

        var thickness = (o.thickness === null || o.thickness === undefined) ? 6 : o.thickness;
        if (thickness < 1) thickness = 1;
        if (thickness > ry) thickness = ry;

        var cx = x + Math.floor(w / 2);
        var cy = y + Math.floor(h / 2);

        var startDeg = ((Math.floor(o.startDeg || 0) % 360) + 360) % 360;
        var sweepDeg = Math.floor(o.sweepDeg === undefined ? 270 : o.sweepDeg);
        if (sweepDeg < 0) sweepDeg = 0;
        if (sweepDeg > 360) sweepDeg = 360;
        var fillSweep = Math.floor(sweepDeg * pct + 0.5);
        if (fillSweep > sweepDeg) fillSweep = sweepDeg;

        var bgColor = resolveColor(o.bg, 0xFF121827 | 0);
        var fillColor = resolveColor(o.color, 0xFF2ECC71 | 0);
        if (o.enabled === false) {
            bgColor = monitor.dim(bgColor);
            fillColor = monitor.dim(fillColor);
        }

        if (sweepDeg - fillSweep > 0) {
            monitor.drawArc(cx, cy, rx, ry, thickness,
                (startDeg + fillSweep) % 360, sweepDeg - fillSweep, bgColor);
        }
        if (fillSweep > 0) {
            monitor.drawArc(cx, cy, rx, ry, thickness, startDeg, fillSweep, fillColor);
        }

        var text = null;
        if (o.showValue) {
            text = String(Math.floor((o.value || 0) + 0.5));
        } else if (o.label !== null && o.label !== undefined && o.label !== '') {
            text = String(o.label);
        }
        if (text !== null) {
            var m = monitor.getCellMetrics();
            var pxPerCol = m[2], pxPerRow = m[3];
            var textPx = text.length * pxPerCol;
            var startPx = cx - Math.floor(textPx / 2);
            var textCol = Math.max(0, Math.floor(startPx / pxPerCol));
            var textRow = Math.max(0, Math.floor(cy / pxPerRow));
            var fg = resolveColor(o.labelColor || 'fg', 0xFFFFFFFF | 0);
            if (o.enabled === false) fg = monitor.dim(fg);
            monitor.drawText(textCol, textRow, text, fg, 0);
        }
    };
    return o;
};

// ============================================================
// Sparkline -- time-series line chart backed by a ring buffer. Mirrors
// ui_v1.lua. Samples are pushed via .push(v); when length exceeds
// `capacity` the oldest sample is dropped. Auto-scales by default;
// explicit min/max lock the axis. Optional baseline, area fill, and
// last-sample numeric readout.
// ============================================================

M.Sparkline = function(props) {
    var o = {
        kind: 'Sparkline',
        x: 0, y: 0, width: 0, height: 0,
        capacity: 64,
        min: null, max: null,
        color: 'info',
        bg: 'bgCard',
        border: 'edge',
        baseline: null,
        baselineColor: 'muted',
        fill: false,
        fillColor: null,
        showLast: false,
        lastColor: 'fg',
        visible: true,
    };
    if (props) { for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k]; }
    // Defensive copy of values
    var copy = [];
    if (props && props.values) {
        for (var i = 0; i < props.values.length; i++) copy.push(props.values[i]);
    }
    o.values = copy;
    _mixWidget(o);

    o.push = function(v) {
        if (v === null || v === undefined) return;
        var cap = o.capacity || 64;
        if (cap < 1) cap = 1;
        o.values.push(v);
        while (o.values.length > cap) o.values.shift();
    };
    o.clear = function() { o.values = []; };
    o.setValues = function(arr) {
        var c = [];
        if (arr) { for (var i = 0; i < arr.length; i++) c.push(arr[i]); }
        o.values = c;
    };

    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var bgc = resolveColor(o.bg, 0);
        if (bgc !== 0) monitor.drawRect(x, y, w, h, bgc);

        var vals = o.values || [];
        var n = vals.length;

        var effMin = o.min, effMax = o.max;
        if (effMin === null || effMin === undefined || effMax === null || effMax === undefined) {
            if (n > 0) {
                var dmin = vals[0], dmax = vals[0];
                for (var i = 1; i < n; i++) {
                    var v = vals[i];
                    if (v < dmin) dmin = v;
                    if (v > dmax) dmax = v;
                }
                if (dmin === dmax) {
                    dmin = dmin - 0.5; dmax = dmax + 0.5;
                } else {
                    var sp = dmax - dmin;
                    dmin = dmin - sp * 0.05;
                    dmax = dmax + sp * 0.05;
                }
                if (effMin === null || effMin === undefined) effMin = dmin;
                if (effMax === null || effMax === undefined) effMax = dmax;
            } else {
                if (effMin === null || effMin === undefined) effMin = 0;
                if (effMax === null || effMax === undefined) effMax = 1;
            }
        }
        var span = effMax - effMin;
        if (span <= 0) span = 1;

        var lineColor = resolveColor(o.color, 0xFF48C2FF | 0);
        var fillC;
        if (o.fillColor !== null && o.fillColor !== undefined) {
            fillC = resolveColor(o.fillColor, lineColor);
        } else {
            fillC = monitor.dim(lineColor);
        }
        if (o.enabled === false) {
            lineColor = monitor.dim(lineColor);
            fillC = monitor.dim(fillC);
        }

        function xAt(idx, count) {
            if (count <= 1) return x + Math.floor(w / 2);
            return x + 1 + Math.floor(idx * (w - 3) / (count - 1) + 0.5);
        }
        function yAt(v) {
            var t = (v - effMin) / span;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            return y + h - 2 - Math.floor(t * (h - 3) + 0.5);
        }

        if (o.baseline !== null && o.baseline !== undefined) {
            var bcline = resolveColor(o.baselineColor || 'muted', 0xFF7A8597 | 0);
            if (o.enabled === false) bcline = monitor.dim(bcline);
            var by = yAt(o.baseline);
            monitor.drawLine(x + 1, by, x + w - 2, by, bcline);
        }

        if (n >= 1) {
            if (o.fill) {
                var baseY;
                if (o.baseline !== null && o.baseline !== undefined) baseY = yAt(o.baseline);
                else baseY = y + h - 2;
                for (var j = 0; j < n; j++) {
                    var xi = xAt(j, n);
                    var yi = yAt(vals[j]);
                    var top, bot;
                    if (yi <= baseY) { top = yi; bot = baseY; }
                    else { top = baseY; bot = yi; }
                    monitor.drawLine(xi, top, xi, bot, fillC);
                }
            }
            if (n >= 2) {
                for (var k = 0; k < n - 1; k++) {
                    var x1 = xAt(k, n), y1 = yAt(vals[k]);
                    var x2 = xAt(k + 1, n), y2 = yAt(vals[k + 1]);
                    monitor.drawLine(x1, y1, x2, y2, lineColor);
                }
            } else {
                monitor.setPixel(xAt(0, 1), yAt(vals[0]), lineColor);
            }
        }

        var bc = resolveColor(o.border, 0);
        if (bc !== 0) monitor.drawRectOutline(x, y, w, h, bc, 1);

        if (o.showLast && n >= 1) {
            var txt = String(Math.floor(vals[n - 1] + 0.5));
            var m2 = monitor.getCellMetrics();
            var pxPerCol = m2[2], pxPerRow = m2[3];
            var textPx = txt.length * pxPerCol;
            var startPx = x + w - 2 - textPx;
            var textCol = Math.max(0, Math.floor(startPx / pxPerCol));
            var textRow = Math.max(0, Math.floor((y + 2) / pxPerRow));
            var fg = resolveColor(o.lastColor || 'fg', 0xFFFFFFFF | 0);
            if (o.enabled === false) fg = monitor.dim(fg);
            monitor.drawText(textCol, textRow, txt, fg, 0);
        }
    };
    return o;
};

// ============================================================
// Icon -- mirror of ui_v1.lua Icon. Small pixel-art symbol for
// logos/status markers. Shapes: rect | circle | diamond | triangle |
// bits (custom 2D 0/1 array). Optional bg and border.
// ============================================================

var _ICON_ASPECT_NUM = 20;
var _ICON_ASPECT_DEN = 9;

M.Icon = function(props) {
    var o = {
        kind: 'Icon',
        x: 0, y: 0, width: 0, height: 0,
        shape: 'rect',
        color: 'info',
        bg: null,
        border: null,
        bits: null,
        // shape="item"     props: `item`     is a registry id like "minecraft:redstone".
        // shape="fluid"    props: `fluid`    is a registry id like "minecraft:water".
        // shape="chemical" props: `chemical` is a Mekanism id like "mekanism:hydrogen";
        //                        no-op when Mekanism is absent.
        item: null,
        fluid: null,
        chemical: null,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    if (props && props.bits) {
        var rowsIn = props.bits;
        var copy = [];
        for (var i = 0; i < rowsIn.length; i++) {
            var row = rowsIn[i];
            var rowCopy = [];
            for (var j = 0; j < row.length; j++) rowCopy.push(row[j]);
            copy.push(rowCopy);
        }
        o.bits = copy;
    }
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var fg = resolveColor(o.color || 'info', 0xFF4FA3FF | 0);
        if (o.enabled === false) fg = monitor.dim(fg);

        if (o.bg !== null && o.bg !== undefined) {
            var bgc = resolveColor(o.bg, 0);
            if (bgc !== 0) monitor.drawRect(x, y, w, h, bgc);
        }

        var shape = o.shape || 'rect';

        if (shape === 'rect') {
            monitor.drawRect(x, y, w, h, fg);
        } else if (shape === 'circle') {
            var cx = x + Math.floor(w / 2);
            var cy = y + Math.floor(h / 2);
            var ry = Math.floor(h / 2);
            if (ry < 1) ry = 1;
            var rx = Math.floor(ry * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5);
            var maxRx = Math.floor(w / 2);
            if (rx > maxRx) rx = maxRx;
            if (rx < 1) rx = 1;
            monitor.fillEllipse(cx, cy, rx, ry, fg);
        } else if (shape === 'diamond') {
            var dcx = x + Math.floor(w / 2);
            var hw = Math.floor(w / 2);
            var hh = Math.floor(h / 2);
            if (hw < 1) hw = 1;
            if (hh < 1) hh = 1;
            for (var dr = 0; dr < h; dr++) {
                var ddy = Math.abs(dr - hh);
                var dratio = 1 - ddy / hh;
                if (dratio < 0) dratio = 0;
                var dhalf = Math.floor(hw * dratio + 0.5);
                monitor.drawLine(dcx - dhalf, y + dr, dcx + dhalf, y + dr, fg, 1);
            }
        } else if (shape === 'triangle') {
            var tcx = x + Math.floor(w / 2);
            var thw = Math.floor(w / 2);
            for (var tr = 0; tr < h; tr++) {
                var tratio = (h > 1) ? (tr / (h - 1)) : 0;
                var thalf = Math.floor(thw * tratio + 0.5);
                monitor.drawLine(tcx - thalf, y + tr, tcx + thalf, y + tr, fg, 1);
            }
        } else if (shape === 'bits') {
            if (o.bits) {
                var rowsArr = o.bits;
                if (rowsArr.length > 0) {
                    var cols = rowsArr[0].length;
                    if (cols > 0) {
                        var pxCol = Math.max(1, Math.floor(w / cols));
                        var pxRow = Math.max(1, Math.floor(h / rowsArr.length));
                        var totalW = pxCol * cols;
                        var totalH = pxRow * rowsArr.length;
                        var ox = x + Math.floor((w - totalW) / 2);
                        var oy = y + Math.floor((h - totalH) / 2);
                        for (var ri = 0; ri < rowsArr.length; ri++) {
                            var rb = rowsArr[ri];
                            for (var ci = 0; ci < cols; ci++) {
                                if (rb[ci]) {
                                    monitor.drawRect(
                                        ox + ci * pxCol,
                                        oy + ri * pxRow,
                                        pxCol, pxRow, fg);
                                }
                            }
                        }
                    }
                }
            }
        } else if (shape === 'item' || shape === 'fluid' || shape === 'chemical') {
            var resId;
            if (shape === 'fluid') resId = o.fluid;
            else if (shape === 'chemical') resId = o.chemical;
            else resId = o.item;
            if (resId && resId !== '') {
                // Lazy clear: fires once per render pass, first time any icon draws.
                var _state = _findState(monitor);
                if (_state && _state.iconClearPending) {
                    monitor.clearIcons();
                    _state.iconClearPending = false;
                }
                // Aspect-correct so the icon renders visually square on screen.
                // Pxbuf pixels are 20:9 W:H per block — a square widget rect would
                // render as a vertical stripe. Shrink the longer axis.
                var iw = w, ih = h;
                if (w * _ICON_ASPECT_DEN > h * _ICON_ASPECT_NUM) {
                    iw = Math.floor(h * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5);
                } else {
                    ih = Math.floor(w * _ICON_ASPECT_DEN / _ICON_ASPECT_NUM + 0.5);
                }
                var ix = x + Math.floor((w - iw) / 2);
                var iy = y + Math.floor((h - ih) / 2);
                if (shape === 'fluid') {
                    monitor.drawFluid(ix, iy, iw, ih, resId);
                } else if (shape === 'chemical') {
                    monitor.drawChemical(ix, iy, iw, ih, resId);
                } else {
                    monitor.drawItem(ix, iy, iw, ih, resId);
                }
            }
        }

        if (o.border !== null && o.border !== undefined) {
            var bc = resolveColor(o.border, 0);
            if (bc !== 0) monitor.drawRectOutline(x, y, w, h, bc, 1);
        }
    };
    return o;
};

// ============================================================
// ItemSlot -- composite: resource texture + optional count overlay +
// optional caption below. Mirrors ui_v1.lua ItemSlot. Pure display;
// interactive behaviors are a future concern (drag/drop/click).
//
// Resource props (set exactly one):
//   item     -- item registry id. Renders via drawItem.
//   fluid    -- fluid registry id. Renders via drawFluid as the fluid's
//               still-texture tinted by its client tint color.
//   chemical -- Mekanism chemical id like "mekanism:hydrogen". Rendered
//               via drawChemical; no-op when Mekanism is absent.
//
// Display props:
//   count    -- number (auto-formatted: 9876 -> "9.9k") or string. Null
//               suppresses the count label.
//   caption  -- optional secondary label drawn below the slot.
//   size     -- icon pixel size (square in visual space; aspect-corrected
//               internally). Defaults to 32.
//   bg, border               -- slot chrome.
//   countColor, captionColor -- label color tokens.
// ============================================================

function _itemSlotFmtCount(c) {
    if (c === null || c === undefined) return null;
    if (typeof c === 'string') return c;
    var n = Number(c);
    if (!isFinite(n)) n = 0;
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e4) return (n / 1e3).toFixed(1) + 'k';
    return String(Math.floor(n));
}

M.ItemSlot = function(props) {
    var o = {
        kind: 'ItemSlot',
        x: 0, y: 0, width: 0, height: 0,
        item: null,
        fluid: null,
        chemical: null,
        count: null,
        caption: null,
        size: 72,
        bg: 'bgCard',
        border: 'edge',
        countColor: 'hi',
        captionColor: 'muted',
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);

    o.measure = function(monitor) {
        var size = o.size || 64;
        var w = (o.width || 0) > 0 ? o.width : size;
        var h = (o.height || 0) > 0 ? o.height : size;
        var hasCaption = o.caption !== null && o.caption !== undefined && o.caption !== "";
        if (hasCaption && monitor && typeof monitor.getCellMetrics === 'function') {
            var m = monitor.getCellMetrics();
            var pxPerRow = m[3];
            h = h + pxPerRow;
        }
        return [w, h];
    };

    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        if (o.bg !== null && o.bg !== undefined) {
            var bgc = resolveColor(o.bg, 0);
            if (bgc !== 0) monitor.drawRect(x, y, w, h, bgc);
        }

        var caption = o.caption;
        var hasCaption = caption !== null && caption !== undefined && caption !== "";
        var m = monitor.getCellMetrics();
        var pxPerCol = m[2], pxPerRow = m[3];
        var captionRows = hasCaption ? 1 : 0;
        var captionPxH = captionRows * pxPerRow;

        // Cell-grid snap: first/last cells fully inside slot pixel bounds.
        var firstCol = Math.ceil(x / pxPerCol);
        var lastCol = Math.floor((x + w) / pxPerCol) - 1;
        var cols = Math.max(0, lastCol - firstCol + 1);

        // Icon rect: inset from slot edges so textures don't clip the border.
        var ICON_INSET = 2;
        var ix0 = x + ICON_INSET;
        var iy0 = y + ICON_INSET;
        var iw0 = Math.max(0, w - 2 * ICON_INSET);
        var ih0 = Math.max(0, h - 2 * ICON_INSET);
        var iconAreaH = Math.max(0, ih0 - captionPxH);
        var iconBoxW = Math.min(iw0, o.size || 64);
        var iconBoxH = Math.min(iconAreaH, o.size || 64);
        if (iconBoxW > 0 && iconBoxH > 0) {
            var iw = iconBoxW, ih = iconBoxH;
            if (iw * _ICON_ASPECT_DEN > ih * _ICON_ASPECT_NUM) {
                iw = Math.floor(ih * _ICON_ASPECT_NUM / _ICON_ASPECT_DEN + 0.5);
            } else {
                ih = Math.floor(iw * _ICON_ASPECT_DEN / _ICON_ASPECT_NUM + 0.5);
            }
            var ix = ix0 + Math.floor((iw0 - iw) / 2);
            var iy = iy0 + Math.floor((iconAreaH - ih) / 2);

            if (o.item && o.item !== "") {
                var _state = _findState(monitor);
                if (_state && _state.iconClearPending) {
                    monitor.clearIcons();
                    _state.iconClearPending = false;
                }
                monitor.drawItem(ix, iy, iw, ih, o.item);
            } else if (o.fluid && o.fluid !== "") {
                var _state2 = _findState(monitor);
                if (_state2 && _state2.iconClearPending) {
                    monitor.clearIcons();
                    _state2.iconClearPending = false;
                }
                monitor.drawFluid(ix, iy, iw, ih, o.fluid);
            } else if (o.chemical && o.chemical !== "") {
                var _state3 = _findState(monitor);
                if (_state3 && _state3.iconClearPending) {
                    monitor.clearIcons();
                    _state3.iconClearPending = false;
                }
                monitor.drawChemical(ix, iy, iw, ih, o.chemical);
            }

            var cntText = _itemSlotFmtCount(o.count);
            if (cntText !== null && cntText !== "") {
                if (cntText.length > cols) cntText = cntText.substring(0, cols);
                if (cntText !== "") {
                    var cntFg = resolveColor(o.countColor || 'hi', 0xFFFFFFFF | 0);
                    var firstRow = Math.ceil(y / pxPerRow);
                    var lastRow = Math.max(firstRow,
                        Math.floor((y + h) / pxPerRow) - 1);
                    if (hasCaption) {
                        lastRow = Math.max(firstRow, lastRow - 1);
                    }
                    var countRow = Math.ceil((iy + ih) / pxPerRow);
                    countRow = Math.max(firstRow, Math.min(countRow, lastRow));
                    var startCol = firstCol + Math.max(0,
                        Math.floor((cols - cntText.length) / 2));
                    monitor.drawText(startCol, countRow, cntText, cntFg, 0);
                }
            }
        }

        if (hasCaption) {
            var capFg = resolveColor(o.captionColor || 'muted', 0xFFB8C2D0 | 0);
            var capRow = Math.floor((y + h - captionPxH) / pxPerRow);
            var capText = caption;
            if (capText.length > cols) capText = capText.substring(0, cols);
            if (capText !== "") {
                var capStartCol = firstCol + Math.max(0,
                    Math.floor((cols - capText.length) / 2));
                monitor.drawText(capStartCol, capRow, capText, capFg, 0);
            }
        }

        if (o.border !== null && o.border !== undefined) {
            var bc = resolveColor(o.border, 0);
            if (bc !== 0) monitor.drawRectOutline(x, y, w, h, bc, 1);
        }
    };
    return o;
};

// ============================================================
// Banner -- horizontal status strip with a colored left-edge accent.
// Mirrors ui_v1.lua. Pure display; non-interactive.
// ============================================================

var _BANNER_STATE_TOKEN = {
    good: "good",
    warn: "warn",
    bad:  "bad",
    info: "info",
    none: "edge",
};

M.Banner = function(props) {
    var o = {
        kind: 'Banner',
        x: 0, y: 0, width: 0, height: 0,
        text: "",
        style: "info",
        color: null,
        textColor: "fg",
        bg: "bgCard",
        edgeAccent: 4,
        padding: 4,
        align: "left",
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var m = monitor.getCellMetrics();
        var pxPerCol = m[2];
        // User's h is a HINT — snapCellRect returns a cell-aligned band so text
        // lands pixel-centered in its middle cell. See engine docs.
        var snap = monitor.snapCellRect(y, h);
        var snappedY = snap[0], snappedH = snap[1], textRow = snap[2];

        var bg = resolveColor(o.bg, 0xFF121827 | 0);
        if (bg !== 0) monitor.drawRect(x, snappedY, w, snappedH, bg);

        var accentColor;
        if (o.color !== null && o.color !== undefined) {
            accentColor = resolveColor(o.color, 0xFF3498DB | 0);
        } else {
            var token = _BANNER_STATE_TOKEN[o.style || "info"];
            accentColor = resolveColor(token || "edge", 0xFF2E3A4E | 0);
        }

        var edge = (o.edgeAccent === null || o.edgeAccent === undefined) ? 4 : o.edgeAccent;
        if (edge < 0) edge = 0;
        if (edge > w) edge = w;
        if (edge > 0) monitor.drawRect(x, snappedY, edge, snappedH, accentColor);

        var text = String(o.text == null ? "" : o.text);
        if (text === "") return;

        var padding = (o.padding === null || o.padding === undefined) ? 4 : o.padding;
        var textLeftPx = x + edge + padding;
        var textRightPx = x + w - padding;
        var textPx = text.length * pxPerCol;

        var startPx;
        if (o.align === "center") {
            startPx = textLeftPx + Math.floor(((textRightPx - textLeftPx) - textPx) / 2);
        } else if (o.align === "right") {
            startPx = textRightPx - textPx;
        } else {
            startPx = textLeftPx;
        }
        if (startPx < textLeftPx) startPx = textLeftPx;

        // ceil so the first glyph sits strictly to the right of accent+padding.
        var textCol = Math.max(0, Math.ceil(startPx / pxPerCol));

        var fg = resolveColor(o.textColor || "fg", 0xFFFFFFFF | 0);
        monitor.drawText(textCol, textRow, text, fg, 0);
    };
    return o;
};

// ============================================================
// Button -- mirror of ui_v1.lua Button. See that file for design notes.
// ============================================================

var _BUTTON_STYLE = {
    primary: "primary",
    ghost:   "ghost",
    danger:  "danger",
};

M.Button = function(props) {
    var o = {
        kind: 'Button',
        x: 0, y: 0, width: 0, height: 0,
        label: '',
        onClick: null,
        style: 'primary',
        enabled: true,
        visible: true,
        color: null,
        textColor: 'fg',
        borderColor: null,
        borderThickness: 2,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var m = monitor.getCellMetrics();
        var cols = m[0], pxPerCol = m[2];
        var snap = monitor.snapCellRect(y, h);
        var snappedY = snap[0], snappedH = snap[1], textRow = snap[2];

        var baseColor;
        if (o.color !== null && o.color !== undefined) {
            baseColor = resolveColor(o.color, 0xFF3498DB | 0);
        } else {
            var token = _BUTTON_STYLE[o.style || 'primary'] || 'primary';
            baseColor = resolveColor(token, 0xFF3498DB | 0);
        }

        var borderColor;
        if (o.borderColor !== null && o.borderColor !== undefined) {
            borderColor = resolveColor(o.borderColor, monitor.lighten(baseColor, 40));
        } else {
            borderColor = monitor.lighten(baseColor, 40);
        }

        var textColor = resolveColor(o.textColor || 'fg', 0xFFFFFFFF | 0);

        if (o.enabled === false) {
            baseColor = monitor.dim(baseColor);
            borderColor = monitor.dim(borderColor);
            textColor = monitor.dim(textColor);
        }

        monitor.drawRect(x, snappedY, w, snappedH, baseColor);

        var topHalf = Math.floor(snappedH / 2);
        if (topHalf > 0) {
            monitor.drawGradientV(x, snappedY, w, topHalf, monitor.lighten(baseColor, 30), baseColor);
        }

        var thickness = (o.borderThickness === null || o.borderThickness === undefined) ? 2 : o.borderThickness;
        if (thickness < 0) thickness = 0;
        if (thickness > 0) {
            monitor.drawRectOutline(x, snappedY, w, snappedH, borderColor, thickness);
        }

        // Text-cell cleanup: the text grid persists across redraws, so
        // shrinking the label (e.g. "POWER: OFF" -> "POWER: ON") would leave
        // stale trailing glyphs. Blank the full button cell-band at textRow
        // first. Mirror of ui_v1.lua.
        var leftCol = Math.max(0, Math.floor(x / pxPerCol));
        var rightCol = Math.min(cols - 1, Math.floor((x + w - 1) / pxPerCol));
        var bandCells = rightCol - leftCol + 1;
        if (bandCells > 0) {
            monitor.fillText(leftCol, textRow, bandCells, ' ', 0, 0);
        }

        var label = String(o.label == null ? '' : o.label);
        if (label === '') return;
        var textPx = label.length * pxPerCol;
        var startPx = x + Math.floor((w - textPx) / 2);
        if (startPx < x) startPx = x;
        var textCol = Math.max(0, Math.floor(startPx / pxPerCol));
        monitor.drawText(textCol, textRow, label, textColor, 0);
    };
    return o;
};

// ============================================================
// Toggle -- boolean switch. Mirror of ui_v1.lua Toggle. See that
// file for design notes. Observers use `onChange`; `onClick` in
// props is silently ignored (Toggle owns its own click handler).
// ============================================================

M.Toggle = function(props) {
    var o = {
        kind: 'Toggle',
        x: 0, y: 0, width: 0, height: 0,
        value: false,
        label: null,
        onLabel: 'ON',
        offLabel: 'OFF',
        onColor: 'good',
        offColor: 'ghost',
        textColor: 'fg',
        onChange: null,
        enabled: true,
        visible: true,
        borderThickness: 2,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.onClick = function(e) {
        if (o.enabled === false) return;
        o.value = !o.value;
        M.invalidate();
        if (typeof o.onChange === 'function') {
            try { o.onChange(o.value, e); }
            catch (err) { print('[ui] Toggle onChange error: ' + err); }
        }
    };
    o._stateLabel = function() {
        var base = o.value ? (o.onLabel || 'ON') : (o.offLabel || 'OFF');
        if (o.label && o.label !== '') return String(o.label) + ': ' + base;
        return base;
    };
    o.draw = function(monitor) {
        if (o.visible === false) return;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        if (w <= 0 || h <= 0) return;

        var m = monitor.getCellMetrics();
        var cols = m[0], pxPerCol = m[2];
        var snap = monitor.snapCellRect(y, h);
        var snappedY = snap[0], snappedH = snap[1], textRow = snap[2];

        var colorToken = o.value ? (o.onColor || 'good') : (o.offColor || 'ghost');
        var baseColor = resolveColor(colorToken, o.value ? (0xFF2ECC71 | 0) : (0xFF2E3A4E | 0));
        var borderColor = monitor.lighten(baseColor, 40);
        var textColor = resolveColor(o.textColor || 'fg', 0xFFFFFFFF | 0);

        if (o.enabled === false) {
            baseColor = monitor.dim(baseColor);
            borderColor = monitor.dim(borderColor);
            textColor = monitor.dim(textColor);
        }

        monitor.drawRect(x, snappedY, w, snappedH, baseColor);
        var topHalf = Math.floor(snappedH / 2);
        if (topHalf > 0) {
            monitor.drawGradientV(x, snappedY, w, topHalf, monitor.lighten(baseColor, 30), baseColor);
        }
        var thickness = (o.borderThickness === null || o.borderThickness === undefined) ? 2 : o.borderThickness;
        if (thickness > 0) {
            monitor.drawRectOutline(x, snappedY, w, snappedH, borderColor, thickness);
        }

        var leftCol = Math.max(0, Math.floor(x / pxPerCol));
        var rightCol = Math.min(cols - 1, Math.floor((x + w - 1) / pxPerCol));
        var bandCells = rightCol - leftCol + 1;
        if (bandCells > 0) {
            monitor.fillText(leftCol, textRow, bandCells, ' ', 0, 0);
        }

        var label = o._stateLabel();
        if (label === '') return;
        var textPx = label.length * pxPerCol;
        var startPx = x + Math.floor((w - textPx) / 2);
        if (startPx < x) startPx = x;
        var textCol = Math.max(0, Math.floor(startPx / pxPerCol));
        monitor.drawText(textCol, textRow, label, textColor, 0);
    };
    return o;
};

// ============================================================
// Spacer -- eats remaining space on the container's main axis. No
// draw chrome. Default flex = 1. Use Spacer({ flex: 2 }) to claim
// a larger share of the remainder.
// ============================================================

M.Spacer = function(props) {
    var o = {
        kind: 'Spacer',
        x: 0, y: 0, width: 0, height: 0,
        flex: 1,
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    // Spacer draws nothing; layout uses it purely to claim flex space.
    o.draw = function(monitor) {};
    return o;
};

// ============================================================
// Container base -- shared layout + draw walkers for VBox, HBox, Card.
//
// Layout algorithm (one-pass, Flutter-style):
//   1. Compute the container's inner rect (outer rect minus padding).
//   2. For each child: if `flex` is set and > 0, remember it for pass 2.
//      Otherwise treat its current width/height as a fixed claim along the
//      main axis.
//   3. Distribute the remainder across flex children proportionally to
//      their flex weights. Non-flex children keep their explicit size.
//   4. Assign (x, y, width, height) to each child and recurse into
//      containers (layout() is idempotent, so repeat layout calls are safe).
//
// Cross-axis: non-flex children get their declared cross-axis size if set,
// else they stretch to fill the inner cross extent. This matches the
// common dashboard case (stacked cards, each spanning the container width).
// ============================================================

// VBox children stack along Y; HBox children stack along X; Card is a VBox
// with extra chrome (bg rect + border). Orientation is derived from `kind`
// so all three share one layout impl.
function _mainAxis(kind) {
    if (kind === 'HBox') return 'h';
    return 'v';  // VBox + Card both vertical
}

// Cards must be cell-grid-aligned so contained Labels sit symmetrically:
// cells are a fixed unit and drawText positions text at cell boundaries, so
// a Card whose (size - 2*pad) doesn't match its content's cell parity will
// render the label closer to one edge than the other. Grow the Card just
// enough to match parity.
function _cardAlignedSize(contentPx, pad, unit) {
    var contentCells = Math.max(1, Math.ceil(contentPx / unit));
    var desired = contentPx + 2 * pad;
    var totalCells = Math.max(contentCells, Math.ceil(desired / unit));
    if ((totalCells - contentCells) % 2 !== 0) totalCells += 1;
    return totalCells * unit;
}

// Round v up to the next multiple of unit.
function _snapUp(v, unit) {
    var rem = v % unit;
    if (rem === 0) return v;
    return v + (unit - rem);
}

// Hug-content size: sum of visible children on the main axis (plus gaps +
// padding), max on the cross axis. Recurses via each child's measure().
// Children without measure() fall back to explicit width/height.
function _measureContainer(self, monitor) {
    var axis = _mainAxis(self.kind);
    var pad = self.padding || 0;
    var gap = self.gap || 0;
    var mainSum = 0;
    var crossMax = 0;
    var visibleCount = 0;
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        visibleCount++;
        var dw, dh;
        if (typeof c.measure === 'function') {
            var m = c.measure(monitor);
            dw = m[0]; dh = m[1];
        } else {
            dw = c.width || 0;
            dh = c.height || 0;
        }
        if (axis === 'v') {
            if (dw > crossMax) crossMax = dw;
            mainSum += dh;
        } else {
            if (dh > crossMax) crossMax = dh;
            mainSum += dw;
        }
    }
    var gaps = gap * Math.max(0, visibleCount - 1);
    // Cross-axis "0" is a stretch signal: if no child declared any cross-axis
    // size, we have nothing to hug to, so report 0 and let the parent stretch
    // us to its inner extent. Main axis always reports hug-sum (content-driven).
    var w, h;
    if (axis === 'v') {
        w = crossMax > 0 ? (crossMax + 2 * pad) : 0;
        h = mainSum + gaps + 2 * pad;
    } else {
        w = mainSum + gaps + 2 * pad;
        h = crossMax > 0 ? (crossMax + 2 * pad) : 0;
    }
    if (self.kind === 'Card' && monitor && typeof monitor.getCellMetrics === 'function') {
        var m = monitor.getCellMetrics();
        var pxPerCol = m[2], pxPerRow = m[3];
        var contentW = axis === 'v' ? crossMax : (mainSum + gaps);
        var contentH = axis === 'v' ? (mainSum + gaps) : crossMax;
        w = contentW > 0 ? _cardAlignedSize(contentW, pad, pxPerCol) : 0;
        h = contentH > 0 ? _cardAlignedSize(contentH, pad, pxPerRow) : 0;
    }
    return [w, h];
}

function _layoutContainer(self, monitor) {
    if (self.visible === false) return;
    var axis = _mainAxis(self.kind);
    var pad = self.padding || 0;
    var gap = self.gap || 0;

    var innerX = (self.x || 0) + pad;
    var innerY = (self.y || 0) + pad;
    var innerW = Math.max(0, (self.width || 0) - 2 * pad);
    var innerH = Math.max(0, (self.height || 0) - 2 * pad);

    var children = self.children || [];
    var visibleCount = 0;
    for (var i = 0; i < children.length; i++) {
        if (children[i].visible !== false) visibleCount++;
    }
    var gaps = gap * Math.max(0, visibleCount - 1);

    // Pass 1: tally flex + fixed claims on the main axis. Hug-content rule:
    // if a child has no flex and no explicit main-axis size, ask it to
    // measure itself so it sizes to its content.
    var totalFixed = 0;
    var totalFlex = 0;
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        var flex = c.flex || 0;
        if (flex > 0) {
            totalFlex += flex;
        } else {
            var explicit = axis === 'v' ? (c.height || 0) : (c.width || 0);
            if (explicit <= 0 && monitor && typeof c.measure === 'function') {
                var mres = c.measure(monitor);
                if (axis === 'v') { c.height = mres[1]; explicit = mres[1]; }
                else { c.width = mres[0]; explicit = mres[0]; }
            }
            totalFixed += explicit;
        }
    }

    var mainExtent = axis === 'v' ? innerH : innerW;
    var remainder = Math.max(0, mainExtent - totalFixed - gaps);

    // Pass 2: assign rects + recurse into children that also lay out.
    var cursor = axis === 'v' ? innerY : innerX;
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        var flex = c.flex || 0;
        var mainSize;
        if (flex > 0) {
            mainSize = totalFlex > 0 ? Math.floor(remainder * flex / totalFlex) : 0;
        } else {
            mainSize = axis === 'v' ? (c.height || 0) : (c.width || 0);
        }

        if (axis === 'v') {
            c.x = innerX;
            c.y = cursor;
            // Cross-axis: honor explicit width > measured width > stretch.
            // A measure of 0 means "no content-hug preference" -- stretch
            // to the parent's innerW. This lets a VBox of measure-less
            // widgets (e.g. Toggles) inherit the parent width down the
            // tree instead of collapsing to 0.
            if ((c.width || 0) <= 0) {
                var dw = 0;
                if (monitor && typeof c.measure === 'function') {
                    var cm = c.measure(monitor);
                    dw = cm[0] || 0;
                }
                c.width = dw > 0 ? dw : innerW;
            }
            c.height = mainSize;
        } else {
            c.x = cursor;
            c.y = innerY;
            c.width = mainSize;
            if ((c.height || 0) <= 0) {
                var dh = 0;
                if (monitor && typeof c.measure === 'function') {
                    var cm2 = c.measure(monitor);
                    dh = cm2[1] || 0;
                }
                c.height = dh > 0 ? dh : innerH;
            }
        }

        if (typeof c.layout === 'function') c.layout(monitor);
        cursor += mainSize + gap;
    }
}

function _drawContainer(self, monitor) {
    if (self.visible === false) return;
    // Card-only chrome: bg rect + optional border. VBox/HBox are invisible
    // grouping shells unless the user sets `bg` explicitly.
    if (self.kind === 'Card' || (self.bg !== null && self.bg !== undefined)
            || (self.border !== null && self.border !== undefined)) {
        var bgDefault = (self.kind === 'Card') ? (0xFF121827 | 0) : 0;
        var bg = resolveColor(self.bg, bgDefault);
        if (bg !== 0) monitor.drawRect(self.x, self.y, self.width, self.height, bg);
        var border = self.border;
        if ((border === null || border === undefined) && self.kind === 'Card') border = 'edge';
        if (border !== null && border !== undefined) {
            var bc = resolveColor(border, 0);
            if (bc !== 0) monitor.drawRectOutline(self.x, self.y, self.width, self.height, bc, 1);
        }
    }
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible !== false && typeof c.draw === 'function') c.draw(monitor);
    }
}

function _makeContainer(kind, props) {
    var o = {
        kind: kind,
        x: 0, y: 0, width: 0, height: 0,
        padding: 0,
        gap: 0,
        children: [],
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    // Container bounds (not children) are the hit-target for the container
    // itself; leaf hit-testing walks children via _findLeafAt.
    o.hittest = function(px, py) {
        if (o.visible === false) return false;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        return px >= x && px < x + w && py >= y && py < y + h;
    };
    o.measure = function(monitor) { return _measureContainer(o, monitor); };
    o.layout = function(monitor) { _layoutContainer(o, monitor); };
    o.draw = function(monitor) { _drawContainer(o, monitor); };
    return o;
}

M.VBox = function(props) { return _makeContainer('VBox', props); };
M.HBox = function(props) { return _makeContainer('HBox', props); };

M.Card = function(props) {
    // Card defaults: padding=4, bg='bgCard', border='edge' (applied before
    // the user's props override so the user can still set bg=null etc).
    var merged = { padding: 4, bg: 'bgCard', border: 'edge' };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) merged[k] = props[k];
    return _makeContainer('Card', merged);
};

// ============================================================
// Stack: overlay container. Every child fills the Stack's inner rect;
// children are drawn back-to-front (first in list = behind, last = on
// top). Hit-test walks front-to-back so the topmost hit wins. Useful
// for layering a Banner over a Card, a tint over an Icon, or a Label
// over a Bar. `flex` is ignored; children always receive the full
// inner rect.
// ============================================================

function _measureStack(self, monitor) {
    var pad = self.padding || 0;
    var mw = 0, mh = 0;
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        var dw, dh;
        if (typeof c.measure === 'function') {
            var m = c.measure(monitor);
            dw = m[0]; dh = m[1];
        } else {
            dw = c.width || 0;
            dh = c.height || 0;
        }
        if (dw > mw) mw = dw;
        if (dh > mh) mh = dh;
    }
    var w = mw > 0 ? (mw + 2 * pad) : 0;
    var h = mh > 0 ? (mh + 2 * pad) : 0;
    return [w, h];
}

function _layoutStack(self, monitor) {
    if (self.visible === false) return;
    var pad = self.padding || 0;
    var innerX = (self.x || 0) + pad;
    var innerY = (self.y || 0) + pad;
    var innerW = Math.max(0, (self.width || 0) - 2 * pad);
    var innerH = Math.max(0, (self.height || 0) - 2 * pad);
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        c.x = innerX; c.y = innerY;
        c.width = innerW; c.height = innerH;
        if (typeof c.layout === 'function') c.layout(monitor);
    }
}

function _drawStack(self, monitor) {
    if (self.visible === false) return;
    if ((self.bg !== null && self.bg !== undefined)
            || (self.border !== null && self.border !== undefined)) {
        var bg = resolveColor(self.bg, 0);
        if (bg !== 0) monitor.drawRect(self.x, self.y, self.width, self.height, bg);
        if (self.border !== null && self.border !== undefined) {
            var bc = resolveColor(self.border, 0);
            if (bc !== 0) monitor.drawRectOutline(self.x, self.y, self.width, self.height, bc, 1);
        }
    }
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible !== false && typeof c.draw === 'function') c.draw(monitor);
    }
}

M.Stack = function(props) {
    var o = {
        kind: 'Stack',
        x: 0, y: 0, width: 0, height: 0,
        padding: 0,
        children: [],
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    _mixWidget(o);
    o.hittest = function(px, py) {
        if (o.visible === false) return false;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        return px >= x && px < x + w && py >= y && py < y + h;
    };
    o.measure = function(monitor) { return _measureStack(o, monitor); };
    o.layout = function(monitor) { _layoutStack(o, monitor); };
    o.draw = function(monitor) { _drawStack(o, monitor); };
    return o;
};

// ============================================================
// Flow: responsive row-wrap container. Packs children left-to-right
// into rows, wrapping to the next row whenever the cursor + next
// child would exceed the container's inner width. Row height equals
// the tallest child in that row; the Flow grows its own height to
// cover every row. `flex` is ignored (wrap + flex don't mix).
// ============================================================

function _measureFlow(self, monitor) {
    var pad = self.padding || 0;
    var gap = self.gap || 0;
    var sum = 0, maxH = 0, count = 0;
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        count++;
        var dw, dh;
        if (typeof c.measure === 'function') {
            var m = c.measure(monitor);
            dw = m[0]; dh = m[1];
        } else { dw = c.width || 0; dh = c.height || 0; }
        sum += dw;
        if (dh > maxH) maxH = dh;
    }
    var gaps = gap * Math.max(0, count - 1);
    return [sum + gaps + 2 * pad, maxH + 2 * pad];
}

function _layoutFlow(self, monitor) {
    if (self.visible === false) return;
    var pad = self.padding || 0;
    var gap = self.gap || 0;
    var innerX = (self.x || 0) + pad;
    var innerY = (self.y || 0) + pad;
    var innerW = Math.max(0, (self.width || 0) - 2 * pad);

    // Cell-align padding and gap when any child is a Card, so the row/col
    // cursor stays on the cell grid and Cards never need post-hoc fudging.
    var hasCard = false;
    var _children = self.children || [];
    for (var _i = 0; _i < _children.length; _i++) {
        if (_children[_i].kind === 'Card') { hasCard = true; break; }
    }
    if (hasCard && monitor && typeof monitor.getCellMetrics === 'function') {
        var _m = monitor.getCellMetrics();
        var _pxCol = _m[2], _pxRow = _m[3];
        innerX = _snapUp(innerX, _pxCol);
        innerY = _snapUp(innerY, _pxRow);
        if (gap > 0) gap = _snapUp(gap, Math.min(_pxCol, _pxRow));
    }

    var rows = [];
    var row = { items: [], width: 0, height: 0 };
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible === false) continue;
        var dw, dh;
        if (typeof c.measure === 'function') {
            var m = c.measure(monitor);
            dw = m[0]; dh = m[1];
        } else { dw = c.width || 0; dh = c.height || 0; }
        var prospective = row.width + (row.items.length > 0 ? gap : 0) + dw;
        if (row.items.length > 0 && prospective > innerW) {
            rows.push(row);
            row = { items: [], width: 0, height: 0 };
            prospective = dw;
        }
        row.items.push({ child: c, w: dw, h: dh });
        row.width = prospective;
        if (dh > row.height) row.height = dh;
    }
    if (row.items.length > 0) rows.push(row);

    var cursorY = innerY;
    for (var ri = 0; ri < rows.length; ri++) {
        var r = rows[ri];
        var cursorX = innerX;
        for (var j = 0; j < r.items.length; j++) {
            var entry = r.items[j];
            var cc = entry.child;
            cc.x = cursorX;
            cc.y = cursorY;
            cc.width = entry.w;
            cc.height = entry.h;
            if (typeof cc.layout === 'function') cc.layout(monitor);
            cursorX += entry.w + gap;
        }
        cursorY += r.height;
        if (ri < rows.length - 1) cursorY += gap;
    }

    if (!self._explicitHeight) {
        self.height = (cursorY - (self.y || 0)) + pad;
    }
}

function _drawFlow(self, monitor) {
    if (self.visible === false) return;
    if ((self.bg !== null && self.bg !== undefined) || (self.border !== null && self.border !== undefined)) {
        var bg = resolveColor(self.bg, 0);
        if (bg !== 0) monitor.drawRect(self.x, self.y, self.width, self.height, bg);
        if (self.border !== null && self.border !== undefined) {
            var bc = resolveColor(self.border, 0);
            if (bc !== 0) monitor.drawRectOutline(self.x, self.y, self.width, self.height, bc, 1);
        }
    }
    var children = self.children || [];
    for (var i = 0; i < children.length; i++) {
        var c = children[i];
        if (c.visible !== false && typeof c.draw === 'function') c.draw(monitor);
    }
}

M.Flow = function(props) {
    var o = {
        kind: 'Flow',
        x: 0, y: 0, width: 0, height: 0,
        padding: 0,
        gap: 0,
        children: [],
        visible: true,
    };
    if (props) for (var k in props) if (props.hasOwnProperty(k)) o[k] = props[k];
    o._explicitHeight = !!(props && props.height && props.height > 0);
    _mixWidget(o);
    o.hittest = function(px, py) {
        if (o.visible === false) return false;
        var x = o.x || 0, y = o.y || 0;
        var w = o.width || 0, h = o.height || 0;
        return px >= x && px < x + w && py >= y && py < y + h;
    };
    o.measure = function(monitor) { return _measureFlow(o, monitor); };
    o.layout = function(monitor) { _layoutFlow(o, monitor); };
    o.draw = function(monitor) { _drawFlow(o, monitor); };
    return o;
};

// ============================================================
// Mount / unmount / render. Container roots auto-size to the monitor's
// pixel bounds when the user didn't pin width/height, and layout() is
// invoked so descendants receive concrete rects.
// ============================================================

function _isContainer(widget) {
    var k = widget.kind;
    return k === 'VBox' || k === 'HBox' || k === 'Card' || k === 'Flow' || k === 'Stack';
}

M.mount = function(monitor, widget) {
    if (!monitor) throw new Error('ui.mount: monitor is required');
    if (!widget) throw new Error('ui.mount: widget is required');
    var state = _findState(monitor);
    if (!state) { state = { monitor: monitor, widgets: [] }; _monitors.push(state); }
    state.widgets.push(widget);

    if (_isContainer(widget)) {
        widget.x = widget.x || 0;
        widget.y = widget.y || 0;
        if ((widget.width || 0) <= 0 || (widget.height || 0) <= 0) {
            var psize = monitor.getPixelSize();
            if ((widget.width || 0) <= 0) widget.width = psize[0];
            if ((widget.height || 0) <= 0) widget.height = psize[1];
        }
        if (typeof widget.layout === 'function') widget.layout(monitor);
    }

    M.invalidate();
    return widget;
};

M.unmount = function(monitor) {
    for (var i = 0; i < _monitors.length; i++) {
        if (_monitors[i].monitor === monitor) { _monitors.splice(i, 1); break; }
    }
    M.invalidate();
};

function _renderOne(monitor, state) {
    for (var i = 0; i < state.widgets.length; i++) {
        var w = state.widgets[i];
        if (w.visible !== false && typeof w.draw === 'function') w.draw(monitor);
    }
}

M.render = function(monitor) {
    if (monitor) {
        var state = _findState(monitor);
        if (state) {
            state.iconClearPending = true;
            _renderOne(monitor, state);
        }
    } else {
        for (var i = 0; i < _monitors.length; i++) {
            _monitors[i].iconClearPending = true;
            _renderOne(_monitors[i].monitor, _monitors[i]);
        }
    }
    _dirty = false;
};

// ============================================================
// Event loop
// ============================================================

// Tree-walking hit-test. Visits children last-to-first (top-of-z-order wins)
// and returns the deepest leaf whose bounds contain (px, py). Containers can
// be hit when they have no matching child -- callers filter on
// `enabled !== false` + `typeof onClick === 'function'` themselves.
function _findLeafAt(widget, px, py) {
    if (widget.visible === false) return null;
    var children = widget.children;
    if (children && children.length > 0) {
        for (var i = children.length - 1; i >= 0; i--) {
            var hit = _findLeafAt(children[i], px, py);
            if (hit) return hit;
        }
    }
    if (typeof widget.hittest === 'function' && widget.hittest(px, py)) {
        return widget;
    }
    return null;
}

function _dispatchTouch(col, row, px, py, player) {
    for (var i = 0; i < _monitors.length; i++) {
        var state = _monitors[i];
        var hit = null;
        for (var j = state.widgets.length - 1; j >= 0; j--) {
            var root = state.widgets[j];
            var candidate = _findLeafAt(root, px, py);
            if (candidate && candidate.enabled !== false
                    && typeof candidate.onClick === 'function') {
                hit = candidate;
                break;
            }
        }
        if (hit) {
            var evObj = {
                type: 'click',
                widget: hit,
                monitor: state.monitor,
                col: col, row: row, px: px, py: py,
                player: player,
                _stopped: false,
                stopPropagation: function() { this._stopped = true; },
            };
            try { hit.onClick(evObj); }
            catch (e) { print('[ui] onClick error: ' + e); }
        }
    }
}

M.exit = function() { _running = false; __uiExit(); };

M.run = function() {
    M.render();
    _running = true;
    __uiRun(function(ev) {
        if (!ev || !ev.name) return;
        if (ev.name === 'monitor_touch') {
            var a = ev.args || [];
            _dispatchTouch(a[0], a[1], a[2] || 0, a[3] || 0, a[4]);
        }
        if (_dirty) M.render();
    });
    _running = false;
};

module.exports = M;
