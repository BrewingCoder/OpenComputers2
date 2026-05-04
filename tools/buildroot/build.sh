#!/bin/sh -e
# Host-side wrapper. Builds the Docker image (cached after first run) and
# invokes the buildroot pipeline inside it. Outputs land in ./output/.
#
# Usage:
#   ./build.sh                # full build (1–2 hours first time, ~minutes thereafter)
#   ./build.sh shell          # drop into the container for poking around
#   ./build.sh menuconfig     # interactive buildroot config editor
#   ./build.sh clean          # wipe build cache (NOT the dl/ cache)
#   ./build.sh distclean      # wipe everything including downloads
#
# Caching:
#   - Docker layer cache covers the toolchain install (debian deps).
#   - Named volume oc2-buildroot-dl  caches buildroot's source downloads (~1 GB).
#   - Named volume oc2-buildroot-out caches build artifacts (toolchain, kernel
#     intermediates, etc — ~20 GB). Without this, every run is full rebuild.

cd "$(dirname "$0")"
mkdir -p output

IMAGE=oc2-buildroot:2025.11
DL_VOL=oc2-buildroot-dl
OUT_VOL=oc2-buildroot-out

cmd="${1:-build}"

docker build -t "$IMAGE" .

case "$cmd" in
    build)
        docker run --rm \
            -v "$DL_VOL:/home/builder/minux/dl" \
            -v "$OUT_VOL:/home/builder/minux/output" \
            -v "$(pwd)/output:/host-output" \
            "$IMAGE"
        ;;
    shell)
        docker run --rm -it \
            -v "$DL_VOL:/home/builder/minux/dl" \
            -v "$OUT_VOL:/home/builder/minux/output" \
            -v "$(pwd)/output:/host-output" \
            --entrypoint /bin/bash \
            "$IMAGE"
        ;;
    menuconfig)
        docker run --rm -it \
            -v "$DL_VOL:/home/builder/minux/dl" \
            -v "$OUT_VOL:/home/builder/minux/output" \
            --entrypoint make \
            "$IMAGE" menuconfig
        ;;
    clean)
        docker volume rm -f "$OUT_VOL"
        ;;
    distclean)
        docker volume rm -f "$OUT_VOL" "$DL_VOL"
        rm -rf output
        ;;
    *)
        echo "unknown command: $cmd" >&2
        echo "usage: $0 [build|shell|menuconfig|clean|distclean]" >&2
        exit 2
        ;;
esac
