package com.brewingcoder.oc2.platform.peripheral

/**
 * Generic protocol-agnostic peripheral. Surfaces methods discovered on the
 * adjacent BlockEntity by whichever ProtocolAdapter claimed it (CC's
 * IPeripheral, ZeroCore's IComputerPort, NeoForge cap fallback, ...).
 *
 * Scripts use it like:
 *   ```lua
 *   local r = peripheral.find("bridge")
 *   for _, m in ipairs(r:methods()) do print(m) end
 *   local stored = r:call("getEnergyStored")
 *   r:call("setControlRodLevel", 0, 50)
 *   ```
 *
 * Method names + value shapes are mod-specific — the bridge does no
 * normalization. That's by design: scripts are talking to the underlying
 * mod's API, OC2 is just the courier.
 */
interface BridgePeripheral : Peripheral {
    override val kind: String get() = "bridge"

    /** Display label — defaults to `bridge_<face>_<adapterId>` like other parts. */
    val name: String

    /**
     * Which adapter is currently servicing the discovered BE.
     * Examples: `"cc"`, `"zerocore"`, `"caps"`, `"none"`. `"none"` means the
     * adapter side knows nothing scriptable lives there — call() will return
     * null and methods() will be empty.
     */
    val protocol: String

    /**
     * Identifier for the underlying object the bridge connected to — typically
     * the BE class FQN or the namespaced block id. Empty string for "none".
     * Diagnostic only; scripts should not pattern-match on it.
     */
    val target: String

    /** Method names available on the underlying peripheral. Empty when [protocol] = "none". */
    fun methods(): List<String>

    /**
     * Invoke a method by name. Args + return value are passed through as-is
     * from the underlying peripheral; supported value shapes are primitives
     * (Boolean/Int/Long/Double/String), [List], [Map], or null.
     *
     * Returns null when:
     *  - the method doesn't exist
     *  - the underlying peripheral threw (logged and swallowed)
     *  - the method returns void
     *
     * Single-value returns come back as that value; multi-value returns come
     * back as a [List]. Matches how Lua sees multi-return.
     */
    fun call(method: String, args: List<Any?>): Any?

    /**
     * Self-introspection. Designed for the developer flow:
     *
     *   ```lua
     *   local r = peripheral.find("bridge")
     *   print(json.encode(r:describe()))
     *   ```
     *
     * Returns a map with:
     *   - `protocol` — adapter id (`"zerocore"`, `"cc"`, `"none"`, ...)
     *   - `name` — the part's label
     *   - `target` — underlying BE class / block id (diagnostic)
     *   - `methods` — list of method names
     *   - `state` — Map of method-name → return value, populated by probing
     *     every method with NO args. Methods that need args (or otherwise
     *     errored) are captured in `errors` instead.
     *   - `errors` — Map of method-name → error message for probes that failed.
     *
     * The default impl probes every method via [call]. Adapters with cheaper
     * introspection paths can override.
     */
    fun describe(): Map<String, Any?> {
        val methodNames = methods()
        val state = mutableMapOf<String, Any?>()
        val errors = mutableMapOf<String, Any?>()
        for (m in methodNames) {
            try {
                val v = call(m, emptyList())
                if (v != null) state[m] = v else errors[m] = "returned nil (likely needs args or is a setter)"
            } catch (t: Throwable) {
                errors[m] = t.message ?: t.javaClass.simpleName
            }
        }
        return mapOf(
            "protocol" to protocol,
            "name" to name,
            "target" to target,
            "methods" to methodNames,
            "state" to state,
            "errors" to errors,
        )
    }
}
