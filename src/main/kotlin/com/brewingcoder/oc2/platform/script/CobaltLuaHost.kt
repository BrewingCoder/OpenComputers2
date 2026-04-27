package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.ControlPlanePeripheral
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

    override fun eval(source: String, chunkName: String, env: ScriptEnv, scriptArgs: List<String>): ScriptResult {
        val state = LuaState()
        return try {
            // 0.9.6 changed standardGlobals to void; globals are retrieved via state.globals()
            CoreLibraries.standardGlobals(state)
            val globals = state.globals()
            globals.rawset("print", makePrintFunction(env.out))
            globals.rawset("fs", makeFsTable(env.mount, env.cwd))
            globals.rawset("peripheral", makePeripheralTable(env))
            globals.rawset("inventory", makeInventoryTable(env))
            globals.rawset("recipes", makeRecipesTable(env, state))
            globals.rawset("colors", makeColorsTable())
            globals.rawset("network", makeNetworkTable(env))
            globals.rawset("json", makeJsonTable())
            // CC:T-style os.* event API. Cobalt's `os` library exists already
            // (clock/time/date); we MERGE into the existing table rather than
            // overwriting it.
            installOsEventApi(globals, env)
            globals.rawset("sleep", fn { args ->
                val secs = args.arg(1).toDouble()
                val ms = (secs * 1000).toLong().coerceIn(0L, 60_000L)
                if (ms > 0) Thread.sleep(ms)
                Constants.NIL
            })
            installRequire(globals, state, env)
            val bytes = source.toByteArray(Charsets.UTF_8)
            val chunk = LoadState.load(state, ByteArrayInputStream(bytes), "@$chunkName", globals)
            // Forward CLI args to the script's main chunk as varargs — `local a = {...}`.
            // Also expose them on `arg` (Lua convention: `arg[1]`, `arg[2]`, ...) so scripts
            // that prefer that idiom over varargs can read them too.
            val varargs: Varargs = if (scriptArgs.isEmpty()) {
                Constants.NONE
            } else {
                val vs: List<LuaValue> = scriptArgs.map { ValueFactory.valueOf(it) }
                ValueFactory.varargsOf(vs)
            }
            if (scriptArgs.isNotEmpty()) {
                val argTable = LuaTable()
                for ((i, s) in scriptArgs.withIndex()) argTable.rawset(i + 1, ValueFactory.valueOf(s))
                globals.rawset("arg", argTable)
            }
            LuaThread.runMain(state, chunk, varargs)
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
     * Wrap a peripheral method body for `:` colon-call syntax. Lua's `m:foo(x)`
     * desugars to `m.foo(m, x)` so the receiver is implicitly arg(1). This
     * helper drops it before passing to [body], letting the body read the
     * user's args starting at `args.arg(1)`. Use this for ALL methods on
     * peripheral wrapper tables; use [fn] for top-level functions like
     * `peripheral.find` that are called with `.` syntax.
     */
    private inline fun method(self: LuaTable, crossinline body: (Varargs) -> LuaValue): VarArgFunction = object : VarArgFunction() {
        override fun invoke(state: LuaState, args: Varargs): Varargs = try {
            // Detect colon-call: when arg(1) is the receiver itself, strip it.
            // Otherwise leave args as-is so `m.foo(x)` still works.
            val effective = if (args.count() >= 1 && args.arg(1) === self) args.subargs(2) else args
            body(effective)
        } catch (e: StorageException) {
            throw LuaError(e.message ?: "fs error")
        } catch (e: PeripheralLease.PeripheralLockException) {
            throw LuaError(e.message ?: "peripheral locked")
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
            val name = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toString() else null
            val p = if (name != null) {
                env.listPeripherals(kind).firstOrNull { it.name == name }
            } else {
                env.findPeripheral(kind)
            } ?: return@fn Constants.NIL
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

    private fun wrapPeripheral(p: com.brewingcoder.oc2.platform.peripheral.Peripheral): LuaValue {
        // Order matters: BridgePeripheral wins over InventoryPeripheral so dual-natured
        // peripherals (Mek factory bridge that also exposes IItemHandler) get the bridge
        // surface AND the inventory surface (wrapBridge layers the latter on for free
        // when `p is InventoryPeripheral`). Otherwise the bridge handle would have only
        // size/list/push/pull and miss call/methods/target/protocol.
        val t = when (p) {
            is MonitorPeripheral -> wrapMonitor(p)
            is BridgePeripheral -> wrapBridge(p)
            is InventoryPeripheral -> wrapInventory(p)
            is CrafterPeripheral -> wrapCrafter(p)
            is MachineCrafterPeripheral -> wrapMachineCrafter(p)
            is RedstonePeripheral -> wrapRedstone(p)
            is FluidPeripheral -> wrapFluid(p)
            is EnergyPeripheral -> wrapEnergy(p)
            is BlockPeripheral -> wrapBlock(p)
            is ControlPlanePeripheral -> wrapControlPlane(p)
            else -> return Constants.NIL
        }
        // Stamp getLocation() onto every peripheral table — returns x, y, z as three values.
        t.rawset("getLocation", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val loc = p.location
                return ValueFactory.varargsOf(
                    ValueFactory.valueOf(loc.x),
                    ValueFactory.valueOf(loc.y),
                    ValueFactory.valueOf(loc.z),
                )
            }
        })
        // Stamp `data` — the free-form user text from the part config GUI.
        // Scripts read it as `peripheral.data`. Snapshot at wrap time; scripts
        // re-fetch via `peripheral.find()` to pick up live edits.
        t.rawset("data", ValueFactory.valueOf(p.data))
        return t
    }

    /**
     * Bridge surface: `methods()` → list of names, `call(name, ...args)` →
     * single value or list. Mirrors the contract on [BridgePeripheral]; the
     * underlying mod's value shapes are passed through via [toLuaValue].
     */
    private fun wrapBridge(b: BridgePeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(b.kind))
        t.rawset("name", ValueFactory.valueOf(b.name))
        t.rawset("protocol", ValueFactory.valueOf(b.protocol))
        t.rawset("target", ValueFactory.valueOf(b.target))
        t.rawset("methods", method(t) {
            val arr = LuaTable()
            for ((i, m) in b.methods().withIndex()) arr.rawset(i + 1, ValueFactory.valueOf(m))
            arr
        })
        t.rawset("call", method(t) { args ->
            val name = args.arg(1).toString()
            val callArgs = mutableListOf<Any?>()
            // Lua args are 1-indexed; arg 1 is the method name, 2..N are the call payload.
            // Resolve LuaTable args back to InventoryPeripheral instances first so a
            // bridge method like `push(slot, chest)` receives the actual peripheral
            // reference (not a stringified Map). Falls back to fromLuaValue for plain
            // tables / primitives.
            for (i in 2..args.count()) {
                val a = args.arg(i)
                val resolved = (a as? LuaTable)?.let { invHandles[it] }
                callArgs.add(resolved ?: fromLuaValue(a))
            }
            toLuaValue(b.call(name, callArgs))
        })
        t.rawset("describe", method(t) { toLuaValue(b.describe()) })
        // Dual-natured peripherals (e.g. Mek factory bridge that ALSO exposes an
        // IItemHandler) get the standard inventory surface layered on top of the
        // bridge surface — so scripts can do `chest:push(slot, smelter)` AND
        // `smelter:push(slot, chest)` without knowing the smelter is a bridge.
        if (b is InventoryPeripheral) decorateInventoryMethods(t, b)
        return t
    }

    private fun wrapBlock(b: BlockPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(b.kind))
        t.rawset("name", ValueFactory.valueOf(b.name))
        t.rawset("read", method(t) {
            val r = b.read() ?: return@method Constants.NIL
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
        t.rawset("harvest", method(t) { args ->
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
        t.rawset("tanks", method(t) { ValueFactory.valueOf(fl.tanks()) })
        t.rawset("getFluid", method(t) { args ->
            val s = fl.getFluid(args.arg(1).toInteger().toInt()) ?: return@method Constants.NIL
            fluidSnapshotToTable(s)
        })
        t.rawset("list", method(t) {
            val arr = LuaTable()
            for ((i, snap) in fl.list().withIndex()) {
                arr.rawset(i + 1, snap?.let { fluidSnapshotToTable(it) } ?: Constants.NIL)
            }
            arr
        })
        t.rawset("push", method(t) { args ->
            val tgt = (args.arg(1) as? LuaTable)?.let { fluidHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1000
            ValueFactory.valueOf(fl.push(tgt, amount))
        })
        t.rawset("pull", method(t) { args ->
            val src = (args.arg(1) as? LuaTable)?.let { fluidHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1000
            ValueFactory.valueOf(fl.pull(src, amount))
        })
        t.rawset("destroy", method(t) { args ->
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
        t.rawset("stored", method(t) { ValueFactory.valueOf(en.stored()) })
        t.rawset("capacity", method(t) { ValueFactory.valueOf(en.capacity()) })
        t.rawset("push", method(t) { args ->
            val tgt = (args.arg(1) as? LuaTable)?.let { energyHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(en.push(tgt, amount))
        })
        t.rawset("pull", method(t) { args ->
            val src = (args.arg(1) as? LuaTable)?.let { energyHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val amount = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(en.pull(src, amount))
        })
        t.rawset("destroy", method(t) { args ->
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
        t.rawset("getInput", method(t) { ValueFactory.valueOf(rs.getInput()) })
        t.rawset("getOutput", method(t) { ValueFactory.valueOf(rs.getOutput()) })
        t.rawset("setOutput", method(t) { args ->
            rs.setOutput(args.arg(1).toInteger().toInt())
            Constants.NIL
        })
        return t
    }

    /**
     * Tier-2 Control Plane handle. Lua surface mirrors [ControlPlanePeripheral]:
     *   cp:cycles()              → number (cumulative since boot)
     *   cp:isPowered()           → bool
     *   cp:togglePower()         → bool (new state)
     *   cp:consoleTail(n)        → list of strings (1-indexed Lua array)
     *   cp:consoleClear()        → nil
     *   cp:diskCapacity()        → number bytes
     *   cp:describe()            → string
     */
    private fun wrapControlPlane(cp: ControlPlanePeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(cp.kind))
        t.rawset("name", ValueFactory.valueOf(cp.name))
        t.rawset("cycles", method(t) { ValueFactory.valueOf(cp.cycles().toDouble()) })
        t.rawset("isPowered", method(t) { ValueFactory.valueOf(cp.isPowered()) })
        t.rawset("togglePower", method(t) { ValueFactory.valueOf(cp.togglePower()) })
        t.rawset("consoleTail", method(t) { args ->
            val n = if (args.count() >= 1) args.arg(1).toInteger().toInt() else 8
            val lines = cp.consoleTail(n)
            val arr = LuaTable()
            for ((i, line) in lines.withIndex()) arr.rawset(i + 1, ValueFactory.valueOf(line))
            arr
        })
        t.rawset("consoleClear", method(t) { cp.consoleClear(); Constants.NIL })
        t.rawset("diskCapacity", method(t) { ValueFactory.valueOf(cp.diskCapacity().toDouble()) })
        t.rawset("describe", method(t) { ValueFactory.valueOf(cp.describe()) })
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
     * Install the CC:T-style `os.*` event API onto the `os` table. CC:T's fork of
     * Cobalt strips Lua's stock `OsLib` entirely, so we also install the clock /
     * time / epoch primitives scripts reach for when building counters and HUDs.
     *
     * Timer scheduling is local to this script's event queue: the worker thread
     * sleeps until the timer fires (no server-tick coupling needed for Phase 1).
     */
    private fun installOsEventApi(globals: LuaTable, env: ScriptEnv) {
        val os = globals.rawget("os") as? LuaTable ?: LuaTable().also { globals.rawset("os", it) }
        val nextTimerId = java.util.concurrent.atomic.AtomicInteger(1)
        val scriptStartNanos = System.nanoTime()

        // os.clock()  →  seconds since this script started (monotonic, double)
        os.rawset("clock", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs =
                ValueFactory.valueOf((System.nanoTime() - scriptStartNanos) / 1_000_000_000.0)
        })

        // os.time()  →  Unix epoch seconds (double). CC:T returns in-game time by
        // default; we return wall-clock seconds because (a) we don't have a shared
        // in-game clock in Phase 1 and (b) wall-clock is what counter/rate code
        // actually wants. Stable behavior; revisit if we add an in-game clock API.
        os.rawset("time", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs =
                ValueFactory.valueOf(System.currentTimeMillis() / 1000.0)
        })

        // os.epoch([locale])  →  Unix epoch milliseconds. `locale` accepted for
        // CC:T compat ("utc" / "local" / "ingame") but ignored — always wall-clock ms.
        os.rawset("epoch", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs =
                ValueFactory.valueOf(System.currentTimeMillis().toDouble())
        })

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

    /**
     * Install a `require(name)` global. Searches `/lib/?.lua` then `/rom/lib/?.lua`
     * — user files under `/lib/` shadow ROM copies. Loaded modules run in the same
     * globals as the caller (Lua's stock behavior); the chunk's return value is
     * cached so subsequent `require("foo")` calls return the same table.
     *
     * Per-eval cache: fresh on every script run, matching the LuaState lifecycle.
     * No circular-require detection — a self-requiring module will stack-overflow.
     */
    private fun installRequire(globals: LuaTable, state: LuaState, env: ScriptEnv) {
        val cache = mutableMapOf<String, LuaValue>()
        val searchPaths = listOf("lib/%s.lua", "rom/lib/%s.lua")

        globals.rawset("require", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val name = args.arg(1).toString()
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
                    throw LuaError("module '$name' not found; searched: ${tried.joinToString(";")}")
                }

                val chunk = try {
                    LoadState.load(state, ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)), "@$name", globals)
                } catch (e: CompileException) {
                    throw LuaError("compile error in module '$name': ${e.message}")
                }
                val result: LuaValue = try {
                    org.squiddev.cobalt.function.Dispatch.call(state, chunk)
                } catch (e: LuaError) {
                    throw LuaError("error loading module '$name': ${e.message}")
                }
                // Lua convention: modules that don't return anything still cache as `true`
                // so `require("foo")` is a no-op second time instead of re-running.
                val value = if (result.isNil()) Constants.TRUE else result
                cache[name] = value
                return value
            }
        })
    }

    private fun readMountFile(mount: com.brewingcoder.oc2.platform.storage.WritableMount, path: String): String {
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

    /** Convert a Kotlin event-arg back to a LuaValue. Supports the wire types we accept. */
    private fun toLuaValue(v: Any?): LuaValue = when (v) {
        null -> Constants.NIL
        is Boolean -> ValueFactory.valueOf(v)
        is Int -> ValueFactory.valueOf(v.toDouble())
        is Long -> ValueFactory.valueOf(v.toDouble())
        is Float -> ValueFactory.valueOf(v.toDouble())
        is Double -> ValueFactory.valueOf(v)
        is String -> ValueFactory.valueOf(v)
        is List<*> -> {
            val arr = LuaTable()
            for ((i, e) in v.withIndex()) arr.rawset(i + 1, toLuaValue(e))
            arr
        }
        is Map<*, *> -> {
            val tbl = LuaTable()
            for ((k, vv) in v) tbl.rawset(k.toString(), toLuaValue(vv))
            tbl
        }
        else -> ValueFactory.valueOf(v.toString())
    }

    /**
     * Inverse of [toLuaValue] — extract a Java value from a Lua arg.
     *
     * **Numbers are always [Double]**, never auto-narrowed to Int. This matches
     * CC:T's `Object[]` convention on bridge/peripheral calls: mod peripherals
     * (ZeroCore's `ComputerPeripheral`, CC:T `IPeripheral`) dispatch reflectively
     * and cast each arg to `Double`, so sending an `Integer` triggers a silent
     * `ClassCastException` → null return (looks like the call "succeeded" but
     * did nothing). Regression: rod-setters on ER2 reactors silently no-op'd
     * with `ok=true` until this was fixed.
     *
     * **Tables become `Map<Any, Any?>`** with Lua's native keys preserved
     * (numeric keys kept as Double). CC:T does the same — peripheral APIs that
     * take an "array of levels" expect a map with Double keys 1..N, not a Java
     * array or a stringified table handle.
     */
    private fun fromLuaValue(v: LuaValue): Any? = when {
        v.isNil() -> null
        v.type() == Constants.TBOOLEAN -> v.toBoolean()
        v.isNumber() -> v.toDouble()
        v.type() == Constants.TSTRING -> v.toString()
        v.type() == Constants.TTABLE -> luaTableToMap(v as LuaTable)
        else -> v.toString()
    }

    private fun luaTableToMap(t: LuaTable): Map<Any, Any?> {
        val out = linkedMapOf<Any, Any?>()
        var k: LuaValue = Constants.NIL
        while (true) {
            val pair = t.next(k)
            val nextKey = pair.arg(1)
            if (nextKey.isNil()) break
            val jKey: Any = if (nextKey.isNumber()) nextKey.toDouble() else nextKey.toString()
            out[jKey] = fromLuaValue(pair.arg(2))
            k = nextKey
        }
        return out
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
        t.rawset("write", method(t) { args ->
            mon.write(args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("println", method(t) { args ->
            mon.println(args.arg(1).toString())
            Constants.NIL
        })
        t.rawset("setCursorPos", method(t) { args ->
            mon.setCursorPos(args.arg(1).toInteger().toInt(), args.arg(2).toInteger().toInt())
            Constants.NIL
        })
        t.rawset("getCursorPos", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val (col, row) = mon.getCursorPos()
                return ValueFactory.varargsOf(ValueFactory.valueOf(col), ValueFactory.valueOf(row))
            }
        })
        t.rawset("clear", method(t) {
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
        val setFg: VarArgFunction = method(t) { args ->
            mon.setForegroundColor(args.arg(1).toDouble().toLong().toInt())
            Constants.NIL
        }
        t.rawset("setForegroundColor", setFg)
        t.rawset("setTextColor", setFg)
        t.rawset("setBackgroundColor", method(t) { args ->
            mon.setBackgroundColor(args.arg(1).toDouble().toLong().toInt())
            Constants.NIL
        })
        t.rawset("pollTouches", method(t) {
            val events = mon.pollTouches()
            val arr = LuaTable()
            for ((i, ev) in events.withIndex()) {
                val e = LuaTable()
                e.rawset("col", ValueFactory.valueOf(ev.col))
                e.rawset("row", ValueFactory.valueOf(ev.row))
                e.rawset("px", ValueFactory.valueOf(ev.px))
                e.rawset("py", ValueFactory.valueOf(ev.py))
                e.rawset("player", ValueFactory.valueOf(ev.playerName))
                arr.rawset(i + 1, e)
            }
            arr
        })

        // ---- HD pixel-buffer API ----
        t.rawset("getPixelSize", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val (w, h) = mon.getPixelSize()
                return ValueFactory.varargsOf(ValueFactory.valueOf(w), ValueFactory.valueOf(h))
            }
        })
        t.rawset("clearPixels", method(t) { args ->
            mon.clearPixels(args.arg(1).toDouble().toLong().toInt())
            Constants.NIL
        })
        t.rawset("setPixel", method(t) { args ->
            mon.setPixel(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawRect", method(t) { args ->
            mon.drawRect(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawRectOutline", method(t) { args ->
            val thickness = if (args.count() >= 6 && !args.arg(6).isNil()) args.arg(6).toInteger().toInt() else 1
            mon.drawRectOutline(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
                thickness,
            )
            Constants.NIL
        })
        t.rawset("drawLine", method(t) { args ->
            mon.drawLine(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawGradientV", method(t) { args ->
            mon.drawGradientV(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
                args.arg(6).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("fillCircle", method(t) { args ->
            mon.fillCircle(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("fillEllipse", method(t) { args ->
            mon.fillEllipse(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawArc", method(t) { args ->
            mon.drawArc(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toInteger().toInt(),
                args.arg(6).toInteger().toInt(),
                args.arg(7).toInteger().toInt(),
                args.arg(8).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawItem", method(t) { args ->
            mon.drawItem(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toString(),
            )
            Constants.NIL
        })
        t.rawset("drawFluid", method(t) { args ->
            mon.drawFluid(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toString(),
            )
            Constants.NIL
        })
        t.rawset("drawChemical", method(t) { args ->
            mon.drawChemical(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
                args.arg(5).toString(),
            )
            Constants.NIL
        })
        t.rawset("clearIcons", method(t) { _ ->
            mon.clearIcons()
            Constants.NIL
        })

        // ---- Engine helpers (cell-geometry, ARGB math, text sugar, small font) ----
        // Lifted out of ui_v1 libraries so scripts don't each reimplement these.
        t.rawset("getCellMetrics", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                val m = mon.getCellMetrics()
                return ValueFactory.varargsOf(
                    ValueFactory.valueOf(m.cols),
                    ValueFactory.valueOf(m.rows),
                    ValueFactory.valueOf(m.pxPerCol),
                    ValueFactory.valueOf(m.pxPerRow),
                )
            }
        })
        t.rawset("snapCellRect", object : VarArgFunction() {
            override fun invoke(state: LuaState, args: Varargs): Varargs {
                // Lua calling convention: m:snapCellRect(y, h) -> self is arg 1
                val offset = if (args.arg(1) === t) 1 else 0
                val y = args.arg(1 + offset).toInteger().toInt()
                val h = args.arg(2 + offset).toInteger().toInt()
                val r = mon.snapCellRect(y, h)
                return ValueFactory.varargsOf(
                    ValueFactory.valueOf(r.snappedY),
                    ValueFactory.valueOf(r.snappedH),
                    ValueFactory.valueOf(r.textRow),
                )
            }
        })
        t.rawset("argb", method(t) { args ->
            ValueFactory.valueOf(mon.argb(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                args.arg(4).toInteger().toInt(),
            ).toLong().and(0xFFFFFFFFL).toDouble())
        })
        t.rawset("lighten", method(t) { args ->
            ValueFactory.valueOf(mon.lighten(
                args.arg(1).toDouble().toLong().toInt(),
                args.arg(2).toInteger().toInt(),
            ).toLong().and(0xFFFFFFFFL).toDouble())
        })
        t.rawset("dim", method(t) { args ->
            ValueFactory.valueOf(mon.dim(
                args.arg(1).toDouble().toLong().toInt(),
            ).toLong().and(0xFFFFFFFFL).toDouble())
        })
        t.rawset("drawText", method(t) { args ->
            mon.drawText(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toString(),
                args.arg(4).toDouble().toLong().toInt(),
                args.arg(5).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("fillText", method(t) { args ->
            val ch = args.arg(4).toString().firstOrNull() ?: ' '
            mon.fillText(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toInteger().toInt(),
                ch,
                args.arg(5).toDouble().toLong().toInt(),
                args.arg(6).toDouble().toLong().toInt(),
            )
            Constants.NIL
        })
        t.rawset("drawSmallText", method(t) { args ->
            mon.drawSmallText(
                args.arg(1).toInteger().toInt(),
                args.arg(2).toInteger().toInt(),
                args.arg(3).toString(),
                args.arg(4).toDouble().toLong().toInt(),
            )
            Constants.NIL
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
        decorateInventoryMethods(t, inv)
        return t
    }

    /**
     * Crafter surface: `size()` → Int, `list()` → array of card snapshots,
     * `craft(slot, count, source)` → number of crafts that completed. The
     * source must be an InventoryPeripheral table produced by
     * `peripheral.find` / `inventory.list` etc. — resolved via [invHandles].
     *
     * Card snapshots: { slot, output (string|nil), outputCount }.
     * Empty slots come back as nil.
     */
    private fun wrapCrafter(c: CrafterPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(c.kind))
        t.rawset("name", ValueFactory.valueOf(c.name))
        t.rawset("size", method(t) { ValueFactory.valueOf(c.size()) })
        t.rawset("list", method(t) {
            val arr = LuaTable()
            for ((i, snap) in c.list().withIndex()) {
                if (snap == null) {
                    arr.rawset(i + 1, Constants.NIL)
                } else {
                    val o = LuaTable()
                    o.rawset("slot", ValueFactory.valueOf(snap.slot))
                    o.rawset("output", snap.output?.let { ValueFactory.valueOf(it) } ?: Constants.NIL)
                    o.rawset("outputCount", ValueFactory.valueOf(snap.outputCount))
                    arr.rawset(i + 1, o)
                }
            }
            arr
        })
        t.rawset("craft", method(t) { args ->
            val slot = args.arg(1).toInteger().toInt()
            val count = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1
            val sourceTable = args.arg(3) as? LuaTable
            val source = sourceTable?.let { invHandles[it] }
                ?: throw LuaError("crafter.craft: source must be an inventory peripheral handle")
            ValueFactory.valueOf(c.craft(slot, count, source))
        })
        t.rawset("adjacentBlock", method(t) {
            c.adjacentBlock()?.let { ValueFactory.valueOf(it) } ?: Constants.NIL
        })
        return t
    }

    private fun wrapMachineCrafter(c: MachineCrafterPeripheral): LuaTable {
        val t = LuaTable()
        t.rawset("kind", ValueFactory.valueOf(c.kind))
        t.rawset("name", ValueFactory.valueOf(c.name))
        t.rawset("size", method(t) { ValueFactory.valueOf(c.size()) })
        t.rawset("list", method(t) {
            val arr = LuaTable()
            for ((i, snap) in c.list().withIndex()) {
                if (snap == null) {
                    arr.rawset(i + 1, Constants.NIL)
                } else {
                    val o = LuaTable()
                    o.rawset("slot", ValueFactory.valueOf(snap.slot))
                    o.rawset("output", snap.output?.let { ValueFactory.valueOf(it) } ?: Constants.NIL)
                    o.rawset("outputCount", ValueFactory.valueOf(snap.outputCount))
                    o.rawset("fluidIn", snap.fluidIn?.let { ValueFactory.valueOf(it) } ?: Constants.NIL)
                    o.rawset("fluidInMb", ValueFactory.valueOf(snap.fluidInMb))
                    o.rawset("blocking", ValueFactory.valueOf(snap.blocking))
                    arr.rawset(i + 1, o)
                }
            }
            arr
        })
        t.rawset("craft", method(t) { args ->
            val slot = args.arg(1).toInteger().toInt()
            val count = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1
            val sourceTable = args.arg(3) as? LuaTable
            val source = sourceTable?.let { invHandles[it] }
                ?: throw LuaError("machine_crafter.craft: source must be an inventory peripheral handle")
            val fluidArg = args.arg(4)
            val fluidSource = if (fluidArg.isNil()) null
                              else (fluidArg as? LuaTable)?.let { fluidHandles[it] }
            ValueFactory.valueOf(c.craft(slot, count, source, fluidSource))
        })
        t.rawset("adjacentBlock", method(t) {
            c.adjacentBlock()?.let { ValueFactory.valueOf(it) } ?: Constants.NIL
        })
        return t
    }

    /**
     * Stamp the InventoryPeripheral surface (size/getItem/list/find/destroy/
     * push/pull) onto an existing LuaTable. Used by [wrapInventory] AND by
     * [wrapBridge] when the underlying peripheral implements both interfaces
     * (e.g. a Mek factory bridge whose IItemHandler is the machine inventory).
     *
     * Also registers `t -> inv` in [invHandles] so cross-peripheral push/pull
     * can resolve the "other end" back to its native peripheral.
     */
    private fun decorateInventoryMethods(t: LuaTable, inv: InventoryPeripheral) {
        invHandles[t] = inv
        t.rawset("size", method(t) { ValueFactory.valueOf(inv.size()) })
        t.rawset("getItem", method(t) { args ->
            val s = inv.getItem(args.arg(1).toInteger().toInt()) ?: return@method Constants.NIL
            itemSnapshotToTable(s)
        })
        t.rawset("list", method(t) {
            val arr = LuaTable()
            for ((i, snap) in inv.list().withIndex()) {
                arr.rawset(i + 1, snap?.let { itemSnapshotToTable(it) } ?: Constants.NIL)
            }
            arr
        })
        t.rawset("find", method(t) { args ->
            ValueFactory.valueOf(inv.find(args.arg(1).toString()))
        })
        t.rawset("destroy", method(t) { args ->
            val slot = args.arg(1).toInteger().toInt()
            val count = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else Int.MAX_VALUE
            ValueFactory.valueOf(inv.destroy(slot, count))
        })
        t.rawset("push", method(t) { args ->
            val slot = args.arg(1).toInteger().toInt()
            val targetTable = args.arg(2) as? LuaTable
            val target = targetTable?.let { invHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val count = if (args.count() >= 3 && !args.arg(3).isNil()) args.arg(3).toInteger().toInt() else 64
            val targetSlot = if (args.count() >= 4 && !args.arg(4).isNil()) args.arg(4).toInteger().toInt() else null
            ValueFactory.valueOf(inv.push(slot, target, count, targetSlot))
        })
        t.rawset("pull", method(t) { args ->
            val sourceTable = args.arg(1) as? LuaTable
            val source = sourceTable?.let { invHandles[it] } ?: return@method ValueFactory.valueOf(0)
            val slot = args.arg(2).toInteger().toInt()
            val count = if (args.count() >= 3 && !args.arg(3).isNil()) args.arg(3).toInteger().toInt() else 64
            val targetSlot = if (args.count() >= 4 && !args.arg(4).isNil()) args.arg(4).toInteger().toInt() else null
            ValueFactory.valueOf(inv.pull(source, slot, count, targetSlot))
        })
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

    /**
     * Build the `inventory` global — thin Lua-side projection of [InventoryApi].
     *
     *   `inventory.list()`                          → array of inventory handles
     *   `inventory.find([name])`                    → handle or nil
     *   `inventory.get(id, count, target [, from])` → moved
     *   `inventory.drain(machine [, id [, to]])`    → moved
     *   `inventory.put(id, count, source, target)`  → moved
     *
     * `target`/`source`/`machine` arguments are inventory handles produced by
     * `peripheral.find` / `inventory.list` etc. — resolved back to the native
     * impl via [invHandles]. `from`/`to` accept arrays of handles (Lua tables).
     */
    private fun makeInventoryTable(env: ScriptEnv): LuaTable {
        val api = InventoryApi(env)
        val t = LuaTable()
        t.rawset("list", fn {
            val arr = LuaTable()
            for ((i, p) in api.list().withIndex()) {
                val wrapped = wrapPeripheral(p)
                if (wrapped !== Constants.NIL) arr.rawset(i + 1, wrapped)
            }
            arr
        })
        t.rawset("find", fn { args ->
            val name = if (args.count() >= 1 && !args.arg(1).isNil()) args.arg(1).toString() else null
            val p = api.find(name) ?: return@fn Constants.NIL
            wrapPeripheral(p)
        })
        t.rawset("get", fn { args ->
            val itemId = args.arg(1).toString()
            val count = args.arg(2).toInteger().toInt()
            val target = (args.arg(3) as? LuaTable)?.let { invHandles[it] }
                ?: return@fn ValueFactory.valueOf(0)
            // `from` nil (or missing) → use the API's default source ranking. Only
            // a present-and-valid array overrides.
            val from = readInventoryArray(args.arg(4))
            ValueFactory.valueOf(api.get(itemId, count, target, from))
        })
        t.rawset("drain", fn { args ->
            val machine = (args.arg(1) as? LuaTable)?.let { invHandles[it] }
                ?: return@fn ValueFactory.valueOf(0)
            val itemId = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toString() else null
            val to = readInventoryArray(args.arg(3))
            ValueFactory.valueOf(api.drain(machine, itemId, to))
        })
        t.rawset("put", fn { args ->
            val itemId = args.arg(1).toString()
            val count = args.arg(2).toInteger().toInt()
            val source = (args.arg(3) as? LuaTable)?.let { invHandles[it] }
                ?: return@fn ValueFactory.valueOf(0)
            val target = (args.arg(4) as? LuaTable)?.let { invHandles[it] }
                ?: return@fn ValueFactory.valueOf(0)
            ValueFactory.valueOf(api.put(itemId, count, source, target))
        })
        return t
    }

    /** Read a Lua array of inventory handles into a List. Returns null if a non-handle slipped in. */
    private fun readInventoryArray(v: LuaValue): List<InventoryPeripheral>? {
        if (v.isNil()) return null
        val tbl = v as? LuaTable ?: return null
        val out = mutableListOf<InventoryPeripheral>()
        var i = 1
        while (true) {
            val entry = tbl.rawget(i)
            if (entry.isNil()) break
            val inv = (entry as? LuaTable)?.let { invHandles[it] } ?: return null
            out.add(inv)
            i++
        }
        return out
    }

    /**
     * Build `recipes` as a callable table:
     *   - `recipes("minecraft:iron_ingot")` (or equivalent `recipes.query(id)`) — query
     *     bridge producers/consumers/inputs/outputs (existing behavior).
     *   - `recipes.craft(itemId [, count])` — auto-discovery craft: walks installed
     *     `machine_crafter` / `crafter` peripherals, finds the card stamping [itemId],
     *     auto-sources ingredients from any inventory on the channel, returns the
     *     expected number of items produced (rounded up to whole cycles).
     */
    private fun makeRecipesTable(env: ScriptEnv, state: LuaState): LuaTable {
        val runQuery: (String) -> LuaTable = { itemId ->
            val q = RecipeApi(env).query(itemId)
            val t = LuaTable()
            t.rawset("itemId", ValueFactory.valueOf(itemId))
            val producers = LuaTable()
            for ((i, b) in q.producers.withIndex()) producers.rawset(i + 1, wrapPeripheral(b))
            t.rawset("producers", producers)
            val consumers = LuaTable()
            for ((i, b) in q.consumers.withIndex()) consumers.rawset(i + 1, wrapPeripheral(b))
            t.rawset("consumers", consumers)
            val inputs = LuaTable()
            for ((i, id) in q.inputs.withIndex()) inputs.rawset(i + 1, ValueFactory.valueOf(id))
            t.rawset("inputs", inputs)
            val outputs = LuaTable()
            for ((i, id) in q.outputs.withIndex()) outputs.rawset(i + 1, ValueFactory.valueOf(id))
            t.rawset("outputs", outputs)
            t
        }

        val ns = LuaTable()
        ns.rawset("query", fn { args -> runQuery(args.arg(1).toString()) })
        ns.rawset("craft", fn { args ->
            val itemId = args.arg(1).toString()
            val count = if (args.count() >= 2 && !args.arg(2).isNil()) args.arg(2).toInteger().toInt() else 1
            ValueFactory.valueOf(RecipeCrafter(env).craft(itemId, count))
        })

        // __call metamethod preserves the legacy `recipes("id")` query syntax.
        // Cobalt invokes __call with `(self, ...)`, so the user's id is arg(2).
        val mt = LuaTable()
        mt.rawset("__call", fn { args -> runQuery(args.arg(2).toString()) })
        ns.setMetatable(state, mt)
        return ns
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
