#!/usr/bin/env bash
# Bake the MSDF atlas + metadata for the terminal renderer.
#
# Inputs (assumed already on disk):
#   - msdf-atlas-gen built at ../_reference/msdf-atlas-gen/build/bin/msdf-atlas-gen
#     (see README — v1.3 release tag, configured with -DMSDF_ATLAS_USE_VCPKG=OFF
#     -DMSDF_ATLAS_USE_SKIA=OFF -DMSDF_ATLAS_NO_ARTERY_FONT=ON)
#   - JetBrains Mono Regular TTF in /tmp/jbmono/fonts/ttf/  (one-time download
#     from https://download.jetbrains.com/fonts/JetBrainsMono-2.304.zip)
#
# Output: writes the atlas PNG + JSON into the mod's resources, replacing the
# previous version. Re-run any time you tune parameters (size, pxrange, dimensions).
#
# Tunables:
#   -size       em-size in atlas pixels. Larger = sharper at runtime, bigger PNG.
#   -pxrange    distance range in atlas pixels. 4 is recommended for ASCII; bump
#               to 6-8 if you see edge artifacts on diagonal strokes.
#   -dimensions atlas size. 256x256 fits printable ASCII at -size 32. Bump to
#               512x512 if extending to extended Unicode ranges.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MSDFGEN="${MSDFGEN:-$ROOT/../_reference/msdf-atlas-gen/build/bin/msdf-atlas-gen}"
TTF="${TTF:-/tmp/jbmono/fonts/ttf/JetBrainsMono-Regular.ttf}"
OUT_PNG="$ROOT/src/main/resources/assets/oc2/textures/font/jbmono_msdf.png"
OUT_JSON="$ROOT/src/main/resources/assets/oc2/font_metadata/jbmono_msdf.json"

[[ -x "$MSDFGEN" ]] || { echo "msdf-atlas-gen not found at $MSDFGEN"; exit 1; }
[[ -f "$TTF" ]] || { echo "JetBrains Mono TTF not found at $TTF"; exit 1; }

"$MSDFGEN" \
  -font "$TTF" \
  -charset <(printf '[0x20, 0x7E], [0xA0, 0xFF], [0x2500, 0x257F]') \
  -type msdf \
  -format png \
  -size 32 \
  -pxrange 4 \
  -dimensions 512 512 \
  -imageout "$OUT_PNG" \
  -json "$OUT_JSON" \
  -fontname "JetBrainsMono"

echo "wrote $OUT_PNG ($(stat -f '%z' "$OUT_PNG") bytes)"
echo "wrote $OUT_JSON ($(stat -f '%z' "$OUT_JSON") bytes)"
