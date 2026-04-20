package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.EnergyPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
import com.brewingcoder.oc2.platform.peripheral.RedstonePeripheral
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

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

    override fun eval(source: String, chunkName: String, env: ScriptEnv): ScriptResult {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1  // interpreter only — see class doc
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "print", makePrintFunction(env.out, scope))
            ScriptableObject.putProperty(scope, "fs", makeFsObject(env.mount, env.cwd, scope))
            ScriptableObject.putProperty(scope, "peripheral", makePeripheralObject(env, scope))
            ScriptableObject.putProperty(scope, "colors", makeColorsObject())
            ScriptableObject.putProperty(scope, "network", makeNetworkObject(env, scope))
            ScriptableObject.putProperty(scope, "sleep", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                override fun call(cx2: Context, scope2: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
                    if (ms > 0) Thread.sleep(ms)
                    return Undefined.instance
                }
            })
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
            val p = env.findPeripheral(kind) ?: return@defineFsMethod null
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

    private fun wrapAnyPeripheral(p: com.brewingcoder.oc2.platform.peripheral.Peripheral, parent: Scriptable): Any? = when (p) {
        is MonitorPeripheral -> wrapMonitor(p, parent)
        is InventoryPeripheral -> wrapInventory(p, parent)
        is RedstonePeripheral -> wrapRedstone(p, parent)
        is FluidPeripheral -> wrapFluid(p, parent)
        is EnergyPeripheral -> wrapEnergy(p, parent)
        is BlockPeripheral -> wrapBlock(p, parent)
        is BridgePeripheral -> wrapBridge(p, parent)
        else -> null
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

    /** Per-eval weak map; same rationale as [CobaltLuaHost.invHandles]. */
    private val invHandles: java.util.WeakHashMap<ScriptableObject, InventoryPeripheral> = java.util.WeakHashMap()

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
