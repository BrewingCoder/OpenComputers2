# OC2 Control Plane firmware build

Builds the four firmware blobs the Tier-2 Control Plane VM needs:

| File | Role |
|---|---|
| `Image` | Linux kernel, RISC-V64 little-endian. Loaded at `0x80200000`. |
| `fw_jump.bin` | OpenSBI firmware. Loaded at `0x80000000`, jumps to kernel. |
| `bootfs.squashfs` | virtio-blk vda. Tiny initramfs that mounts overlay then `chroot`s. |
| `rootfs.cramfs` | virtio-blk vdb. Buildroot userland: bash, busybox, lua 5.4, codecs. |

The kernel + rootfs are forked from [North-Western-Development/minux](https://github.com/North-Western-Development/minux) (Buildroot 2025.11 with a `sedna-riscv64` board overlay). minux is what OC2R ships; it's the well-trodden path and provides the virtio-9p peripheral RPC plumbing OC2 uses.

## Quick start

Requires Docker. On Windows: Docker Desktop with WSL2 backend.

```sh
./build.sh        # full build, 1–2 hours first time
```

Outputs land in `./output/`. Once done they're picked up by the gradle resource step (TODO — wiring not yet done).

## How it works

`Dockerfile` provisions a Debian Bookworm image with Buildroot's host prerequisites and clones minux at a pinned commit. `entrypoint.sh` runs `make` inside the container, then runs minux's `minux-bootfs/build.sh` to assemble the secondary initramfs, then copies the four blobs to `/host-output` (bind-mounted to `./output/`).

Two named Docker volumes cache state across runs:

- `oc2-buildroot-dl` — Buildroot's source download cache (~1 GB). Survives `clean`.
- `oc2-buildroot-out` — Build artifacts including the toolchain (~20 GB). Wiped by `clean`.

## Subcommands

```sh
./build.sh build       # default — full pipeline
./build.sh shell       # interactive container shell, build cache mounted
./build.sh menuconfig  # buildroot's curses config editor
./build.sh clean       # wipe build cache, keep dl/
./build.sh distclean   # wipe everything including downloads + ./output
```

## Pinned upstream

- minux ref: `8d2d3c4f91761d019f2371e75350d98f6aa9f0e5` (branch `2025.11.x`, 2026-02-26)

To bump: edit `Dockerfile`'s `MINUX_REF` arg, run `./build.sh distclean && ./build.sh`.

## License

The build inputs and outputs follow upstream licensing:

- **Linux kernel** — GPL-2.0
- **OpenSBI** — BSD-2-Clause
- **Buildroot tooling** — GPL-2.0
- **Busybox** — GPL-2.0
- **Lua 5.4** — MIT
- **musl libc** — MIT

Per GPL-2.0, when we redistribute the kernel + busybox binaries, we must offer corresponding source. The minux fork pinned above is itself the corresponding source (it's a Buildroot tree, which produces the binaries). When we cut a release with bundled blobs, ship a `LICENSES/` directory and a `NOTICE.md` pointing back to that repo + commit.
