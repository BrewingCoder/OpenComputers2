package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.CrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.MachineCrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.EnergyPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
import com.brewingcoder.oc2.platform.peripheral.RedstonePeripheral
import com.brewingcoder.oc2.platform.recipes.InventoryApi
import com.brewingcoder.oc2.platform.recipes.RecipeApi
import com.brewingcoder.oc2.platform.recipes.RecipeCrafter
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function as RhinoFunction
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mozilla Rhino-backed JavaScript host (ES5 + many ES6 features). Mirrors
 * [CobaltLuaHost] behaviorally — same [print] capture, same `fs` API surface
 * via [ScriptFsOps] — so users can switch languages without learning a different
 * standard library.
 *
 * What's installed:
 *   - Standard JS globals (Math, String, JSON, Array, etc.) via Rhino's default scope
 *   - `print(...)` that pushes into the supplied [ShellOutput] (newline-joined args)
 *   - A `fs` global object with the same methods as the Lua binding
 *
 * Rhino interpreter mode: we use `setOptimizationLevel(-1)` (interpreted mode)
 * to avoid bytecode generation. This is slower than the JIT path but:
 *   - Works inside MC's transforming classloader without permission games
 *   - Avoids per-mod-jar bytecode classes leaking into NeoForge's class space
 *   - Eliminates a whole category of "runs in dev, breaks in production" bugs
 *
 * Performance is a non-issue at v0 — scripts run for milliseconds.
 *
 * v0 simplifications same as Lua: no cooperative yielding, no file handles,
 * no module loader (no `require`), no async / Promise integration.
 */
class RhinoJSHost : ScriptHost {

    override fun eval(source: String, chunkName: String, env: ScriptEnv, scriptArgs: List<String>): ScriptResult {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1  // interpreter only — see class doc
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "print", makePrintFunction(env.out, scope))
            ScriptableObject.putProperty(scope, "fs", makeFsObject(env.mount, env.cwd, scope))
            ScriptableObject.putProperty(scope, "peripheral", makePeripheralObject(env, scope))
            ScriptableObject.putProperty(scope, "inventory", makeInventoryObject(env, scope))
            ScriptableObject.putProperty(scope, "recipes", makeRecipesFunction(env, scope))
            ScriptableObject.putProperty(scope, "colors", makeColorsObject())
            ScriptableObject.putProperty(scope, "network", makeNetworkObject(env, scope))
            // Forward CLI args as the global `args` array — `args[0]`, `args[1]`, ...
            // Always defined (empty array if no args) so `args.length` is safe.
            run {
                val arr = cx.newArray(scope, scriptArgs.size)
                for ((i, s) in scriptArgs.withIndex()) arr.put(i, arr, s)
                ScriptableObject.putProperty(scope, "args", arr)
            }
            ScriptableObject.putProperty(scope, "sleep", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                override fun call(cx2: Context, scope2: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val secs = (args.getOrNull(0) as? Number)?.toDouble() ?: 0.0
                    val ms = (secs * 1000).toLong().coerceIn(0L, 60_000L)
                    if (ms > 0) Thread.sleep(ms)
                    return Undefined.instance
                }
            })
            installRequire(scope, env)
            installUiRunHost(scope, env)
            cx.evaluateString(scope, source, chunkName, 1, null)
            ScriptResult(ok = true, errorMessage = null)
        } catch (e: JavaScriptException) {
            ScriptResult(ok = false, errorMessage = cleanError(e.message))
        } catch (e: RhinoException) {
            ScriptResult(ok = false, errorMessage = cleanError(e.message))
        } catch (e: StorageException) {
            ScriptResult(ok = false, errorMessage = "fs error: ${e.message}")
        } catch (e: PeripheralLease.PeripheralLockException) {
            ScriptResult(ok = false, errorMessage = "js error: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    private fun cleanError(raw: String?): String {
        val msg = raw ?: "unknown"
        val cleaned = msg.replace(Regex("""java\.lang\.\w+(?:\.\w+)*:\s*"""), "")
        return "js error: $cleaned"
    }

    private fun makeColorsObject(): ScriptableObject {
        val obj = NativeObject()
        for ((name, value) in ScriptColors.PALETTE) {
            ScriptableObject.putProperty(obj, name, value.toLong() and 0xFFFFFFFFL)
        }
        return obj
    }

    /**
     * Build the `peripheral` JS object:
     *   `peripheral.find(kind)` → handle or null
     *   `peripheral.list([kind])` → array of handles
     */
    private fun makePeripheralObject(env: ScriptEnv, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        defineFsMethod(obj, "find", parent, 1) { args ->
            val kind = asString(args, 0)
            val name = if (args.size >= 2 && args[1] != null && args[1] !== Undefined.instance) asString(args, 1) else null
            val p = if (name != null) {
                env.listPeripherals(kind).firstOrNull { it.name == name }
            } else {
                env.findPeripheral(kind)
            } ?: return@defineFsMethod null
            wrapAnyPeripheral(p, parent)
        }
        defineFsMethod(obj, "list", parent, 1) { args ->
            val kind = if (args.isNotEmpty() && args[0] != null && args[0] !== Undefined.instance) asString(args, 0) else null
            val matches = env.listPeripherals(kind)
            val arr = cx().newArray(parent, matches.size)
            for ((i, p) in matches.withIndex()) {
                val wrapped = wrapAnyPeripheral(p, parent) ?: continue
                arr.put(i, arr, wrapped)
            }
            arr
        }
        return obj
    }

    private fun wrapAnyPeripheral(p: com.brewingcoder.oc2.platform.peripheral.Peripheral, parent: Scriptable): Any? {
        val obj = when (p) {
            is MonitorPeripheral -> wrapMonitor(p, parent)
            is InventoryPeripheral -> wrapInventory(p, parent)
            is CrafterPeripheral -> wrapCrafter(p, parent)
            is MachineCrafterPeripheral -> wrapMachineCrafter(p, parent)
            is RedstonePeripheral -> wrapRedstone(p, parent)
            is FluidPeripheral -> wrapFluid(p, parent)
            is EnergyPeripheral -> wrapEnergy(p, parent)
            is BlockPeripheral -> wrapBlock(p, parent)
            is BridgePeripheral -> wrapBridge(p, parent)
            else -> return null
        }
        // Stamp getLocation() onto every peripheral object — returns {x, y, z}.
        defineFsMethod(obj, "getLocation", parent, 0) { _ ->
            val loc = p.location
            val result = NativeObject()
            ScriptableObject.putProperty(result, "x", loc.x)
            ScriptableObject.putProperty(result, "y", loc.y)
            ScriptableObject.putProperty(result, "z", loc.z)
            result
        }
        // Stamp `data` — the free-form user text from the part config GUI.
        // Scripts read it as `peripheral.data`. Snapshot at wrap time; scripts
        // re-fetch via `peripheral.find()` to pick up live edits.
        ScriptableObject.putProperty(obj, "data", p.data)
        return obj
    }

    /** JS counterpart to [CobaltLuaHost.wrapBridge]. */
    private fun wrapBridge(b: BridgePeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", b.kind)
        ScriptableObject.putProperty(obj, "name", b.name)
        ScriptableObject.putProperty(obj, "protocol", b.protocol)
        ScriptableObject.putProperty(obj, "target", b.target)
        defineFsMethod(obj, "methods", parent, 0) { _ ->
            val list = b.methods()
            val arr = cx().newArray(parent, list.size)
            for ((i, m) in list.withIndex()) arr.put(i, arr, m)
            arr
        }
        defineFsMethod(obj, "call", parent, 2) { args ->
            val name = args.getOrNull(0) as? String ?: return@defineFsMethod null
            val callArgs = args.drop(1).map { jsToJava(it) }
            javaToJs(b.call(name, callArgs), parent)
        }
        defineFsMethod(obj, "describe", parent, 0) { _ -> javaToJs(b.describe(), parent) }
        return obj
    }

    /** JS → Java boxing for bridge call args. */
    private fun jsToJava(v: Any?): Any? = when (v) {
        null, Undefined.instance -> null
        is Number, is Boolean, is String -> v
        is NativeArray -> (0 until v.length).map { jsToJava(v.get(it.toInt(), v)) }
        else -> v.toString()
    }

    /** Java → JS marshalling for bridge call returns (mirrors [CobaltLuaHost.toLuaValue]). */
    private fun javaToJs(any: Any?, parent: Scriptable): Any? = when (any) {
        null -> null
        is Boolean, is Int, is Long, is Float, is Double, is String -> any
        is List<*> -> {
            val arr = cx().newArray(parent, any.size)
            for ((i, e) in any.withIndex()) arr.put(i, arr, javaToJs(e, parent))
            arr
        }
        is Map<*, *> -> {
            val o = NativeObject()
            for ((k, v) in any) ScriptableObject.putProperty(o, k.toString(), javaToJs(v, parent))
            o
        }
        else -> any.toString()
    }

    private fun wrapBlock(b: BlockPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", b.kind)
        ScriptableObject.putProperty(obj, "name", b.name)
        defineFsMethod(obj, "read", parent, 0) { _ ->
            val r = b.read() ?: return@defineFsMethod null
            val o = NativeObject()
            ScriptableObject.putProperty(o, "id", r.id)
            ScriptableObject.putProperty(o, "isAir", r.isAir)
            ScriptableObject.putProperty(o, "lightLevel", r.lightLevel)
            ScriptableObject.putProperty(o, "redstonePower", r.redstonePower)
            ScriptableObject.putProperty(o, "hardness", r.hardness.toDouble())
            val pos = NativeObject()
            ScriptableObject.putProperty(pos, "x", r.pos.first)
            ScriptableObject.putProperty(pos, "y", r.pos.second)
            ScriptableObject.putProperty(pos, "z", r.pos.third)
            ScriptableObject.putProperty(o, "pos", pos)
            if (r.nbt != null) ScriptableObject.putProperty(o, "nbt", r.nbt)
            o
        }
        defineFsMethod(obj, "harvest", parent, 1) { args ->
            val targetObj = args.getOrNull(0) as? ScriptableObject
            val target = targetObj?.let { invHandles[it] }
            val moved = b.harvest(target)
            val arr = cx().newArray(parent, moved.size)
            for ((i, snap) in moved.withIndex()) {
                arr.put(i, arr, itemSnapshotToJs(snap))
            }
            arr
        }
        return obj
    }

    private fun wrapFluid(fl: FluidPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", fl.kind)
        ScriptableObject.putProperty(obj, "name", fl.name)
        fluidHandles[obj] = fl
        defineFsMethod(obj, "tanks", parent, 0) { _ -> fl.tanks() }
        defineFsMethod(obj, "getFluid", parent, 1) { args ->
            val s = fl.getFluid((args.getOrNull(0) as? Number)?.toInt() ?: 0) ?: return@defineFsMethod null
            fluidSnapshotToJs(s)
        }
        defineFsMethod(obj, "list", parent, 0) { _ ->
            val list = fl.list()
            val arr = cx().newArray(parent, list.size)
            for ((i, s) in list.withIndex()) arr.put(i, arr, s?.let { fluidSnapshotToJs(it) })
            arr
        }
        defineFsMethod(obj, "push", parent, 2) { args ->
            val tgt = (args.getOrNull(0) as? ScriptableObject)?.let { fluidHandles[it] } ?: return@defineFsMethod 0
            val amount = (args.getOrNull(1) as? Number)?.toInt() ?: 1000
            fl.push(tgt, amount)
        }
        defineFsMethod(obj, "pull", parent, 2) { args ->
            val src = (args.getOrNull(0) as? ScriptableObject)?.let { fluidHandles[it] } ?: return@defineFsMethod 0
            val amount = (args.getOrNull(1) as? Number)?.toInt() ?: 1000
            fl.pull(src, amount)
        }
        defineFsMethod(obj, "destroy", parent, 1) { args ->
            val amount = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            fl.destroy(amount)
        }
        return obj
    }

    private fun fluidSnapshotToJs(s: FluidPeripheral.FluidSnapshot): ScriptableObject {
        val o = NativeObject()
        ScriptableObject.putProperty(o, "id", s.id)
        ScriptableObject.putProperty(o, "amount", s.amount)
        return o
    }

    private val fluidHandles: java.util.WeakHashMap<ScriptableObject, FluidPeripheral> = java.util.WeakHashMap()

    private fun wrapEnergy(en: EnergyPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", en.kind)
        ScriptableObject.putProperty(obj, "name", en.name)
        energyHandles[obj] = en
        defineFsMethod(obj, "stored", parent, 0) { _ -> en.stored() }
        defineFsMethod(obj, "capacity", parent, 0) { _ -> en.capacity() }
        defineFsMethod(obj, "push", parent, 2) { args ->
            val tgt = (args.getOrNull(0) as? ScriptableObject)?.let { energyHandles[it] } ?: return@defineFsMethod 0
            val amount = (args.getOrNull(1) as? Number)?.toInt() ?: Int.MAX_VALUE
            en.push(tgt, amount)
        }
        defineFsMethod(obj, "pull", parent, 2) { args ->
            val src = (args.getOrNull(0) as? ScriptableObject)?.let { energyHandles[it] } ?: return@defineFsMethod 0
            val amount = (args.getOrNull(1) as? Number)?.toInt() ?: Int.MAX_VALUE
            en.pull(src, amount)
        }
        defineFsMethod(obj, "destroy", parent, 1) { args ->
            val amount = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            en.destroy(amount)
        }
        return obj
    }

    private val energyHandles: java.util.WeakHashMap<ScriptableObject, EnergyPeripheral> = java.util.WeakHashMap()

    private fun wrapRedstone(rs: RedstonePeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", rs.kind)
        ScriptableObject.putProperty(obj, "name", rs.name)
        defineFsMethod(obj, "getInput", parent, 0) { _ -> rs.getInput() }
        defineFsMethod(obj, "getOutput", parent, 0) { _ -> rs.getOutput() }
        defineFsMethod(obj, "setOutput", parent, 1) { args ->
            rs.setOutput((args.getOrNull(0) as? Number)?.toInt() ?: 0); Undefined.instance
        }
        return obj
    }

    /** JS counterpart to [CobaltLuaHost.wrapInventory]. 1-indexed slots, same surface. */
    private fun wrapInventory(inv: InventoryPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", inv.kind)
        ScriptableObject.putProperty(obj, "name", inv.name)
        // Stash the native handle so push/pull can recover the underlying inventory.
        invHandles[obj] = inv
        defineFsMethod(obj, "size", parent, 0) { _ -> inv.size() }
        defineFsMethod(obj, "getItem", parent, 1) { args ->
            val s = inv.getItem((args.getOrNull(0) as? Number)?.toInt() ?: 0) ?: return@defineFsMethod null
            itemSnapshotToJs(s)
        }
        defineFsMethod(obj, "list", parent, 0) { _ ->
            val list = inv.list()
            val arr = cx().newArray(parent, list.size)
            for ((i, s) in list.withIndex()) {
                arr.put(i, arr, s?.let { itemSnapshotToJs(it) })
            }
            arr
        }
        defineFsMethod(obj, "find", parent, 1) { args -> inv.find(asString(args, 0)) }
        defineFsMethod(obj, "destroy", parent, 2) { args ->
            val slot = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: Int.MAX_VALUE
            inv.destroy(slot, count)
        }
        defineFsMethod(obj, "push", parent, 4) { args ->
            val slot = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val targetObj = args.getOrNull(1) as? ScriptableObject
            val target = targetObj?.let { invHandles[it] } ?: return@defineFsMethod 0
            val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
            val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
            inv.push(slot, target, count, targetSlot)
        }
        defineFsMethod(obj, "pull", parent, 4) { args ->
            val sourceObj = args.getOrNull(0) as? ScriptableObject
            val source = sourceObj?.let { invHandles[it] } ?: return@defineFsMethod 0
            val slot = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
            val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
            inv.pull(source, slot, count, targetSlot)
        }
        return obj
    }

    private fun itemSnapshotToJs(s: InventoryPeripheral.ItemSnapshot): ScriptableObject {
        val o = NativeObject()
        ScriptableObject.putProperty(o, "id", s.id)
        ScriptableObject.putProperty(o, "count", s.count)
        return o
    }

    /**
     * JS counterpart to [CobaltLuaHost.wrapCrafter]. Same surface — size/list/craft.
     * The `source` argument to craft() must be an inventory peripheral object
     * produced by `peripheral.find` / `inventory.list`; resolved via [invHandles].
     */
    private fun wrapCrafter(c: CrafterPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", c.kind)
        ScriptableObject.putProperty(obj, "name", c.name)
        defineFsMethod(obj, "size", parent, 0) { _ -> c.size() }
        defineFsMethod(obj, "list", parent, 0) { _ ->
            val list = c.list()
            val arr = cx().newArray(parent, list.size)
            for ((i, snap) in list.withIndex()) {
                if (snap == null) {
                    arr.put(i, arr, null)
                } else {
                    val o = NativeObject()
                    ScriptableObject.putProperty(o, "slot", snap.slot)
                    ScriptableObject.putProperty(o, "output", snap.output)
                    ScriptableObject.putProperty(o, "outputCount", snap.outputCount)
                    arr.put(i, arr, o)
                }
            }
            arr
        }
        defineFsMethod(obj, "craft", parent, 3) { args ->
            val slot = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 1
            val sourceObj = args.getOrNull(2) as? ScriptableObject
            val source = sourceObj?.let { invHandles[it] }
                ?: throw IllegalArgumentException("crafter.craft: source must be an inventory peripheral handle")
            c.craft(slot, count, source)
        }
        defineFsMethod(obj, "adjacentBlock", parent, 0) { _ -> c.adjacentBlock() }
        return obj
    }

    /**
     * JS counterpart to [CobaltLuaHost.wrapMachineCrafter]. Same surface — size/list/craft —
     * but craft() takes an optional 4th arg `fluidSource` (a fluid peripheral handle)
     * for cards whose recipe stamps a fluid input.
     */
    private fun wrapMachineCrafter(c: MachineCrafterPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", c.kind)
        ScriptableObject.putProperty(obj, "name", c.name)
        defineFsMethod(obj, "size", parent, 0) { _ -> c.size() }
        defineFsMethod(obj, "list", parent, 0) { _ ->
            val list = c.list()
            val arr = cx().newArray(parent, list.size)
            for ((i, snap) in list.withIndex()) {
                if (snap == null) {
                    arr.put(i, arr, null)
                } else {
                    val o = NativeObject()
                    ScriptableObject.putProperty(o, "slot", snap.slot)
                    ScriptableObject.putProperty(o, "output", snap.output)
                    ScriptableObject.putProperty(o, "outputCount", snap.outputCount)
                    ScriptableObject.putProperty(o, "fluidIn", snap.fluidIn)
                    ScriptableObject.putProperty(o, "fluidInMb", snap.fluidInMb)
                    ScriptableObject.putProperty(o, "blocking", snap.blocking)
                    arr.put(i, arr, o)
                }
            }
            arr
        }
        defineFsMethod(obj, "craft", parent, 4) { args ->
            val slot = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 1
            val sourceObj = args.getOrNull(2) as? ScriptableObject
            val source = sourceObj?.let { invHandles[it] }
                ?: throw IllegalArgumentException("machine_crafter.craft: source must be an inventory peripheral handle")
            val fluidArg = args.getOrNull(3) as? ScriptableObject
            val fluidSource = fluidArg?.let { fluidHandles[it] }
            c.craft(slot, count, source, fluidSource)
        }
        defineFsMethod(obj, "adjacentBlock", parent, 0) { _ -> c.adjacentBlock() }
        return obj
    }

    /** Per-eval weak map; same rationale as [CobaltLuaHost.invHandles]. */
    private val invHandles: java.util.WeakHashMap<ScriptableObject, InventoryPeripheral> = java.util.WeakHashMap()

    /**
     * Build the `inventory` JS object — thin projection of [InventoryApi].
     *
     *   `inventory.list()`                          → array of inventory handles
     *   `inventory.find([name])`                    → handle or null
     *   `inventory.get(id, count, target [, from])` → moved
     *   `inventory.drain(machine [, id [, to]])`    → moved
     *   `inventory.put(id, count, source, target)`  → moved
     */
    private fun makeInventoryObject(env: ScriptEnv, parent: Scriptable): ScriptableObject {
        val api = InventoryApi(env)
        val obj = NativeObject()
        defineFsMethod(obj, "list", parent, 0) { _ ->
            val list = api.list()
            val arr = cx().newArray(parent, list.size)
            for ((i, p) in list.withIndex()) {
                arr.put(i, arr, wrapAnyPeripheral(p, parent))
            }
            arr
        }
        defineFsMethod(obj, "find", parent, 1) { args ->
            val name = if (args.isNotEmpty() && args[0] != null && args[0] !== Undefined.instance) asString(args, 0) else null
            val p = api.find(name) ?: return@defineFsMethod null
            wrapAnyPeripheral(p, parent)
        }
        defineFsMethod(obj, "get", parent, 4) { args ->
            val itemId = asString(args, 0)
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val target = (args.getOrNull(2) as? ScriptableObject)?.let { invHandles[it] }
                ?: return@defineFsMethod 0
            // `from` undefined (or missing) → use the API's default ranking; only a present array overrides.
            val from = readInventoryArray(args.getOrNull(3))
            api.get(itemId, count, target, from)
        }
        defineFsMethod(obj, "drain", parent, 3) { args ->
            val machine = (args.getOrNull(0) as? ScriptableObject)?.let { invHandles[it] }
                ?: return@defineFsMethod 0
            val itemId = if (args.size >= 2 && args[1] != null && args[1] !== Undefined.instance) asString(args, 1) else null
            val to = readInventoryArray(args.getOrNull(2))
            api.drain(machine, itemId, to)
        }
        defineFsMethod(obj, "put", parent, 4) { args ->
            val itemId = asString(args, 0)
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val source = (args.getOrNull(2) as? ScriptableObject)?.let { invHandles[it] }
                ?: return@defineFsMethod 0
            val target = (args.getOrNull(3) as? ScriptableObject)?.let { invHandles[it] }
                ?: return@defineFsMethod 0
            api.put(itemId, count, source, target)
        }
        return obj
    }

    /** Read a JS array of inventory handles. Null = unspecified (use defaults); empty list = empty list. */
    private fun readInventoryArray(v: Any?): List<InventoryPeripheral>? {
        if (v == null || v === Undefined.instance) return null
        val arr = v as? NativeArray ?: return null
        val out = mutableListOf<InventoryPeripheral>()
        for (i in 0 until arr.length) {
            val entry = arr.get(i.toInt(), arr) as? ScriptableObject ?: return null
            val inv = invHandles[entry] ?: return null
            out.add(inv)
        }
        return out
    }

    /**
     * Build `recipes` as a callable function-object:
     *   - `recipes("minecraft:iron_ingot")` (or `recipes.query(id)`) — query bridge
     *     producers/consumers/inputs/outputs (existing behavior).
     *   - `recipes.craft(itemId [, count])` — auto-discovery craft: walks installed
     *     `machine_crafter` / `crafter` peripherals, finds the card stamping [itemId],
     *     auto-sources ingredients, returns the expected number of items produced.
     *
     * The function-object form preserves backward compatibility (`recipes(id)`)
     * while adding `.craft` / `.query` namespaced methods on the same handle.
     */
    private fun makeRecipesFunction(env: ScriptEnv, parent: Scriptable): BaseFunction {
        val runQuery: (Context, String) -> Any = { cx, itemId ->
            val q = RecipeApi(env).query(itemId)
            val obj = NativeObject()
            ScriptableObject.putProperty(obj, "itemId", itemId)
            run {
                val arr = cx.newArray(parent, q.producers.size)
                for ((i, b) in q.producers.withIndex()) arr.put(i, arr, wrapAnyPeripheral(b, parent))
                ScriptableObject.putProperty(obj, "producers", arr)
            }
            run {
                val arr = cx.newArray(parent, q.consumers.size)
                for ((i, b) in q.consumers.withIndex()) arr.put(i, arr, wrapAnyPeripheral(b, parent))
                ScriptableObject.putProperty(obj, "consumers", arr)
            }
            run {
                val arr = cx.newArray(parent, q.inputs.size)
                for ((i, id) in q.inputs.withIndex()) arr.put(i, arr, id)
                ScriptableObject.putProperty(obj, "inputs", arr)
            }
            run {
                val arr = cx.newArray(parent, q.outputs.size)
                for ((i, id) in q.outputs.withIndex()) arr.put(i, arr, id)
                ScriptableObject.putProperty(obj, "outputs", arr)
            }
            obj
        }

        val recipes = object : BaseFunction(parent, getFunctionPrototype(parent)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                return runQuery(cx, asString(args, 0))
            }
        }
        defineFsMethod(recipes, "query", parent, 1) { args ->
            runQuery(cx(), asString(args, 0))
        }
        defineFsMethod(recipes, "craft", parent, 2) { args ->
            val itemId = asString(args, 0)
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 1
            RecipeCrafter(env).craft(itemId, count)
        }
        return recipes
    }

    /**
     * Build the `network` JS object:
     *   `network.id()`               → host computer's id (number)
     *   `network.send(msg [, ch])`   → broadcast on channel (own channel if omitted)
     *   `network.recv()`             → {from, body} or null
     *   `network.peek()`             → {from, body} or null (does not consume)
     *   `network.size()`             → pending message count (number)
     */
    private fun makeNetworkObject(env: ScriptEnv, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        defineFsMethod(obj, "id", parent, 0) { _ -> env.network.id() }
        defineFsMethod(obj, "send", parent, 2) { args ->
            val msg = asString(args, 0)
            val ch = if (args.size >= 2 && args[1] != null && args[1] !== Undefined.instance) asString(args, 1) else null
            env.network.send(msg, ch); Undefined.instance
        }
        defineFsMethod(obj, "recv", parent, 0) { _ ->
            env.network.recv()?.let { messageToJsObject(it) }
        }
        defineFsMethod(obj, "peek", parent, 0) { _ ->
            env.network.peek()?.let { messageToJsObject(it) }
        }
        defineFsMethod(obj, "size", parent, 0) { _ -> env.network.size() }
        return obj
    }

    private fun messageToJsObject(m: com.brewingcoder.oc2.platform.network.NetworkInboxes.Message): ScriptableObject {
        val o = NativeObject()
        ScriptableObject.putProperty(o, "from", m.from)
        ScriptableObject.putProperty(o, "body", m.body)
        return o
    }

    private fun wrapMonitor(mon: MonitorPeripheral, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "kind", mon.kind)
        defineFsMethod(obj, "write", parent, 1) { args ->
            mon.write(asString(args, 0)); Undefined.instance
        }
        defineFsMethod(obj, "println", parent, 1) { args ->
            mon.println(asString(args, 0)); Undefined.instance
        }
        defineFsMethod(obj, "setCursorPos", parent, 2) { args ->
            val col = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val row = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            mon.setCursorPos(col, row); Undefined.instance
        }
        defineFsMethod(obj, "getCursorPos", parent, 0) { _ ->
            val (col, row) = mon.getCursorPos()
            cx().newArray(parent, arrayOf<Any?>(col, row))
        }
        defineFsMethod(obj, "clear", parent, 0) { _ ->
            mon.clear(); Undefined.instance
        }
        defineFsMethod(obj, "getSize", parent, 0) { _ ->
            val (cols, rows) = mon.getSize()
            cx().newArray(parent, arrayOf<Any?>(cols, rows))
        }
        // setForegroundColor + CC:T-aligned alias setTextColor
        val setFg: (Array<out Any?>) -> Any? = { args ->
            val color = (args.getOrNull(0) as? Number)?.toLong()?.toInt() ?: 0
            mon.setForegroundColor(color); Undefined.instance
        }
        defineFsMethod(obj, "setForegroundColor", parent, 1, setFg)
        defineFsMethod(obj, "setTextColor", parent, 1, setFg)
        defineFsMethod(obj, "setBackgroundColor", parent, 1) { args ->
            val color = (args.getOrNull(0) as? Number)?.toLong()?.toInt() ?: 0
            mon.setBackgroundColor(color); Undefined.instance
        }
        defineFsMethod(obj, "pollTouches", parent, 0) { _ ->
            val events = mon.pollTouches()
            val arr = cx().newArray(parent, events.size)
            for ((i, ev) in events.withIndex()) {
                val o = NativeObject()
                ScriptableObject.putProperty(o, "col", ev.col)
                ScriptableObject.putProperty(o, "row", ev.row)
                ScriptableObject.putProperty(o, "px", ev.px)
                ScriptableObject.putProperty(o, "py", ev.py)
                ScriptableObject.putProperty(o, "player", ev.playerName)
                arr.put(i, arr, o)
            }
            arr
        }

        // ---- HD pixel-buffer API ----
        defineFsMethod(obj, "getPixelSize", parent, 0) { _ ->
            val (w, h) = mon.getPixelSize()
            cx().newArray(parent, arrayOf<Any?>(w, h))
        }
        defineFsMethod(obj, "clearPixels", parent, 1) { args ->
            val argb = (args.getOrNull(0) as? Number)?.toLong()?.toInt() ?: 0
            mon.clearPixels(argb); Undefined.instance
        }
        defineFsMethod(obj, "setPixel", parent, 3) { args ->
            val x = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val y = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val argb = (args.getOrNull(2) as? Number)?.toLong()?.toInt() ?: 0
            mon.setPixel(x, y, argb); Undefined.instance
        }
        defineFsMethod(obj, "drawRect", parent, 5) { args ->
            mon.drawRect(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawRectOutline", parent, 6) { args ->
            val thickness = (args.getOrNull(5) as? Number)?.toInt() ?: 1
            mon.drawRectOutline(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
                thickness,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawLine", parent, 5) { args ->
            mon.drawLine(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawGradientV", parent, 6) { args ->
            mon.drawGradientV(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
                (args.getOrNull(5) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "fillCircle", parent, 4) { args ->
            mon.fillCircle(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "fillEllipse", parent, 5) { args ->
            mon.fillEllipse(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawArc", parent, 8) { args ->
            mon.drawArc(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toInt() ?: 0,
                (args.getOrNull(5) as? Number)?.toInt() ?: 0,
                (args.getOrNull(6) as? Number)?.toInt() ?: 0,
                (args.getOrNull(7) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawItem", parent, 5) { args ->
            mon.drawItem(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                args.getOrNull(4)?.toString() ?: "",
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawFluid", parent, 5) { args ->
            mon.drawFluid(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                args.getOrNull(4)?.toString() ?: "",
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawChemical", parent, 5) { args ->
            mon.drawChemical(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                args.getOrNull(4)?.toString() ?: "",
            ); Undefined.instance
        }
        defineFsMethod(obj, "clearIcons", parent, 0) { _ ->
            mon.clearIcons(); Undefined.instance
        }

        // ---- Engine helpers (cell-geometry, ARGB math, text sugar, small font) ----
        // Lifted out of ui_v1 libraries so both language ports share one impl.
        defineFsMethod(obj, "getCellMetrics", parent, 0) { _ ->
            val m = mon.getCellMetrics()
            cx().newArray(parent, arrayOf<Any?>(m.cols, m.rows, m.pxPerCol, m.pxPerRow))
        }
        defineFsMethod(obj, "snapCellRect", parent, 2) { args ->
            val y = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val h = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val r = mon.snapCellRect(y, h)
            cx().newArray(parent, arrayOf<Any?>(r.snappedY, r.snappedH, r.textRow))
        }
        defineFsMethod(obj, "argb", parent, 4) { args ->
            mon.argb(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
            )
        }
        defineFsMethod(obj, "lighten", parent, 2) { args ->
            mon.lighten(
                (args.getOrNull(0) as? Number)?.toLong()?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
            )
        }
        defineFsMethod(obj, "dim", parent, 1) { args ->
            mon.dim((args.getOrNull(0) as? Number)?.toLong()?.toInt() ?: 0)
        }
        defineFsMethod(obj, "drawText", parent, 5) { args ->
            mon.drawText(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                asString(args, 2),
                (args.getOrNull(3) as? Number)?.toLong()?.toInt() ?: 0,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "fillText", parent, 6) { args ->
            val chArg = asString(args, 3)
            val ch = if (chArg.isNotEmpty()) chArg[0] else ' '
            mon.fillText(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                ch,
                (args.getOrNull(4) as? Number)?.toLong()?.toInt() ?: 0,
                (args.getOrNull(5) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        defineFsMethod(obj, "drawSmallText", parent, 4) { args ->
            mon.drawSmallText(
                (args.getOrNull(0) as? Number)?.toInt() ?: 0,
                (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                asString(args, 2),
                (args.getOrNull(3) as? Number)?.toLong()?.toInt() ?: 0,
            ); Undefined.instance
        }
        return obj
    }

    private fun makePrintFunction(out: ShellOutput, parent: Scriptable): BaseFunction =
        object : BaseFunction(parent, getFunctionPrototype(parent)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                val sb = StringBuilder()
                for ((i, a) in args.withIndex()) {
                    if (i > 0) sb.append(' ')
                    sb.append(jsToString(a))
                }
                out.println(sb.toString())
                return Undefined.instance
            }
        }

    /**
     * Install a `require(name)` global. Searches `/lib/?.js` then `/rom/lib/?.js`
     * — user files under `/lib/` shadow ROM copies. Each module is wrapped in
     * `(function(module, exports, require) { ...body... })` and invoked; `module.exports`
     * is returned (defaults to the same object as `exports`, CommonJS-style).
     *
     * Per-eval cache: fresh on every script run, matching the Context lifecycle.
     * No circular-require detection — a self-requiring module will stack-overflow.
     */
    private fun installRequire(scope: ScriptableObject, env: ScriptEnv) {
        val cache = mutableMapOf<String, Any?>()
        val searchPaths = listOf("lib/%s.js", "rom/lib/%s.js")

        val requireFn = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val name = asString(args, 0)
                if (name.isEmpty()) throw Context.reportRuntimeError("require() requires a module name")
                cache[name]?.let { return it }

                val tried = mutableListOf<String>()
                var source: String? = null
                for (pattern in searchPaths) {
                    val path = pattern.format(name)
                    tried.add("/$path")
                    try {
                        if (env.mount.exists(path) && !env.mount.isDirectory(path)) {
                            source = readMountFile(env.mount, path)
                            break
                        }
                    } catch (_: StorageException) { /* try next */ }
                }
                if (source == null) {
                    throw Context.reportRuntimeError(
                        "module '$name' not found; searched: ${tried.joinToString(";")}"
                    )
                }

                // CommonJS wrapper. Evaluating a parenthesized function expression yields
                // the Function value, which we then invoke with (module, exports, require).
                // `this` inside the wrapper is `module.exports`, matching Node's behavior
                // so users can write `this.foo = ...` or `module.exports = ...` interchangeably.
                val wrapped = "(function (module, exports, require) {\n$source\n})"
                val wrapperAny = try {
                    cx.evaluateString(scope, wrapped, "@$name", 1, null)
                } catch (e: RhinoException) {
                    throw Context.reportRuntimeError("compile error in module '$name': ${e.message}")
                }
                val wrapper = wrapperAny as? org.mozilla.javascript.Function
                    ?: throw Context.reportRuntimeError("module '$name' did not compile to a function")

                val moduleObj = NativeObject()
                val exportsObj = NativeObject()
                ScriptableObject.putProperty(moduleObj, "exports", exportsObj)

                try {
                    wrapper.call(cx, scope, exportsObj, arrayOf<Any?>(moduleObj, exportsObj, this))
                } catch (e: RhinoException) {
                    throw Context.reportRuntimeError("error loading module '$name': ${e.message}")
                }

                val result = ScriptableObject.getProperty(moduleObj, "exports")
                cache[name] = result
                return result
            }
        }
        ScriptableObject.putProperty(scope, "require", requireFn)
    }

    private fun readMountFile(mount: WritableMount, path: String): String {
        mount.openForRead(path).use { ch ->
            val size = ch.size().toInt()
            val buf = java.nio.ByteBuffer.allocate(size)
            while (buf.hasRemaining()) {
                val n = ch.read(buf)
                if (n < 0) break
            }
            return String(buf.array(), 0, buf.position(), Charsets.UTF_8)
        }
    }

    /**
     * Install the JS-side event loop host-binding and browser-style timers.
     *
     * JS lacks Lua's `os.pullEvent` (that requires Rhino continuations — deferred,
     * see `docs/12-followups.md`). Instead the UI library's `ui.run(root)` calls
     * host-provided `__uiRun(dispatcher)`, which:
     *   - enters a blocking loop polling [ScriptEnv.events]
     *   - for each script event, invokes [dispatcher] via Rhino reentry with an
     *     event object `{name, args}`
     *   - for synthetic timer events (produced by [setTimeout]/[setInterval]),
     *     bypasses [dispatcher] and fires the stored callback directly
     *   - returns when `__uiExit()` flips the running flag or the worker thread is interrupted
     *
     * `setTimeout(fn, ms)` / `setInterval(fn, ms)` spawn daemon threads that
     * sleep and then offer a synthetic [ScriptEvent] into the queue. Callbacks
     * therefore only fire while `__uiRun` is actively looping — matches browser
     * event-loop semantics (no setTimeout without a running event loop).
     *
     * Per-eval state: timer callbacks + running flag live in this closure, so
     * every [eval] call gets fresh state. Any orphaned timer threads from a
     * previous eval keep offering into THEIR old queue, which nobody polls —
     * harmless until the daemon thread terminates on JVM exit or interrupt.
     */
    private fun installUiRunHost(scope: ScriptableObject, env: ScriptEnv) {
        val running = AtomicBoolean(false)
        val timerCounter = AtomicInteger(1)
        // One-shot callbacks: removed on fire OR on clearTimeout.
        val timeoutCallbacks = ConcurrentHashMap<Int, RhinoFunction>()
        // Recurring callbacks: removed on clearInterval; cancel flag tells daemon thread to stop.
        val intervalCallbacks = ConcurrentHashMap<Int, RhinoFunction>()
        val intervalCancels = ConcurrentHashMap<Int, AtomicBoolean>()

        // __uiRun(dispatcher) — blocks until __uiExit or interrupt.
        ScriptableObject.putProperty(scope, "__uiRun", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val dispatcher = args.getOrNull(0) as? RhinoFunction
                    ?: throw Context.reportRuntimeError("__uiRun: first argument must be a function")
                if (!running.compareAndSet(false, true)) {
                    throw Context.reportRuntimeError("__uiRun: already running (reentrant call)")
                }
                try {
                    while (running.get()) {
                        val ev = try {
                            env.events.poll(null, 60_000L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt(); break
                        } ?: continue

                        when (ev.name) {
                            UI_TIMEOUT_EVENT -> {
                                val id = (ev.args.firstOrNull() as? Number)?.toInt() ?: continue
                                val fn = timeoutCallbacks.remove(id) ?: continue
                                invokeQuietly(cx, scope, fn, emptyArray())
                            }
                            UI_INTERVAL_EVENT -> {
                                val id = (ev.args.firstOrNull() as? Number)?.toInt() ?: continue
                                val fn = intervalCallbacks[id] ?: continue  // cleared after scheduling
                                invokeQuietly(cx, scope, fn, emptyArray())
                            }
                            else -> {
                                val evObj = scriptEventToJs(ev, scope)
                                invokeQuietly(cx, scope, dispatcher, arrayOf<Any?>(evObj))
                            }
                        }
                    }
                } finally {
                    running.set(false)
                }
                return Undefined.instance
            }
        })

        // __uiExit() — flips the flag; __uiRun loop exits after the current poll.
        ScriptableObject.putProperty(scope, "__uiExit", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                running.set(false)
                return Undefined.instance
            }
        })

        // setTimeout(fn, ms) → id
        ScriptableObject.putProperty(scope, "setTimeout", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? RhinoFunction
                    ?: throw Context.reportRuntimeError("setTimeout: first argument must be a function")
                val ms = (args.getOrNull(1) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
                val id = timerCounter.getAndIncrement()
                timeoutCallbacks[id] = fn
                Thread({
                    try {
                        if (ms > 0) Thread.sleep(ms)
                        // Only offer if not already cleared. Offering a cleared one
                        // is fine (lookup returns null) but avoids cluttering the queue.
                        if (timeoutCallbacks.containsKey(id)) {
                            env.events.offer(ScriptEvent(UI_TIMEOUT_EVENT, listOf(id)))
                        }
                    } catch (_: InterruptedException) {
                        timeoutCallbacks.remove(id)
                    }
                }, "OC2 js-setTimeout id=$id").apply { isDaemon = true }.start()
                return id
            }
        })

        // clearTimeout(id)
        ScriptableObject.putProperty(scope, "clearTimeout", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return Undefined.instance
                timeoutCallbacks.remove(id)
                return Undefined.instance
            }
        })

        // setInterval(fn, ms) → id
        ScriptableObject.putProperty(scope, "setInterval", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? RhinoFunction
                    ?: throw Context.reportRuntimeError("setInterval: first argument must be a function")
                // Minimum 1ms to avoid tight loops.
                val ms = (args.getOrNull(1) as? Number)?.toLong()?.coerceAtLeast(1L) ?: 1L
                val id = timerCounter.getAndIncrement()
                val cancel = AtomicBoolean(false)
                intervalCallbacks[id] = fn
                intervalCancels[id] = cancel
                Thread({
                    try {
                        while (!cancel.get()) {
                            Thread.sleep(ms)
                            if (cancel.get()) break
                            env.events.offer(ScriptEvent(UI_INTERVAL_EVENT, listOf(id)))
                        }
                    } catch (_: InterruptedException) { /* drop */ }
                    intervalCallbacks.remove(id)
                    intervalCancels.remove(id)
                }, "OC2 js-setInterval id=$id").apply { isDaemon = true }.start()
                return id
            }
        })

        // clearInterval(id)
        ScriptableObject.putProperty(scope, "clearInterval", object : BaseFunction(scope, getFunctionPrototype(scope)) {
            override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return Undefined.instance
                intervalCancels[id]?.set(true)
                intervalCallbacks.remove(id)
                return Undefined.instance
            }
        })
    }

    /** Build the JS event object handed to a `__uiRun` dispatcher: `{name, args: [...]}`. */
    private fun scriptEventToJs(ev: ScriptEvent, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", ev.name)
        val arr = cx().newArray(parent, ev.args.size)
        for ((i, a) in ev.args.withIndex()) {
            arr.put(i, arr, jsPrimitive(a))
        }
        ScriptableObject.putProperty(obj, "args", arr)
        return obj
    }

    /** Coerce an event arg (String/Int/Long/Double/Boolean/null) to a JS-safe value. */
    private fun jsPrimitive(v: Any?): Any? = when (v) {
        null -> null
        is Boolean, is String -> v
        is Int -> v
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        is Double -> v
        else -> v.toString()
    }

    /**
     * Invoke a JS function from inside `__uiRun`'s loop. Swallows exceptions so
     * one bad callback doesn't take down the whole event loop — the loop is the
     * program's outermost control flow, and a broken timer shouldn't kill the
     * dashboard.
     */
    private fun invokeQuietly(cx: Context, scope: Scriptable, fn: RhinoFunction, args: Array<out Any?>) {
        try {
            fn.call(cx, scope, scope, args)
        } catch (_: RhinoException) { /* keep loop alive */ }
        catch (_: JavaScriptException) { /* keep loop alive */ }
    }

    private companion object {
        const val UI_TIMEOUT_EVENT = "__js_timeout_fire"
        const val UI_INTERVAL_EVENT = "__js_interval_fire"
    }

    /** Build the fs API as a Rhino object with one [BaseFunction] per method. */
    private fun makeFsObject(mount: WritableMount, cwd: String, parent: Scriptable): ScriptableObject {
        val obj = NativeObject()
        defineFsMethod(obj, "list", parent, 1) { args ->
            val entries = ScriptFsOps.list(mount, cwd, asString(args, 0))
            // Return as a NativeArray so JS sees it as a real Array
            cx().newArray(parent, entries.toTypedArray<Any?>())
        }
        defineFsMethod(obj, "exists", parent, 1) { args ->
            ScriptFsOps.exists(mount, cwd, asString(args, 0))
        }
        defineFsMethod(obj, "isDir", parent, 1) { args ->
            ScriptFsOps.isDir(mount, cwd, asString(args, 0))
        }
        defineFsMethod(obj, "size", parent, 1) { args ->
            ScriptFsOps.size(mount, cwd, asString(args, 0)).toDouble()
        }
        defineFsMethod(obj, "read", parent, 1) { args ->
            ScriptFsOps.read(mount, cwd, asString(args, 0))
        }
        defineFsMethod(obj, "write", parent, 2) { args ->
            ScriptFsOps.write(mount, cwd, asString(args, 0), asString(args, 1))
            Undefined.instance
        }
        defineFsMethod(obj, "append", parent, 2) { args ->
            ScriptFsOps.append(mount, cwd, asString(args, 0), asString(args, 1))
            Undefined.instance
        }
        defineFsMethod(obj, "mkdir", parent, 1) { args ->
            ScriptFsOps.mkdir(mount, cwd, asString(args, 0))
            Undefined.instance
        }
        defineFsMethod(obj, "delete", parent, 1) { args ->
            ScriptFsOps.delete(mount, cwd, asString(args, 0))
            Undefined.instance
        }
        defineFsMethod(obj, "capacity", parent, 0) { _ -> ScriptFsOps.capacity(mount).toDouble() }
        defineFsMethod(obj, "free", parent, 0) { _ -> ScriptFsOps.free(mount).toDouble() }
        defineFsMethod(obj, "combine", parent, 2) { args -> ScriptFsOps.combine(asString(args, 0), asString(args, 1)) }
        defineFsMethod(obj, "getName", parent, 1) { args -> ScriptFsOps.getName(asString(args, 0)) }
        defineFsMethod(obj, "getDir", parent, 1) { args -> ScriptFsOps.getDir(asString(args, 0)) }
        return obj
    }

    private inline fun defineFsMethod(
        target: ScriptableObject,
        name: String,
        parent: Scriptable,
        @Suppress("UNUSED_PARAMETER") arity: Int,
        crossinline body: (Array<out Any?>) -> Any?,
    ) {
        val fn = object : BaseFunction(parent, getFunctionPrototype(parent)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? = try {
                body(args)
            } catch (e: StorageException) {
                throw Context.reportRuntimeError(e.message ?: "fs error")
            }
        }
        ScriptableObject.putProperty(target, name, fn)
    }

    private fun cx(): Context = Context.getCurrentContext()

    private fun getFunctionPrototype(scope: Scriptable): Scriptable =
        ScriptableObject.getFunctionPrototype(scope)

    /** Best-effort `String(x)` for arbitrary JS values. Avoids `[object Object]` for primitives. */
    private fun jsToString(v: Any?): String = when (v) {
        null -> "null"
        Undefined.instance -> "undefined"
        is Double -> {
            if (v.isFinite() && v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
        else -> Context.toString(v)
    }

    private fun asString(args: Array<out Any?>, i: Int): String =
        if (i < args.size && args[i] != null && args[i] !== Undefined.instance) Context.toString(args[i]) else ""
}

/** Plain JS object — Rhino's NativeObject is the bag we hang fs.* methods off. */
private class NativeObject : ScriptableObject() {
    override fun getClassName(): String = "Object"
}
