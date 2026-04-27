package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.script.ScriptEnv

/**
 * Pure-platform (Rule D) entry point for the script-side `recipes` global.
 * Wraps a [ScriptEnv] so callers can interrogate which attached machines
 * accept or emit a given item.
 *
 *   `recipes("minecraft:iron_ingot").producers` — bridges that emit it
 *   `recipes("minecraft:raw_iron").consumers`  — bridges that accept it
 *   `recipes("minecraft:iron_ingot").inputs`   — input ids that yield it
 *   `recipes("minecraft:raw_iron").outputs`    — output ids it yields
 *
 * Backed by `BridgePeripheral.call("canConsume" / "canProduce" /
 * "getInputsFor" / "getOutputFor", listOf(itemId))` — adapters that
 * implement those methods (Mek machines, NeoForge cap furnace) participate
 * automatically; everything else is silently filtered out.
 */
class RecipeApi(private val env: ScriptEnv) {
    /** Build a reusable query for [itemId]. Lazily fetches bridge data on first access. */
    fun query(itemId: String): RecipeQuery = RecipeQuery(itemId, env)

    /** Convenience: alias for [query] so call-sites can write `recipes("x")` from script glue. */
    operator fun invoke(itemId: String): RecipeQuery = query(itemId)
}

/**
 * Lazy snapshot of "what can this network do with [itemId]?". Each property
 * caches its answer the first time it's read — call `recipes(id)` again if
 * the network changed.
 */
class RecipeQuery internal constructor(
    val itemId: String,
    private val env: ScriptEnv,
) {
    private val bridges: List<BridgePeripheral> by lazy {
        env.listPeripherals("bridge").filterIsInstance<BridgePeripheral>()
    }

    /** Bridges that report `canProduce(itemId) == true`. */
    val producers: List<BridgePeripheral> by lazy { bridges.filter { askYes(it, "canProduce") } }

    /** Bridges that report `canConsume(itemId) == true`. */
    val consumers: List<BridgePeripheral> by lazy { bridges.filter { askYes(it, "canConsume") } }

    /**
     * Distinct input item ids that any [producers] machine accepts to make [itemId].
     * Aggregated from `getInputsFor(itemId)` across every producer.
     */
    val inputs: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        for (b in producers) {
            val recipes = b.callOrNull("getInputsFor") as? List<*> ?: continue
            for (r in recipes) {
                val m = r as? Map<*, *> ?: continue
                val ins = m["inputs"] as? List<*> ?: continue
                ins.forEach { id -> (id as? String)?.let(seen::add) }
            }
        }
        seen.toList()
    }

    /**
     * Distinct output item ids any [consumers] machine emits when fed [itemId].
     * Aggregated from `getOutputFor(itemId)` across every consumer.
     */
    val outputs: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        for (b in consumers) {
            val out = b.callOrNull("getOutputFor") as? Map<*, *> ?: continue
            (out["id"] as? String)?.let(seen::add)
        }
        seen.toList()
    }

    private fun askYes(b: BridgePeripheral, method: String): Boolean = try {
        b.call(method, listOf(itemId)) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    private fun BridgePeripheral.callOrNull(method: String): Any? = try {
        call(method, listOf(itemId))
    } catch (_: Throwable) {
        null
    }
}
