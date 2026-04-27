package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptEnv
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RecipeApi] / [RecipeQuery]. Uses a fake [BridgePeripheral]
 * impl that implements the same `canConsume`/`canProduce`/`getInputsFor`/
 * `getOutputFor` contract Mek + NeoForgeCapAdapter expose at runtime.
 */
class RecipeApiTest {

    private object SilentOut : ShellOutput {
        override fun println(line: String) {}
        override fun clear() {}
    }

    /**
     * Fake bridge backed by a tiny recipe table:
     *   `recipes[outputId] = list of input id sets`
     * `canConsume(id)` is true if any recipe lists `id` as input;
     * `canProduce(id)` is true if `recipes[id]` exists.
     */
    private class FakeBridge(
        override val name: String,
        private val recipes: Map<String, List<List<String>>>,
    ) : BridgePeripheral {
        override val location: Position = Position.ORIGIN
        override val protocol: String = "test"
        override val target: String = "test:fake_bridge"

        override fun methods(): List<String> =
            listOf("canConsume", "canProduce", "getInputsFor", "getOutputFor")

        override fun call(method: String, args: List<Any?>): Any? {
            val id = args.firstOrNull() as? String
            return when (method) {
                "canConsume" -> id != null && recipes.values.any { variants ->
                    variants.any { it.contains(id) }
                }
                "canProduce" -> id != null && recipes.containsKey(id)
                "getOutputFor" -> {
                    if (id == null) return null
                    val match = recipes.entries.firstOrNull { (_, variants) ->
                        variants.any { it.contains(id) }
                    } ?: return null
                    mapOf("id" to match.key, "count" to 1)
                }
                "getInputsFor" -> {
                    if (id == null) return emptyList<Any>()
                    (recipes[id] ?: return emptyList<Any>()).map { inputs ->
                        mapOf("inputs" to inputs, "count" to 1)
                    }
                }
                else -> null
            }
        }
    }

    private class FakeEnv(private val bridges: List<BridgePeripheral>) : ScriptEnv {
        override val mount: WritableMount = InMemoryMount(4096)
        override val cwd: String = ""
        override val out: ShellOutput = SilentOut
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "bridge") bridges.firstOrNull() else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "bridge") bridges else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun smelter(name: String) = FakeBridge(
        name,
        mapOf(
            "minecraft:iron_ingot" to listOf(listOf("minecraft:raw_iron", "minecraft:iron_ore")),
            "minecraft:copper_ingot" to listOf(listOf("minecraft:raw_copper", "minecraft:copper_ore")),
        ),
    )

    private fun crusher(name: String) = FakeBridge(
        name,
        mapOf(
            "minecraft:raw_iron" to listOf(listOf("minecraft:iron_ore")),
            "minecraft:raw_copper" to listOf(listOf("minecraft:copper_ore")),
        ),
    )

    @Test
    fun `producers includes every bridge that canProduce the item`() {
        val sm = smelter("smelter")
        val cr = crusher("crusher")
        val api = RecipeApi(FakeEnv(listOf(sm, cr)))

        val q = api.query("minecraft:iron_ingot")
        q.producers.map { it.name } shouldBe listOf("smelter")
    }

    @Test
    fun `consumers includes every bridge that canConsume the item`() {
        val sm = smelter("smelter")
        val cr = crusher("crusher")
        val api = RecipeApi(FakeEnv(listOf(sm, cr)))

        // raw_iron is consumed by the smelter; iron_ore is consumed by both.
        api.query("minecraft:raw_iron").consumers.map { it.name } shouldBe listOf("smelter")
        api.query("minecraft:iron_ore").consumers.map { it.name } shouldBe listOf("smelter", "crusher")
    }

    @Test
    fun `inputs aggregates input ids across all producers`() {
        val sm = smelter("smelter")
        val api = RecipeApi(FakeEnv(listOf(sm)))

        val q = api.query("minecraft:iron_ingot")
        q.inputs shouldBe listOf("minecraft:raw_iron", "minecraft:iron_ore")
    }

    @Test
    fun `outputs aggregates output ids across all consumers`() {
        val sm = smelter("smelter")
        val cr = crusher("crusher")
        val api = RecipeApi(FakeEnv(listOf(sm, cr)))

        // Feeding iron_ore into the network: crusher emits raw_iron, smelter emits iron_ingot.
        val q = api.query("minecraft:iron_ore")
        q.outputs.toSet() shouldBe setOf("minecraft:iron_ingot", "minecraft:raw_iron")
    }

    @Test
    fun `query returns empty lists when nothing matches`() {
        val sm = smelter("smelter")
        val api = RecipeApi(FakeEnv(listOf(sm)))

        val q = api.query("minecraft:obsidian")
        q.producers shouldBe emptyList()
        q.consumers shouldBe emptyList()
        q.inputs shouldBe emptyList()
        q.outputs shouldBe emptyList()
    }

    @Test
    fun `query returns empty lists when no bridges are on the channel`() {
        val api = RecipeApi(FakeEnv(emptyList()))
        val q = api.query("minecraft:iron_ingot")
        q.producers shouldBe emptyList()
        q.consumers shouldBe emptyList()
    }

    @Test
    fun `bridges that throw on canConsume are silently skipped`() {
        val good = smelter("good")
        val bad = object : BridgePeripheral {
            override val name = "bad"
            override val location: Position = Position.ORIGIN
            override val protocol: String = "test"
            override val target: String = "test:bad"
            override fun methods(): List<String> = listOf("canConsume", "canProduce")
            override fun call(method: String, args: List<Any?>): Any? = throw RuntimeException("kaboom")
        }
        val api = RecipeApi(FakeEnv(listOf(bad, good)))

        api.query("minecraft:raw_iron").consumers.map { it.name } shouldBe listOf("good")
    }

    @Test
    fun `invoke operator is sugar for query`() {
        val sm = smelter("smelter")
        val api = RecipeApi(FakeEnv(listOf(sm)))
        api("minecraft:iron_ingot").producers.map { it.name } shouldBe listOf("smelter")
    }
}
