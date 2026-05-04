# OC2 RISC-V firmware build environment.
#
# Builds the four blobs the Control Plane VM needs:
#   - Image            (Linux kernel, RISC-V64)
#   - fw_jump.bin      (OpenSBI firmware)
#   - rootfs.cramfs    (Buildroot userland — bash, busybox, lua 5.4, codecs)
#   - bootfs.squashfs  (boot-stage initramfs that mounts overlay & switches root)
#
# Forks North-Western-Development/minux at a pinned commit. minux is itself
# Buildroot 2025.11 with a sedna-riscv64 board overlay layered on top — that
# overlay is what wires the OC2 peripheral RPC into Linux's virtio-9p layer.

FROM debian:bookworm-slim

# Buildroot's documented host prerequisites for a riscv64-musl toolchain build.
# https://buildroot.org/downloads/manual/manual.html#requirement
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash bc binutils bison build-essential bzip2 ca-certificates \
        cpio diffutils file findutils flex g++ gawk gcc git gzip iputils-ping \
        libncurses-dev libssl-dev locales make patch perl python3 rsync sed \
        squashfs-tools tar unzip wget which xxd xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
    && locale-gen

ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# Buildroot refuses to build as root, so we provision a non-root account but
# leave the image running as root: entrypoint.sh chowns the named volumes
# (which Docker mounts as root by default) before dropping privileges with
# `su -p builder`. Trying to USER-switch in the Dockerfile would lock us out
# of the volume mount points.
RUN useradd --create-home --shell /bin/bash builder \
    && install -d -o builder -g builder /home/builder/work \
    && apt-get update && apt-get install -y --no-install-recommends sudo \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /home/builder

# Pin the upstream minux ref so rebuilds are reproducible. Bump deliberately.
ARG MINUX_REF=8d2d3c4f91761d019f2371e75350d98f6aa9f0e5
ARG MINUX_URL=https://github.com/North-Western-Development/minux.git

RUN git clone --no-checkout "${MINUX_URL}" /home/builder/minux \
    && cd /home/builder/minux \
    && git checkout "${MINUX_REF}" \
    && chown -R builder:builder /home/builder/minux

WORKDIR /home/builder/minux

COPY entrypoint.sh /home/builder/entrypoint.sh
RUN chmod +x /home/builder/entrypoint.sh

# Outputs land at /host-output (caller bind-mounts).
# Buildroot dl/ + output/ should be cached via named volumes — see build.sh.
ENTRYPOINT ["/home/builder/entrypoint.sh"]
