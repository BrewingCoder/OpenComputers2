package com.brewingcoder.oc2.block.parts

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag

/**
 * Optional MC-coupled persistence escape hatch for parts whose state can't fit
 * the narrow [com.brewingcoder.oc2.platform.parts.Part.NbtWriter] surface
 * (String/Int/Boolean only). Implementing parts get a chance to read/write
 * arbitrary [CompoundTag] data with the [HolderLookup.Provider] required for
 * registry-keyed payloads (ItemStacks, recipes, etc.).
 *
 * The Adapter BE calls [saveRawNbt] *after* the standard [saveNbt] and
 * [loadRawNbt] *after* the standard [loadNbt] — both subkeys live alongside
 * the platform-pure ones in the same per-part CompoundTag. Implementing this
 * is OPT-IN; parts that fit the platform-pure surface ignore it.
 *
 * Lives in `block/parts/` (MC-coupled) on purpose — keeps `platform/parts/`
 * import-free.
 */
interface PartWithRawNbt {
    /** Write whatever this part needs to the [tag]. Called after [com.brewingcoder.oc2.platform.parts.Part.saveNbt]. */
    fun saveRawNbt(tag: CompoundTag, registries: HolderLookup.Provider)

    /** Mirror of [saveRawNbt]. Called after [com.brewingcoder.oc2.platform.parts.Part.loadNbt]. */
    fun loadRawNbt(tag: CompoundTag, registries: HolderLookup.Provider)
}
