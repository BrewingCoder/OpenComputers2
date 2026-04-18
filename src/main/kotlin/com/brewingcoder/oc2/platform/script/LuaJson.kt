package com.brewingcoder.oc2.platform.script

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.squiddev.cobalt.Constants
import org.squiddev.cobalt.LuaError
import org.squiddev.cobalt.LuaTable
import org.squiddev.cobalt.LuaValue
import org.squiddev.cobalt.ValueFactory

/**
 * Gson bridge for the Lua `json` global. JS scripts use Rhino's built-in
 * `JSON` object instead — Lua has no native equivalent, so this fills the gap.
 *
 * Encoding rules (Lua → JSON):
 *   - LuaTable with all integer keys 1..n (and at least one element) → JSON array
 *   - LuaTable otherwise (or empty) → JSON object with string keys
 *   - LuaString → JSON string
 *   - LuaNumber → JSON number
 *   - LuaBoolean → JSON true/false
 *   - LuaNil → JSON null
 *
 * Decoding rules (JSON → Lua):
 *   - JSON object → LuaTable with string keys
 *   - JSON array → LuaTable with integer keys 1..n (Lua convention)
 *   - JSON string/number/bool/null → corresponding LuaValue
 *
 * The empty-table ambiguity (Lua can't distinguish `[]` from `{}`) is resolved
 * by encoding empty tables as `{}` — same call as cjson.lua, same as PUC-Rio Lua.
 */
internal object LuaJson {
    private val gson: Gson = Gson()

    fun encode(v: LuaValue): String = gson.toJson(toJson(v))

    fun decode(text: String): LuaValue = fromJson(gson.fromJson(text, JsonElement::class.java))

    private fun toJson(v: LuaValue): JsonElement {
        if (v.isNil()) return JsonNull.INSTANCE
        if (v.type() == Constants.TBOOLEAN) return JsonPrimitive(v.toBoolean())
        if (v.isNumber()) {
            val d = v.toDouble()
            return if (d.isFinite() && d == d.toLong().toDouble()) JsonPrimitive(d.toLong())
            else JsonPrimitive(d)
        }
        if (v.type() == Constants.TSTRING) return JsonPrimitive(v.toString())
        if (v is LuaTable) return tableToJson(v)
        // Functions / userdata / threads aren't representable in JSON; serialize as null.
        return JsonNull.INSTANCE
    }

    /** Decide array vs object by examining keys; matches cjson and dkjson behavior. */
    private fun tableToJson(t: LuaTable): JsonElement {
        val n = t.length()
        if (n > 0 && isArrayLike(t, n)) {
            val arr = JsonArray(n)
            for (i in 1..n) arr.add(toJson(t.rawget(i)))
            return arr
        }
        val obj = JsonObject()
        var k: LuaValue = Constants.NIL
        while (true) {
            val pair = t.next(k)
            val nextK = pair.arg(1)
            if (nextK.isNil()) break
            k = nextK
            obj.add(nextK.toString(), toJson(pair.arg(2)))
        }
        return obj
    }

    private fun isArrayLike(t: LuaTable, length: Int): Boolean {
        for (i in 1..length) {
            if (t.rawget(i).isNil()) return false
        }
        var k: LuaValue = Constants.NIL
        var seen = 0
        while (true) {
            val pair = t.next(k)
            val nextK = pair.arg(1)
            if (nextK.isNil()) break
            k = nextK
            seen++
            if (!nextK.isNumber()) return false
            val d = nextK.toDouble()
            if (d != d.toLong().toDouble()) return false
            val idx = d.toLong()
            if (idx < 1 || idx > length) return false
        }
        return seen == length
    }

    private fun fromJson(e: JsonElement?): LuaValue {
        if (e == null || e.isJsonNull) return Constants.NIL
        if (e.isJsonPrimitive) {
            val p = e.asJsonPrimitive
            return when {
                p.isBoolean -> ValueFactory.valueOf(p.asBoolean)
                p.isNumber -> {
                    val n = p.asNumber
                    val d = n.toDouble()
                    if (d.isFinite() && d == d.toLong().toDouble()) ValueFactory.valueOf(d)
                    else ValueFactory.valueOf(d)
                }
                p.isString -> ValueFactory.valueOf(p.asString)
                else -> throw LuaError("unknown json primitive")
            }
        }
        if (e.isJsonArray) {
            val arr = e.asJsonArray
            val t = LuaTable()
            for ((i, item) in arr.withIndex()) t.rawset(i + 1, fromJson(item))
            return t
        }
        if (e.isJsonObject) {
            val o = e.asJsonObject
            val t = LuaTable()
            for ((k, v) in o.entrySet()) t.rawset(k, fromJson(v))
            return t
        }
        throw LuaError("unknown json node")
    }
}
