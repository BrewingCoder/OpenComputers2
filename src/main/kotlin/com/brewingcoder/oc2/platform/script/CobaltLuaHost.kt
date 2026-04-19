package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.EnergyPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
import com.brewingcoder.oc2.platform.peripheral.RedstonePeripheral
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
            globals.rawset("network", makeNetworkTable(env))
            globals.rawset("json", makeJsonTable())
            // CC:T-style os.* event API. Cobalt's `os` library exists already
            // (clock/time/date); we MERGE into the existing table rather than
            // overwriting it.
            installOsEventApi(globals, env)
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
        is InventoryPeripheral -> wrapInventory(p)
        is RedstonePeripheral -> wrapRedstone(p)
        is FluidPeripheral -> wrapFluid(p)
        is EnergyPeripheral -> wrapEnergy(p)
        is BlockPeripheral -> wrapBlock(p)
        else -> Constants.NIL
    }

    private fun wrapBlock(b: BlockPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(b.kind))
        t.rawset("name", ValueFactory.valueOf(b.name))
        t.rawset("read", fn {
            val r = b.read() ?: return@fn Constants.NIL
            val o = LuaTable()
            o.rawset("id", ValueFactory.valueOf(r.id))
            o.rawset("isAir", ValueFactory.valueOf(r.isAir))
            o.rawset("lightLevel", ValueFactory.valueOf(r.lightLevel))
            o.rawset("redstonePower", ValueFactory.valueOf(r.redstonePower))
            o.rawset("hardness", ValueFactory.valueOf(r.hardness.toDouble()))
            val pos = LuaTable()
            pos.rawset("x", ValueFactory.valueOf(r.pos.first))
            pos.rawset("y", ValueFactory.valueOf(r.pos.second))
            pos.rawset("z", ValueFactory.valueOf(r.pos.third))
            o.rawset("pos", pos)
            if (r.nbt != null) o.rawset("nbt", ValueFactory.valueOf(r.nbt))
            o
        })
        t.rawset("harvest", fn { args ->
            val targetTable = args.arg(1) as? LuaTable
            val target = targetTable?.let { invHandles[it] }
            val moved = b.harvest(target)
            val arr = LuaTable()
            for ((i, snap) in moved.withIndex()) {
                arr.rawset(i + 1, itemSnapshotToTable(snap))
            }
            arr
        })
        return t
    }

    private fun wrapFluid(fl: FluidPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(fl.kind))
        t.rawset("name", ValueFactory.valueOf(fl.name))
        fluidHandles[t] = fl
        t.rawset("tanks", fn { ValueFactory.valueOf(fl.tanks()) })
        t.rawset("getFluid", fn { args ->
            val s = fl.getFluid(args.arg(1).toInteger().toInt()) ?: return@fn Constants.NIL
            fluidSnapshotToTable(s)
        })
        t.rawset("list", fn {
            val arr = LuaTable()
            for ((i, snap) in fl.list().withIndex()) {
                arr.rawset(i + 1, snap?.let { fluidSnapshotToTable(it) } ?: Constants.NIL)
            }
            arr
        })
        t.rawset("push", fn { args ->
            val tgt = (args.arg(1) as? LuaTable)?.let { fluidHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1000
            ValueFactory.valueOf(fl.push(tgt, amount))
        })
        t.rawset("pull", fn { args ->
            val src = (args.arg(1) as? LuaTable)?.let { fluidHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1000
            ValueFactory.valueOf(fl.pull(src, amount))
        })
        t.rawset("destroy", fn { args ->
            val amount = args.arg(1).toInteger().toInt()
            ValueFactory.valueOf(fl.destroy(amount))
        })
        return t
    }

    private fun fluidSnapshotToTable(s: FluidPeripheral.FluidSnapshot): LuaTable {
        val o = LuaTable()
        o.rawset("id", ValueFactory.valueOf(s.id))
        o.rawset("amount", ValueFactory.valueOf(s.amount))
        return o
    }

    private val fluidHandles: java.util.WeakHashMap<LuaTable, FluidPeripheral> = java.util.WeakHashMap()

    private fun wrapEnergy(en: EnergyPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(en.kind))
        t.rawset("name", ValueFactory.valueOf(en.name))
        energyHandles[t] = en
        t.rawset("stored", fn { ValueFactory.valueOf(en.stored()) })
        t.rawset("capacity", fn { ValueFactory.valueOf(en.capacity()) })
        t.rawset("push", fn { args ->
            val tgt = (args.arg(1) as? LuaTable)?.let { energyHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(en.push(tgt, amount))
        })
        t.rawset("pull", fn { args ->
            val src = (args.arg(1) as? LuaTable)?.let { energyHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(en.pull(src, amount))
        })
        t.rawset("destroy", fn { args ->
            val amount = args.arg(1).toInteger().toInt()
            ValueFactory.valueOf(en.destroy(amount))
        })
        return t
    }

    private val energyHandles: java.util.WeakHashMap<LuaTable, EnergyPeripheral> = java.util.WeakHashMap()

    private fun wrapRedstone(rs: RedstonePeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(rs.kind))
        t.rawset("name", ValueFactory.valueOf(rs.name))
        t.rawset("getInput", fn { ValueFactory.valueOf(rs.getInput()) })
        t.rawset("getOutput", fn { ValueFactory.valueOf(rs.getOutput()) })
        t.rawset("setOutput", fn { args ->
            rs.setOutput(args.arg(1).toInteger().toInt())
            Constants.NIL
        })
        return t
    }

    /**
     * Build the `network` table:
     *   `network.id()`            → host computer's id (int)
     *   `network.send(msg [, ch])` → broadcast on channel (own channel if omitted)
     *   `network.recv()`          → table {from, body} or nil
     *   `network.peek()`          → table {from, body} or nil (does not consume)
     *   `network.size()`          → pending message count (int)
     */
    private fun makeNetworkTable(env: ScriptEnv): LuaTable {
        val t = LuaTable()
        t.rawset("id", fn { ValueFactory.valueOf(env.network.id()) })
        t.rawset("send", fn { args ->
            val msg = args.arg(1).toString()
            val ch = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toString() else null
            env.network.send(msg, ch)
            Constants.NIL
        })
        t.rawset("recv", fn {
            val m = env.network.recv() ?: return@fn Constants.NIL
            messageToTable(m)
        })
        t.rawset("peek", fn {
            val m = env.network.peek() ?: return@fn Constants.NIL
            messageToTable(m)
        })
        t.rawset("size", fn { ValueFactory.valueOf(env.network.size()) })
        return t
    }

    private fun messageToTable(m: com.brewingcoder.oc2.platform.network.NetworkInboxes.Message): LuaTable {
        val o = LuaTable()
        o.rawset("from", ValueFactory.valueOf(m.from))
        o.rawset("body", ValueFactory.valueOf(m.body))
        return o
    }

    /**
     * Build the `json` table — Gson-backed encode/decode. Lua scripts use this
     * to serialize tables for `network.send`. JS scripts get the standard `JSON`
     * object from Rhino instead.
     *
     * Conversion rules:
     *   - Lua tables with integer keys 1..n → JSON arrays
     *   - Lua tables otherwise              → JSON objects (string keys)
     *   - nil / NIL                         → JSON null (encode); decode produces NIL
     *   - numbers, booleans, strings        → corresponding JSON primitives
     *
     * Decode errors (malformed JSON) raise a Lua error so callers can `pcall`.
     */
    /**
     * Install the CC:T-style `os.*` event API onto the existing `os` table that
     * Cobalt's CoreLibraries provides. We do NOT replace the table — we merge
     * three new functions: `pullEvent`, `queueEvent`, `startTimer`.
     *
     * Timer scheduling is local to this script's event queue: the worker thread
     * sleeps until the timer fires (no server-tick coupling needed for Phase 1).
     */
    private fun installOsEventApi(globals: LuaTable, env: ScriptEnv) {
        val os = globals.rawget("os") as? LuaTable ?: LuaTable().also { globals.rawset("os", it) }
        val nextTimerId = java.util.concurrent.atomic.AtomicInteger(1)

        // os.pullEvent([filter])  →  name, args... (blocks indefinitely)
        os.rawset("pullEvent", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val filter = if (args.count() >= 1 && !args.arg(1).isNil()) args.arg(1).toString() else null
                // Block "indefinitely" but in chunks so kill/interrupt is responsive.
                while (true) {
                    val ev = env.events.poll(filter, 60_000L) ?: continue
                    val out = arrayOfNulls<LuaValue>(1 + ev.args.size)
                    out[0] = ValueFactory.valueOf(ev.name)
                    for ((i, a) in ev.args.withIndex()) out[i + 1] = toLuaValue(a)
                    @Suppress("UNCHECKED_CAST")
                    return ValueFactory.varargsOf(*(out as Array<LuaValue>))
                }
                @Suppress("UNREACHABLE_CODE") Constants.NONE
            }
        })

        // os.queueEvent(name, ...)  →  enqueue an event into THIS script's queue
        os.rawset("queueEvent", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                if (args.count() < 1) return Constants.NONE
                val name = args.arg(1).toString()
                val payload = (2..args.count()).map { fromLuaValue(args.arg(it)) }
                env.events.offer(ScriptEvent(name, payload))
                return Constants.NONE
            }
        })

        // os.startTimer(seconds)  →  timerId. Fires "timer" event with that id when due.
        os.rawset("startTimer", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val secs = args.arg(1).toDouble().coerceAtLeast(0.0)
                val id = nextTimerId.getAndIncrement()
                val delayMs = (secs * 1000).toLong()
                Thread({
                    try {
                        Thread.sleep(delayMs)
                        env.events.offer(ScriptEvent("timer", listOf(id)))
                    } catch (_: InterruptedException) { /* script killed; drop */ }
                }, "OC2 timer pid=?-id=$id").apply { isDaemon = true }.start()
                return ValueFactory.valueOf(id)
            }
        })
    }

    /** Convert a Kotlin event-arg back to a LuaValue. Supports the wire types we accept. */
    private fun toLuaValue(v: Any?): LuaValue = when (v) {
        null -> Constants.NIL
        is Boolean -> ValueFactory.valueOf(v)
        is Int -> ValueFactory.valueOf(v.toDouble())
        is Long -> ValueFactory.valueOf(v.toDouble())
        is Double -> ValueFactory.valueOf(v)
        is String -> ValueFactory.valueOf(v)
        else -> ValueFactory.valueOf(v.toString())
    }

    /** Inverse of [toLuaValue] — extract a primitive from a Lua arg for queueEvent. */
    private fun fromLuaValue(v: LuaValue): Any? = when {
        v.isNil() -> null
        v.type() == Constants.TBOOLEAN -> v.toBoolean()
        v.isNumber() -> {
            val d = v.toDouble()
            if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toInt() else d
        }
        else -> v.toString()
    }

    private fun makeJsonTable(): LuaTable {
        val t = LuaTable()
        t.rawset("encode", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs =
                ValueFactory.valueOf(LuaJson.encode(args.arg(1)))
        })
        t.rawset("decode", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs = try {
                LuaJson.decode(args.arg(1).toString())
            } catch (e: Exception) {
                throw LuaError("json decode: ${e.message ?: e::class.simpleName}")
            }
        })
        return t
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

    /**
     * Wrap an [InventoryPeripheral]. Methods take 1-indexed slots (Lua tradition);
     * the underlying impl matches.
     *
     * `getItem`/`list` produce `{id=string, count=int}` tables (or nil for empty
     * slots in `list`). `push`/`pull` accept another inventory handle from
     * `peripheral.find`/`peripheral.list` — we recover it via Lua's userdata-as-table
     * convention by stashing the underlying handle on the table at `__handle`.
     */
    private fun wrapInventory(inv: InventoryPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(inv.kind))
        t.rawset("name", ValueFactory.valueOf(inv.name))
        // Stash the native handle so push/pull can resolve "the other end" back to a Peripheral.
        invHandles[t] = inv
        t.rawset("size", fn { ValueFactory.valueOf(inv.size()) })
        t.rawset("getItem", fn { args ->
            val s = inv.getItem(args.arg(1).toInteger().toInt()) ?: return@fn Constants.NIL
            itemSnapshotToTable(s)
        })
        t.rawset("list", fn {
            val arr = LuaTable()
            for ((i, snap) in inv.list().withIndex()) {
                arr.rawset(i + 1, snap?.let { itemSnapshotToTable(it) } ?: Constants.NIL)
            }
            arr
        })
        t.rawset("find", fn { args ->
            ValueFactory.valueOf(inv.find(args.arg(1).toString()))
        })
        t.rawset("destroy", fn { args ->
            val slot = args.arg(1).toInteger().toInt()
            val count = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(inv.destroy(slot, count))
        })
        t.rawset("push", fn { args ->
            val slot = args.arg(1).toInteger().toInt()
            val targetTable = args.arg(2) as? LuaTable
            val target = targetTable?.let { invHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val count = if (args.count() >= 3 && !args.arg(3).isNil()) args.arg(3).toInteger().toInt() else 64
            val targetSlot = if (args.count() >= 4 && !args.arg(4).isNil()) args.arg(4).toInteger().toInt() else null
            ValueFactory.valueOf(inv.push(slot, target, count, targetSlot))
        })
        t.rawset("pull", fn { args ->
            val sourceTable = args.arg(1) as? LuaTable
            val source = sourceTable?.let { invHandles[it] } ?: return@fn ValueFactory.valueOf(0)
            val slot = args.arg(2).toInteger().toInt()
            val count = if (args.count() >= 3 && !args.arg(3).isNil()) args.arg(3).toInteger().toInt() else 64
            val targetSlot = if (args.count() >= 4 && !args.arg(4).isNil()) args.arg(4).toInteger().toInt() else null
            ValueFactory.valueOf(inv.pull(source, slot, count, targetSlot))
        })
        return t
    }

    private fun itemSnapshotToTable(s: InventoryPeripheral.ItemSnapshot): LuaTable {
        val o = LuaTable()
        o.rawset("id", ValueFactory.valueOf(s.id))
        o.rawset("count", ValueFactory.valueOf(s.count))
        return o
    }

    /**
     * WeakHashMap so once Lua collects a wrapped inventory table, we don't pin
     * the underlying [InventoryPeripheral]. Per-eval state — [eval] runs with a
     * fresh LuaState, so cross-eval pollution isn't a concern, but the weak
     * reference still earns its keep against long scripts that wrap many invs.
     */
    private val invHandles: java.util.WeakHashMap<LuaTable, InventoryPeripheral> = java.util.WeakHashMap()

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
