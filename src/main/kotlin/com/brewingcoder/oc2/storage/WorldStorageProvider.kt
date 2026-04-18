package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.Mount
import com.brewingcoder.oc2.platform.storage.StorageProvider
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.nio.file.Path

/**
 * Resolves per-computer storage to `<root>/computer/<id>/` on disk. [root] is
 * `<world>/oc2/` in production (set up by [OC2ServerContext]) or a tmp dir in
 * tests.
 *
 * ROM mount is currently an empty in-memory placeholder. We'll swap it for a
 * jar-resource-backed mount when actual ROM contents land (boot scripts,
 * default Lua programs).
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

    override fun romMount(): Mount = EMPTY_ROM

    companion object {
        /** Default per-computer capacity until config lands. Matches CC:T's `computerSpaceLimit` default. */
        const val DEFAULT_CAPACITY_BYTES: Long = 2L * 1024L * 1024L  // 2 MiB

        // Placeholder ROM until we ship boot scripts. Swap for a jar-backed Mount when ready.
        private val EMPTY_ROM: Mount = InMemoryMount()
    }
}
