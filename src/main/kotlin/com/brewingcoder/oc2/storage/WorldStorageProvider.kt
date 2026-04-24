package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.Mount
import com.brewingcoder.oc2.platform.storage.StorageProvider
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.nio.file.Path

/**
 * Resolves per-computer storage to `<root>/computer/<id>/` on disk. [root] is
 * `<world>/oc2/` in production (set up by [OC2ServerContext]) or a tmp dir in
 * tests.
 *
 * ROM is a [ResourceMount] over `assets/oc2/rom/` in the mod jar. Contains boot
 * scripts, shared libraries (e.g. `ui_v1.lua`), default programs — all mounted
 * read-only at `/rom/` inside each computer via [com.brewingcoder.oc2.platform.storage.UnionMount].
 */
class WorldStorageProvider(
    private val root: Path,
    private val perComputerCapacityBytes: Long,
) : StorageProvider {

    /**
     * One mount instance per computer ID, cached for the life of the provider.
     * Keeps [WorldFileMount.usedBytes] consistent across opens; CC:Tweaked
     * documents the same single-instance-per-folder requirement.
     */
    private val mounts = HashMap<Int, WritableMount>()

    @Synchronized
    override fun rootMountFor(computerId: Int): WritableMount {
        require(computerId >= 0) { "computerId must be non-negative, got $computerId" }
        return mounts.getOrPut(computerId) {
            WorldFileMount(root.resolve("computer").resolve(computerId.toString()), perComputerCapacityBytes)
        }
    }

    override fun romMount(): Mount = SHARED_ROM

    companion object {
        /** Default per-computer capacity until config lands. Matches CC:T's `computerSpaceLimit` default. */
        const val DEFAULT_CAPACITY_BYTES: Long = 2L * 1024L * 1024L  // 2 MiB

        /**
         * Shared across every computer — ROM contents don't change at runtime, so a single
         * classpath scan at mod load is sufficient. If the resource base is absent (tests,
         * stripped jar), [ResourceMount] still returns a valid empty-root mount.
         */
        private val SHARED_ROM: Mount = ResourceMount("assets/oc2/rom")
    }
}
