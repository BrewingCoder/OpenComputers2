#!/bin/sh -e
# Runs as root inside the oc2-buildroot container. Fixes ownership on the
# named volumes (Docker mounts them as root by default), then drops to the
# unprivileged `builder` user for the actual build (Buildroot refuses root).

cd /home/builder/minux

# Named volumes mount fresh as root-owned; chown them once before make runs.
# Cheap if already owned by builder (no recursion through GBs of artifacts).
if [ "$(stat -c %u /home/builder/minux/output)" != "$(id -u builder)" ]; then
    chown builder:builder /home/builder/minux/output
fi
if [ "$(stat -c %u /home/builder/minux/dl)" != "$(id -u builder)" ]; then
    chown builder:builder /home/builder/minux/dl
fi
mkdir -p /host-output
chown builder:builder /host-output

# Drop to builder. `runuser` resets $HOME to the target user's home (unlike
# `su -p` which preserves the caller's). Without this, kconfig/git tools
# scribble warnings about /root/.config being inaccessible.
exec runuser -u builder -- /bin/sh -c '
    set -e
    cd /home/builder/minux

    # Two patches to minux'\''s Makefile, both idempotent:
    #
    # 1) SKIP_LEGACY=y in COMMON_CONFIG_ENV. minux pinned Buildroot 2025.11,
    #    but its checked-in .config carries dozens of legacy options
    #    (BR2_PACKAGE_DIRECTFB_*, BR2_BINUTILS_VERSION_2_2x, ...) that
    #    moved to Config.in.legacy. With SKIP_LEGACY= empty (the default),
    #    conf prompts INTERACTIVELY for each — and stdin is closed in
    #    non-interactive builds → kconfig aborts. SKIP_LEGACY=y silently
    #    drops them.
    #
    # 2) Force CONFIG_DIR := $(O) for in-tree builds (where O = CURDIR/output).
    #    Buildroot'\''s Makefile has a special case (lines ~165-170) that sets
    #    CONFIG_DIR := $(CURDIR) when O = $(CURDIR)/output, so BR2_CONFIG
    #    becomes source-root/.config. kconfig'\''s syncconfig writes temp
    #    files in BR2_CONFIG'\''s directory then RENAMES them to KCONFIG_*
    #    targets (which live in output/). When source-root and output/
    #    are on different mounts (docker named volumes!), rename(2) fails
    #    with EXDEV → "Error during update of the configuration".
    #    Patching CONFIG_DIR := $(O) in BOTH branches puts the temp files
    #    on the output volume, same FS as the rename targets.
    sed -i '\''s|^\(\s*\)SKIP_LEGACY=$|\1SKIP_LEGACY=y|'\'' Makefile
    sed -i '\''s|^CONFIG_DIR := \$(CURDIR)$|CONFIG_DIR := $(O)|'\'' Makefile

    # Sync any buildroot drift in minux'\''s checked-in .config: olddefconfig
    # silently picks defaults for new options (e.g. options added between the
    # commit minux pinned and any host-side patches), avoiding interactive
    # prompts when syncconfig later runs as part of the main make.
    #
    # With CONFIG_DIR patched above, BR2_CONFIG = $(O)/.config = output/.config.
    # olddefconfig still reads from source-root /.config first (since that'\''s
    # what minux ships) then writes to BR2_CONFIG. We seed output/.config
    # by copying source-root /.config into place before olddefconfig.
    mkdir -p output
    if [ ! -f output/.config ]; then
        cp .config output/.config
    fi
    echo "==> make olddefconfig (sync .config to current buildroot)"
    make olddefconfig

    echo "==> Building rootfs + kernel + OpenSBI (this is the slow one)"
    make -j"$(nproc)"

    echo "==> Building bootfs.squashfs (small initramfs)"
    ./minux-bootfs/build.sh

    echo "==> Copying outputs to /host-output"
    cp -v output/images/Image          /host-output/
    cp -v output/images/fw_jump.bin    /host-output/
    cp -v output/images/rootfs.cramfs  /host-output/
    cp -v minux-bootfs/bootfs.squashfs /host-output/

    echo "==> Done. Sizes:"
    ls -lh /host-output/
'
