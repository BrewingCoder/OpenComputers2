package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import org.squiddev.cobalt.Constants
import org.squiddev.cobalt.LuaError
import org.squiddev.cobalt.LuaState
import org.squiddev.cobalt.LuaTable
import org.squiddev.cobalt.LuaThread
import org.squiddev.cobalt.LuaValue
import org.squiddev.cobalt.ValueFactory
import org.squiddev.cobalt.Varargs
import org.squiddev.cobalt.compiler.CompileException
import org.squiddev.cobalt.compiler.LoadState
import org.squiddev.cobalt.function.VarArgFunction
import org.squiddev.cobalt.lib.CoreLibraries
import java.io.ByteArrayInputStream

/**
 * Cobalt-backed Lua 5.2 host. One [LuaState] per [eval] call — globals don't
 * persist across `run` invocations in v0. (When we add a persistent REPL, the
 * state will live on the [com.brewingcoder.oc2.platform.os.ShellSession].)
 *
 * What's installed:
 *   - Cobalt's standard libraries (`base`, `string`, `table`, `math`, `os`, `io`)
 *   - An overridden `print` that pushes to the supplied [ShellOutput] instead
 *     of the host JVM's stdout
 *
 * What's deliberately NOT installed yet:
 *   - Cooperative yielding / cycle budget — runaway scripts will block the
 *     server thread until OOM. Acceptable for v0; will land via the Cobalt
 *     `interruptHandler` + a wall-clock watchdog in a follow-up.
 *   - A `fs` API exposing the computer's [com.brewingcoder.oc2.platform.storage.WritableMount]
 *   - The OC2 channel-registry / RPC bindings
 *
 * v0 goal: prove the pipeline (file → compile → execute → captured output).
 */
class CobaltLuaHost : ScriptHost {

    override fun eval(source: String, chunkName: String, env: ScriptEnv): ScriptResult {
        val state = LuaState()
        return try {
            val globals = CoreLibraries.standardGlobals(state)
            globals.rawset("print", makePrintFunction(env.out))
            globals.rawset("fs", makeFsTable(env.mount, env.cwd))
            globals.rawset("peripheral", makePeripheralTable(env))
            globals.rawset("colors", makeColorsTable())
            globals.rawset("sleep", fn { args ->
                val ms = args.arg(1).toDouble().toLong().coerceIn(0L, 60_000L)
                if (ms > 0) Thread.sleep(ms)
                Constants.NIL
            })
            val bytes = source.toByteArray(Charsets.UTF_8)
            val chunk = LoadState.load(state, ByteArrayInputStream(bytes), "@$chunkName", globals)
            LuaThread.runMain(state, chunk)
            ScriptResult(ok = true, errorMessage = null)
        } catch (e: CompileException) {
            ScriptResult(ok = false, errorMessage = cleanError("compile error", e.message))
        } catch (e: LuaError) {
            ScriptResult(ok = false, errorMessage = cleanError("lua error", e.message))
        }
    }

    /** Strip Java-internals noise (java.lang.X, fully-qualified InterruptedException etc). */
    private fun cleanError(prefix: String, raw: String?): String {
        val msg = raw ?: "unknown"
        // Strip java.lang.* class names from messages: "java.lang.InterruptedException: foo" → "foo"
        val cleaned = msg.replace(Regex("""java\.lang\.\w+(?:\.\w+)*:\s*"""), "")
            .replace(Regex("""vm error:\s*"""), "")
        return "$prefix: $cleaned"
    }

    private fun makePrintFunction(out: ShellOutput): VarArgFunction = object : VarArgFunction() {
        override fun invoke(state: LuaState, args: Varargs): Varargs {
            val sb = StringBuilder()
            for (i in 1..args.count()) {
                if (i > 1) sb.append('\t')
                sb.append(luaToString(args.arg(i)))
            }
            out.println(sb.toString())
            return Constants.NONE
        }
    }

    /**
     * Build the `fs` table. Every entry wraps [ScriptFsOps] and translates
     * [StorageException] into [LuaError] so Lua's `pcall` / `xpcall` behavior
     * is what users expect.
     */
    private fun makeFsTable(mount: WritableMount, cwd: String): LuaTable {
        val t = LuaTable()
        t.rawset("list", fn { args ->
            val path = args.arg(1).toString()
            val entries = ScriptFsOps.list(mount, cwd, path)
            val arr = LuaTable()
            for ((i, e) in entries.withIndex()) arr.rawset(i + 1, ValueFactory.valueOf(e))
            arr
        })
        t.rawset("exists", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.exists(mount, cwd, args.arg(1).toString()))
        })
        t.rawset("isDir", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.isDir(mount, cwd, args.arg(1).toString()))
        })
        t.rawset("size", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.size(mount, cwd, args.arg(1).toString()).toDouble())
        })
        t.rawset("read", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.read(mount, cwd, args.arg(1).toString()))
        })
        t.rawset("write", fn { args ->
            ScriptFsOps.write(mount, cwd, args.arg(1).toString(), args.arg(2).toString())
            Constants.NIL
        })
        t.rawset("append", fn { args ->
            ScriptFsOps.append(mount, cwd, args.arg(1).toString(), args.arg(2).toString())
            Constants.NIL
        })
        t.rawset("mkdir", fn { args ->
            ScriptFsOps.mkdir(mount, cwd, args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("delete", fn { args ->
            ScriptFsOps.delete(mount, cwd, args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("capacity", fn { ValueFactory.valueOf(ScriptFsOps.capacity(mount).toDouble()) })
        t.rawset("free", fn { ValueFactory.valueOf(ScriptFsOps.free(mount).toDouble()) })
        // Path utilities — pure string ops, no mount access
        t.rawset("combine", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.combine(args.arg(1).toString(), args.arg(2).toString()))
        })
        t.rawset("getName", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.getName(args.arg(1).toString()))
        })
        t.rawset("getDir", fn { args ->
            ValueFactory.valueOf(ScriptFsOps.getDir(args.arg(1).toString()))
        })
        return t
    }

    private inline fun fn(crossinline body: (Varargs) -> LuaValue): VarArgFunction = object : VarArgFunction() {
        override fun invoke(state: LuaState, args: Varargs): Varargs = try {
            body(args)
        } catch (e: StorageException) {
            throw LuaError(e.message ?: "fs error")
        }
    }

    /**
     * Build the `peripheral` table.
     *
     *   `peripheral.find(kind)` → table or nil
     *   `peripheral.list([kind])` → table of handles (all peripherals on the channel)
     *
     * For v0 only [MonitorPeripheral] is exposed; future kinds wrap the same way.
     */
    private fun makePeripheralTable(env: ScriptEnv): LuaTable {
        val t = LuaTable()
        t.rawset("find", fn { args ->
            val kind = args.arg(1).toString()
            val p = env.findPeripheral(kind) ?: return@fn Constants.NIL
            wrapPeripheral(p)
        })
        t.rawset("list", fn { args ->
            val kind = if (args.count() >= 1 && !args.arg(1).isNil()) args.arg(1).toString() else null
            val arr = LuaTable()
            for ((i, p) in env.listPeripherals(kind).withIndex()) {
                val wrapped = wrapPeripheral(p)
                if (wrapped !== Constants.NIL) arr.rawset(i + 1, wrapped)
            }
            arr
        })
        return t
    }

    private fun wrapPeripheral(p: com.brewingcoder.oc2.platform.peripheral.Peripheral): LuaValue = when (p) {
        is MonitorPeripheral -> wrapMonitor(p)
        else -> Constants.NIL
    }

    private fun makeColorsTable(): LuaTable {
        val t = LuaTable()
        for ((name, value) in ScriptColors.PALETTE) {
            // Use double to preserve high-bit alpha across Lua's number model.
            t.rawset(name, ValueFactory.valueOf(value.toLong().and(0xFFFFFFFFL).toDouble()))
        }
        return t
    }

    private fun wrapMonitor(mon: MonitorPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(mon.kind))
        t.rawset("write", fn { args ->
            mon.write(args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("println", fn { args ->
            mon.println(args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("setCursorPos", fn { args ->
            mon.setCursorPos(args.arg(1).toInteger().toInt(), args.arg(2).toInteger().toInt())
            Constants.NIL
        })
        t.rawset("getCursorPos", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val (col, row) = mon.getCursorPos()
                return ValueFactory.varargsOf(ValueFactory.valueOf(col), ValueFactory.valueOf(row))
            }
        })
        t.rawset("clear", fn {
            mon.clear()
            Constants.NIL
        })
        t.rawset("getSize", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val (cols, rows) = mon.getSize()
                return ValueFactory.varargsOf(ValueFactory.valueOf(cols), ValueFactory.valueOf(rows))
            }
        })
        // Color setters take ARGB ints. Use toLong().toInt() so values >= 2^31
        // (e.g. 0xFFD4D4D4) round-trip correctly — Lua numbers are doubles, and
        // direct LuaInteger.toInt() truncates to int range losing the high alpha bit.
        // setForegroundColor + CC:T-aligned alias setTextColor
        val setFg: VarArgFunction = fn { args ->
            mon.setForegroundColor(args.arg(1).toDouble().toLong().toInt())
            Constants.NIL
        }
        t.rawset("setForegroundColor", setFg)
        t.rawset("setTextColor", setFg)
        t.rawset("setBackgroundColor", fn { args ->
            mon.setBackgroundColor(args.arg(1).toDouble().toLong().toInt())
            Constants.NIL
        })
        t.rawset("pollTouches", fn {
            val events = mon.pollTouches()
            val arr = LuaTable()
            for ((i, ev) in events.withIndex()) {
                val e = LuaTable()
                e.rawset("col", ValueFactory.valueOf(ev.col))
                e.rawset("row", ValueFactory.valueOf(ev.row))
                e.rawset("player", ValueFactory.valueOf(ev.playerName))
                arr.rawset(i + 1, e)
            }
            arr
        })
        return t
    }

    /** Best-effort `tostring()` for arbitrary [LuaValue]s. Does not invoke `__tostring` metamethods. */
    private fun luaToString(v: LuaValue): String = when {
        v.isNumber() -> {
            // Cobalt's number toString may emit "5.0" for integers; trim to "5" for cleaner shell output.
            val d = v.toDouble()
            if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }
}
