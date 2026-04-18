package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
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
        else -> null
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
                ScriptableObject.putProperty(o, "player", ev.playerName)
                arr.put(i, arr, o)
            }
            arr
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
