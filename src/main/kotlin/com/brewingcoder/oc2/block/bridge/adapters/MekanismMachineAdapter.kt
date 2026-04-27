package com.brewingcoder.oc2.block.bridge.adapters

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.bridge.ProtocolAdapter
import com.brewingcoder.oc2.block.parts.ItemHandlerHost
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import java.lang.reflect.Method

/**
 * Mekanism processing-machine adapter. Binds to the factory base class
 * `TileEntityFactory` (covers Smelter, Enrichment Chamber, Crusher, Sawmill,
 * Combiner, Oxidizer factories at all 5 tiers) and best-effort to the
 * single-slot machine bases (`TileEntityProgressMachine`,
 * `TileEntityRecipeMachine`).
 *
 * Reflective so we don't compile against Mekanism. Mekanism keeps its public
 * getters reasonably stable, but field/method names occasionally rename
 * across MC versions — when that happens, the adapter should keep working
 * for unaffected methods (each method is wrapped in try/catch returning null)
 * and `_introspect()` can be used in-game to discover the new shape.
 *
 * **Energy unit:** all energy methods return **Forge Energy (FE)** — the unit
 * Mekanism's GUI shows by default in modern versions. Mekanism's API returns
 * raw Joules; we multiply by [J_TO_FE] (0.4 — inverse of Mekanism's default
 * `general.ENERGY_CONVERSION_FROM_FE = 2.5`). For raw Joule values use the
 * `*J` variants. `_introspect()` shows the raw API output (Joules) to
 * preserve its use as a discovery tool.
 *
 * Surface (v0.1):
 *   getActive()                  Boolean — is the machine running
 *   getEnergy()                  Number  — stored FE
 *   getMaxEnergy()               Number  — storage cap (FE)
 *   getEnergyJ()                 Number  — raw stored Joules
 *   getMaxEnergyJ()              Number  — raw storage cap (Joules)
 *   getEnergyFilledPercentage()  Number  — derived 0..1 (unit-agnostic)
 *   getEnergyNeeded()            Number  — derived (cap - stored), FE, >= 0
 *   getEnergyNeededJ()           Number  — derived, raw Joules
 *   getProgress([slot])          Number  — 0..1 progress for process slot (default 0)
 *   getProcesses()               Number  — number of process slots (1 for non-factory)
 *   getMachineType()             String  — runtime class simpleName
 *   getMachineFqn()              String  — runtime class FQN
 *   getRecipeType()              String  — recipe type name (best-effort)
 *   _introspect()                Table   — debug: lists all no-arg public getters and their RAW values
 *
 * **Inventory surface (R2)** — same machine, same bridge, no second adapter:
 * the wrapped peripheral also implements [InventoryPeripheral] keyed off the
 * Mek machine's `IItemHandler` capability on `face`. Mek's side-config still
 * filters which slots accept items per face — pushing raw ore through a face
 * configured "input only" goes into input slots, etc.
 *
 * Inventory methods exposed via both `bridge.call("name", ...)` AND, when a
 * caller resolves the handle as an [InventoryPeripheral] (Lua-side passes it
 * to another inventory's `push`/`pull`), the standard interface methods:
 *
 *   size()              Number  — slot count of the machine inventory at `face`
 *   list()              Array   — per-slot snapshots ({id,count} or null)
 *   getItem(slot)       Table   — snapshot of one slot, 1-indexed
 *   find(itemId)        Number  — first slot containing itemId, 1-indexed; -1 if none
 *   destroy(slot,count) Number  — void up to count items from slot, returns voided count
 *   push(slot,target,count?,targetSlot?)   Number — move items OUT of machine to another inventory
 *   pull(source,slot,count?,targetSlot?)   Number — move items IN from another inventory
 */
object MekanismMachineAdapter : ProtocolAdapter {
    override val id: String = "mekanism-machine"

    /** Joules → FE. Inverse of Mekanism's default `ENERGY_CONVERSION_FROM_FE = 2.5`. */
    private const val J_TO_FE: Double = 0.4

    private const val FACTORY_FQN = "mekanism.common.tile.factory.TileEntityFactory"
    private const val PROGRESS_FQN = "mekanism.common.tile.prefab.TileEntityProgressMachine"
    private const val RECIPE_FQN = "mekanism.common.tile.prefab.TileEntityRecipeMachine"

    private val boundClasses: List<Class<*>> by lazy {
        listOfNotNull(
            tryLoad(FACTORY_FQN),
            tryLoad(PROGRESS_FQN),
            tryLoad(RECIPE_FQN),
        ).also {
            if (it.isEmpty()) {
                OpenComputers2.LOGGER.warn("MekanismMachineAdapter: no Mekanism machine base classes resolved — all candidate FQNs missing")
            }
        }
    }

    override fun canHandle(be: BlockEntity, face: Direction): Boolean =
        boundClasses.any { it.isInstance(be) }

    override fun wrap(be: BlockEntity, face: Direction, name: String, data: String, location: Position): BridgePeripheral? {
        if (!canHandle(be, face)) return null
        return MachinePeripheral(name, be, face, location, data)
    }

    private fun tryLoad(fqn: String): Class<*>? = try {
        Class.forName(fqn)
    } catch (t: Throwable) {
        OpenComputers2.LOGGER.debug("MekanismMachineAdapter: cannot resolve $fqn", t)
        null
    }

    private fun findNoArgMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == 0) {
                    return try { m.isAccessible = true; m } catch (_: Throwable) { m }
                }
            }
            c = c.superclass
        }
        return try {
            cls.getMethod(name).also { it.isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findIntArgMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == 1 &&
                    (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.javaObjectType)) {
                    return try { m.isAccessible = true; m } catch (_: Throwable) { m }
                }
            }
            c = c.superclass
        }
        return null
    }

    private class MachinePeripheral(
        override val name: String,
        private val be: BlockEntity,
        private val face: Direction,
        override val location: Position,
        override val data: String,
    ) : BridgePeripheral, InventoryPeripheral, ItemHandlerHost {
        override val protocol: String = "mekanism-machine"
        override val target: String = be.javaClass.name

        // Bridge wins the kind tiebreak (peripheral.find("bridge") is the entry
        // point); the inventory surface is layered on by the Lua/JS host when it
        // sees the peripheral implements both interfaces.
        override val kind: String get() = "bridge"

        private val noArgCache = HashMap<String, Method?>()
        private val intArgCache = HashMap<String, Method?>()

        override fun methods(): List<String> = listOf(
            // status
            "getActive",
            "getEnergy",
            "getMaxEnergy",
            "getEnergyJ",
            "getMaxEnergyJ",
            "getEnergyFilledPercentage",
            "getEnergyNeeded",
            "getEnergyNeededJ",
            "getProgress",
            "getProcesses",
            "getMachineType",
            "getMachineFqn",
            "getRecipeType",
            "_introspect",
            "_capProbe",
            "_extractProbe",
            // inventory (also exposed as the InventoryPeripheral interface)
            "size",
            "list",
            "getItem",
            "find",
            "destroy",
            "push",
            "pull",
            // recipe query (Phase 1: vanilla SMELTING only — covers Mek smelter + furnace)
            "getRecipes",
            "getOutputFor",
            "getInputsFor",
            "canConsume",
            "canProduce",
        )

        override fun call(method: String, args: List<Any?>): Any? {
            return when (method) {
                "getMachineType" -> be.javaClass.simpleName
                "getMachineFqn" -> be.javaClass.name
                "getActive" -> tryNoArg("getActive")
                    ?: tryNoArg("isActive")
                "getEnergy" -> (tryEnergy("getEnergy") as? Number)?.toDouble()?.times(J_TO_FE)
                "getMaxEnergy" -> (tryEnergy("getMaxEnergy") as? Number)?.toDouble()?.times(J_TO_FE)
                "getEnergyJ" -> tryEnergy("getEnergy")
                "getMaxEnergyJ" -> tryEnergy("getMaxEnergy")
                "getEnergyNeeded" -> {
                    val stored = (tryEnergy("getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (tryEnergy("getMaxEnergy") as? Number)?.toDouble() ?: return null
                    (cap - stored).coerceAtLeast(0.0) * J_TO_FE
                }
                "getEnergyNeededJ" -> {
                    val stored = (tryEnergy("getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (tryEnergy("getMaxEnergy") as? Number)?.toDouble() ?: return null
                    (cap - stored).coerceAtLeast(0.0)
                }
                "getEnergyFilledPercentage" -> {
                    val stored = (tryEnergy("getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (tryEnergy("getMaxEnergy") as? Number)?.toDouble() ?: return null
                    if (cap > 0.0) stored / cap else 0.0
                }
                "getProgress" -> {
                    val slot = (args.firstOrNull() as? Number)?.toInt() ?: 0
                    tryProgress(slot)
                }
                "getProcesses" -> tryNoArg("getProcesses")
                    ?: tryNoArg("getNumProcesses")
                    ?: 1
                "getRecipeType" -> {
                    val rt = tryNoArg("getRecipeType") ?: return null
                    // RecipeType is usually an enum or has a name() method; otherwise toString
                    tryNoArgOn(rt, "name") ?: tryNoArgOn(rt, "getRegistryName")?.toString() ?: rt.toString()
                }
                "_introspect" -> introspect()
                "_capProbe" -> capProbe()
                "_extractProbe" -> extractProbe()
                // ---- inventory ops (delegate to InventoryPeripheral impl) ----
                "size" -> size()
                "list" -> list()
                "getItem" -> {
                    val slot = (args.firstOrNull() as? Number)?.toInt() ?: return null
                    getItem(slot)?.let { mapOf("id" to it.id, "count" to it.count) }
                }
                "find" -> {
                    val id = args.firstOrNull() as? String ?: return -1
                    find(id)
                }
                "destroy" -> {
                    val slot = (args.getOrNull(0) as? Number)?.toInt() ?: return 0
                    val count = (args.getOrNull(1) as? Number)?.toInt() ?: Int.MAX_VALUE
                    destroy(slot, count)
                }
                "push" -> {
                    val slot = (args.getOrNull(0) as? Number)?.toInt() ?: return 0
                    val target = args.getOrNull(1) as? InventoryPeripheral ?: return 0
                    val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
                    val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
                    push(slot, target, count, targetSlot)
                }
                "pull" -> {
                    val source = args.getOrNull(0) as? InventoryPeripheral ?: return 0
                    val slot = (args.getOrNull(1) as? Number)?.toInt() ?: return 0
                    val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
                    val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
                    pull(source, slot, count, targetSlot)
                }
                "getRecipes" -> getRecipesList()
                "getOutputFor" -> {
                    val id = args.firstOrNull() as? String ?: return null
                    getOutputFor(id)
                }
                "getInputsFor" -> {
                    val id = args.firstOrNull() as? String ?: return emptyList<Any>()
                    getInputsFor(id)
                }
                "canConsume" -> {
                    val id = args.firstOrNull() as? String ?: return false
                    getOutputFor(id) != null
                }
                "canProduce" -> {
                    val id = args.firstOrNull() as? String ?: return false
                    getInputsFor(id).isNotEmpty()
                }
                else -> null
            }
        }

        // ---- ItemHandlerHost / InventoryPeripheral impl ----

        /**
         * Lookup the machine's [IItemHandler] cap.
         *
         * Two surprises we work around here:
         *
         *  1. **5-arg form is required.** The 3-arg `lvl.getCapability(cap, pos, side)`
         *     returns null for Mek factories on every side, even though the cap is
         *     registered. The 5-arg form `(cap, pos, state, blockEntity, side)`
         *     dispatches correctly. Tested against Mekanism 10.7 / NeoForge 21.1.220.
         *     Discovered via `_capProbe()`. The chest's IItemHandler resolves through
         *     the 3-arg form; Mek factories don't.
         *
         *  2. **Prefer the side-keyed handler.** Counter-intuitively, Mek's *unsided*
         *     handler (side=null) exposes all 19 slots (1 energy + 9 inputs + 9
         *     outputs interleaved) but **refuses `extractItem` on output slots** —
         *     `simulate=true` returns empty for every output slot, so push/pull/drain
         *     no-op. The side-keyed handler for `face.opposite` (the side the machine
         *     sees the adapter from) groups outputs at the END of the slot list and
         *     **does** permit extract, provided the user has configured that face
         *     for output (or mixed I/O) in Mek's side-config GUI. We use that handler
         *     as the primary, falling back to unsided only if the side-keyed lookup
         *     returns null. Discovered via `_extractProbe()`.
         *
         * Re-resolved every call: Mek can swap the handler when the side config
         * changes (e.g. user toggles a face from input-only to output-only
         * mid-script). Cap lookup is cheap — Mek caches internally.
         */
        override val itemHandler: IItemHandler?
            get() {
                val lvl = be.level as? ServerLevel ?: return null
                val state = be.blockState
                // Prefer the side-keyed handler for `face.opposite` (the side the
                // machine "sees" the adapter from). Mek's side-keyed handler respects
                // per-side rules: output slots are extractable, input slots accept
                // inserts. The unsided (null) handler exposes all 19 slots but
                // refuses extractItem on output slots (returns empty stack), which
                // breaks drain — so we deliberately use the side-keyed view.
                return try {
                    lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, face.opposite)
                        ?: lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, null)
                } catch (t: Throwable) {
                    OpenComputers2.LOGGER.debug("MekanismMachineAdapter: cap lookup failed for {}", be.javaClass.simpleName, t)
                    null
                }
            }

        override fun size(): Int = itemHandler?.slots ?: 0

        override fun getItem(slot: Int): ItemSnapshot? {
            val h = itemHandler ?: return null
            val idx = slot - 1
            if (idx !in 0 until h.slots) return null
            return snapshot(h.getStackInSlot(idx))
        }

        override fun list(): List<ItemSnapshot?> {
            val h = itemHandler ?: return emptyList()
            return (0 until h.slots).map { snapshot(h.getStackInSlot(it)) }
        }

        override fun find(itemId: String): Int {
            val h = itemHandler ?: return -1
            for (i in 0 until h.slots) {
                val s = h.getStackInSlot(i)
                if (s.isEmpty) continue
                if (idFor(s) == itemId) return i + 1
            }
            return -1
        }

        override fun destroy(slot: Int, count: Int): Int {
            val h = itemHandler ?: return 0
            val idx = slot - 1
            if (idx !in 0 until h.slots) return 0
            return h.extractItem(idx, count.coerceAtLeast(0), /* simulate = */ false).count
        }

        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int {
            val srcH = itemHandler ?: return 0
            val src = slot - 1
            if (src !in 0 until srcH.slots) return 0
            val toMove = srcH.extractItem(src, count.coerceAtLeast(0), /* simulate = */ true)
            if (toMove.isEmpty) return 0
            val targetH = (target as? ItemHandlerHost)?.itemHandler ?: return 0
            val remainder = if (targetSlot != null) tryInsertAt(targetH, targetSlot - 1, toMove)
                            else tryInsertAny(targetH, toMove)
            val moved = toMove.count - remainder.count
            if (moved > 0) srcH.extractItem(src, moved, /* simulate = */ false)
            return moved
        }

        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int {
            val dstH = itemHandler ?: return 0
            val srcH = (source as? ItemHandlerHost)?.itemHandler ?: return 0
            val src = slot - 1
            if (src !in 0 until srcH.slots) return 0
            val toMove = srcH.extractItem(src, count.coerceAtLeast(0), /* simulate = */ true)
            if (toMove.isEmpty) return 0
            val remainder = if (targetSlot != null) tryInsertAt(dstH, targetSlot - 1, toMove)
                            else tryInsertAny(dstH, toMove)
            val moved = toMove.count - remainder.count
            if (moved > 0) srcH.extractItem(src, moved, /* simulate = */ false)
            return moved
        }

        private fun tryInsertAt(h: IItemHandler, idx: Int, stack: ItemStack): ItemStack {
            if (idx !in 0 until h.slots) return stack
            return h.insertItem(idx, stack, /* simulate = */ false)
        }

        private fun tryInsertAny(h: IItemHandler, stack: ItemStack): ItemStack {
            var remainder = stack
            for (i in 0 until h.slots) {
                if (remainder.isEmpty) break
                remainder = h.insertItem(i, remainder, /* simulate = */ false)
            }
            return remainder
        }

        private fun snapshot(stack: ItemStack): ItemSnapshot? {
            if (stack.isEmpty) return null
            return ItemSnapshot(idFor(stack), stack.count)
        }

        private fun idFor(stack: ItemStack): String =
            BuiltInRegistries.ITEM.getKey(stack.item).toString()

        // ---- Recipe query (Phase 1: vanilla SMELTING only) ----

        /**
         * True if this machine consumes vanilla `RecipeType.SMELTING` recipes — Mek
         * smelter factories at all 5 tiers + the Energized Smelter both inherit
         * vanilla smelting via Mek's recipe-type bridge. Detected by the
         * machine's runtime class name (cheaper than reflective recipe-type peek).
         */
        private fun isVanillaSmeltingCompatible(): Boolean {
            val fqn = be.javaClass.name
            // Factory variants: TileEntitySmeltingFactory or factoryType=SMELTING
            if (fqn.contains("Smelter", ignoreCase = true)) return true
            if (fqn.contains("Smelting", ignoreCase = true)) return true
            // Mek factory base + factoryType enum check
            val ft = tryNoArg("getFactoryType")?.let { tryNoArgOn(it, "name") }?.toString()
            return ft.equals("SMELTING", ignoreCase = true)
        }

        private fun smeltingRecipes(): List<net.minecraft.world.item.crafting.RecipeHolder<*>> {
            if (!isVanillaSmeltingCompatible()) return emptyList()
            val server = (be.level as? ServerLevel)?.server ?: return emptyList()
            return try {
                @Suppress("UNCHECKED_CAST")
                server.recipeManager.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING)
                    as List<net.minecraft.world.item.crafting.RecipeHolder<*>>
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("MekanismMachineAdapter: recipe lookup failed", t)
                emptyList()
            }
        }

        private fun resultStackOf(holder: net.minecraft.world.item.crafting.RecipeHolder<*>): ItemStack {
            val server = (be.level as? ServerLevel)?.server ?: return ItemStack.EMPTY
            @Suppress("UNCHECKED_CAST")
            val recipe = holder.value as net.minecraft.world.item.crafting.Recipe<Any>
            return try { recipe.getResultItem(server.registryAccess()) } catch (_: Throwable) { ItemStack.EMPTY }
        }

        private fun ingredientItemIdsOf(holder: net.minecraft.world.item.crafting.RecipeHolder<*>): List<String> {
            val recipe = holder.value
            val ing = try { recipe.ingredients.firstOrNull() } catch (_: Throwable) { null } ?: return emptyList()
            return try {
                ing.items.map { idFor(it) }.distinct()
            } catch (_: Throwable) { emptyList() }
        }

        /**
         * List every recipe this machine can run, as `[{inputs:[id,...], output:{id,count}}, ...]`.
         * `inputs` may contain multiple item ids when the recipe uses an item tag
         * (e.g. `#c:ores/copper` resolves to deepslate_copper_ore + copper_ore).
         */
        private fun getRecipesList(): List<Map<String, Any?>> {
            val out = mutableListOf<Map<String, Any?>>()
            for (h in smeltingRecipes()) {
                val output = resultStackOf(h)
                if (output.isEmpty) continue
                val inputs = ingredientItemIdsOf(h)
                if (inputs.isEmpty()) continue
                out.add(mapOf(
                    "inputs" to inputs,
                    "output" to mapOf("id" to idFor(output), "count" to output.count),
                ))
            }
            return out
        }

        /** Predict the output stack `{id, count}` for a single input id, or null if not consumable. */
        private fun getOutputFor(inputId: String): Map<String, Any?>? {
            for (h in smeltingRecipes()) {
                if (inputId !in ingredientItemIdsOf(h)) continue
                val output = resultStackOf(h)
                if (output.isEmpty) continue
                return mapOf("id" to idFor(output), "count" to output.count)
            }
            return null
        }

        /**
         * List every recipe whose output matches `outputId`, as `[{inputs:[id,...], count:N}, ...]`.
         * Caller picks which input form they have on hand. Empty if the machine
         * cannot produce `outputId`.
         */
        private fun getInputsFor(outputId: String): List<Map<String, Any?>> {
            val out = mutableListOf<Map<String, Any?>>()
            for (h in smeltingRecipes()) {
                val output = resultStackOf(h)
                if (output.isEmpty || idFor(output) != outputId) continue
                val inputs = ingredientItemIdsOf(h)
                if (inputs.isEmpty()) continue
                out.add(mapOf("inputs" to inputs, "count" to output.count))
            }
            return out
        }

        /**
         * Mekanism's energy is usually wrapped in a `MachineEnergyContainer`. Direct
         * `getEnergy` / `getMaxEnergy` may not exist on the BE — try walking through
         * `getEnergyContainer()` first, then fall back to direct.
         */
        private fun tryEnergy(getter: String): Any? {
            // Direct getter on BE (some Mekanism machines do expose these)
            tryNoArg(getter)?.let { return normalize(it) }
            // Via energy container
            val container = tryNoArg("getEnergyContainer") ?: return null
            // The container exposes getEnergy / getMaxEnergy directly
            val v = tryNoArgOn(container, getter)
            return normalize(v)
        }

        private fun tryProgress(slot: Int): Any? {
            // Factory progress: int-arg method per slot
            val intM = intArgCache.getOrPut("getProgress") { findIntArgMethod(be.javaClass, "getProgress") }
            if (intM != null) {
                return try {
                    val v = intM.invoke(be, slot)
                    normalize(v)
                } catch (t: Throwable) {
                    OpenComputers2.LOGGER.debug("MekanismMachineAdapter: getProgress($slot) failed", t)
                    null
                }
            }
            // Non-factory: no-arg getProgress / getOperatingTicks
            tryNoArg("getProgress")?.let { return normalize(it) }
            // operatingTicks / ticksRequired pattern — derive 0..1
            val ticks = (tryNoArg("getOperatingTicks") as? Number)?.toDouble()
            val required = (tryNoArg("getTicksRequired") as? Number)?.toDouble()
            if (ticks != null && required != null && required > 0.0) {
                return ticks / required
            }
            return null
        }

        /**
         * Diagnostic: query the IItemHandler cap on every side + null and report
         * `<side> -> <slots> | null`. Use when a machine that should expose an
         * inventory returns size=0 — tells you exactly which side(s) Mek's
         * side-config has enabled.
         */
        private fun capProbe(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            out["machineFace"] = face.serializedName
            out["machinePos"] = "${be.blockPos.x},${be.blockPos.y},${be.blockPos.z}"
            val lvl = be.level as? ServerLevel
            out["levelKind"] = lvl?.javaClass?.simpleName ?: "<not ServerLevel>"
            out["beClass"] = be.javaClass.name
            if (lvl == null) return out
            val sides = listOf<Direction?>(null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
            // 3-arg form (cap, pos, side) — what we use today.
            for (side in sides) {
                val key = "3arg." + (side?.serializedName ?: "null")
                out[key] = try {
                    val h = lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, side)
                    if (h == null) "null" else "slots=${h.slots}"
                } catch (t: Throwable) { "threw:${t.javaClass.simpleName}:${t.message}" }
            }
            // 5-arg form (cap, pos, state, BE, side) — explicit BE + state, in case
            // 3-arg path skips BE-cap resolution in this NeoForge build.
            val state = be.blockState
            for (side in sides) {
                val key = "5arg." + (side?.serializedName ?: "null")
                out[key] = try {
                    val h = lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, side)
                    if (h == null) "null" else "slots=${h.slots}"
                } catch (t: Throwable) { "threw:${t.javaClass.simpleName}:${t.message}" }
            }
            return out
        }

        /**
         * Diagnostic: for each side (incl. null), simulate extractItem on every
         * non-empty slot of that side's handler and report which extracts succeed.
         * Helps determine which side(s) Mek permits extraction through.
         */
        private fun extractProbe(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            val lvl = be.level as? ServerLevel ?: run { out["error"] = "no ServerLevel"; return out }
            val state = be.blockState
            val sides = listOf<Direction?>(null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
            for (side in sides) {
                val sideName = side?.serializedName ?: "null"
                val h = try {
                    lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, side)
                } catch (t: Throwable) { null }
                if (h == null) {
                    out[sideName] = "null"
                    continue
                }
                val rows = mutableListOf<String>()
                for (i in 0 until h.slots) {
                    val s = h.getStackInSlot(i)
                    if (s.isEmpty) continue
                    val sim = try { h.extractItem(i, 64, true) } catch (t: Throwable) { null }
                    val extractable = sim?.count ?: -1
                    rows.add("slot=${i + 1} item=${idFor(s)} have=${s.count} canExtract=$extractable")
                }
                out[sideName] = if (rows.isEmpty()) "slots=${h.slots} all-empty" else "slots=${h.slots} | " + rows.joinToString(" ; ")
            }
            return out
        }

        /** Reflectively dump all no-arg public getters and their current values. */
        private fun introspect(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            out["class"] = be.javaClass.name
            out["simpleName"] = be.javaClass.simpleName
            val seen = HashSet<String>()
            var c: Class<*>? = be.javaClass
            while (c != null && c.name.startsWith("mekanism.")) {
                for (m in c.declaredMethods) {
                    if (m.parameterCount != 0) continue
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    val name = m.name
                    if (!seen.add(name)) continue
                    if (!(name.startsWith("get") || name.startsWith("is")) || name == "getClass") continue
                    if (name.endsWith("Listener") || name.endsWith("Listeners")) continue
                    try {
                        m.isAccessible = true
                        val v = m.invoke(be)
                        out[name] = describeValue(v)
                    } catch (_: Throwable) {
                        out[name] = "<threw>"
                    }
                }
                c = c.superclass
            }
            return out
        }

        private fun describeValue(v: Any?): Any? = when (v) {
            null -> null
            is Number, is Boolean, is String -> v
            is Enum<*> -> v.name
            is Collection<*> -> "<collection size=${v.size}>"
            is Map<*, *> -> "<map size=${v.size}>"
            else -> "<${v.javaClass.simpleName}>"
        }

        private fun tryNoArg(methodName: String): Any? {
            val m = noArgCache.getOrPut("${be.javaClass.name}#$methodName") {
                findNoArgMethod(be.javaClass, methodName)
            } ?: return null
            return try {
                m.invoke(be)
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("MekanismMachineAdapter: {}#{} failed", be.javaClass.simpleName, methodName, t)
                null
            }
        }

        /** Like [tryNoArg] but on an arbitrary receiver (used to walk `getEnergyContainer()` etc.). */
        private fun tryNoArgOn(receiver: Any, methodName: String): Any? {
            val m = findNoArgMethod(receiver.javaClass, methodName) ?: return null
            return try {
                m.invoke(receiver)
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("MekanismMachineAdapter: {}#{} failed (receiver)", receiver.javaClass.simpleName, methodName, t)
                null
            }
        }

        private fun normalize(value: Any?): Any? = when (value) {
            null -> null
            is Long -> value.toDouble()
            is Int, is Short, is Byte -> (value as Number).toDouble()
            is Float, is Double -> value
            is Boolean -> value
            is String -> value
            is Enum<*> -> value.name
            else -> value.toString()
        }
    }
}
