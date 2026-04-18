package com.brewingcoder.oc2.platform.storage

/**
 * Hands out the per-computer writable root mount and the shared read-only ROM mount.
 *
 * One implementation per host environment:
 *   - production: `WorldStorageProvider` resolves to `<world>/oc2/computer/<id>/`
 *   - tests: an in-memory provider built on [InMemoryMount]
 *
 * Two-mount stack matches CC:Tweaked: the VM's filesystem layer composes a writable
 * `/` (per-computer HDD, returned by [rootMountFor]) and a read-only `/rom`
 * (shared across all computers, returned by [romMount]).
 */
interface StorageProvider {
    /** Per-computer writable root. Same id always returns a mount over the same bytes. */
    fun rootMountFor(computerId: Int): WritableMount

    /** Shared read-only ROM (boot scripts, libraries, default programs). */
    fun romMount(): Mount
}
