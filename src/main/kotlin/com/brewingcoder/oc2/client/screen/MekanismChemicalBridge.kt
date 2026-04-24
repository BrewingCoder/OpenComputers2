package com.brewingcoder.oc2.client.screen

import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Soft-dep bridge to Mekanism's chemical system. Resolves a chemical
 * registry id (e.g. `"mekanism:hydrogen"`) into the sprite + tint color
 * needed to render it as a textured quad on top of the monitor.
 *
 * Mekanism is NOT on this mod's compile classpath. Everything here goes
 * through reflection so the module compiles and runs standalone; when
 * Mekanism is absent every call returns null, i.e. `drawChemical` is a
 * silent no-op.
 *
 * Reflected API (Mekanism 1.21.1, 10.7+):
 *   - `mekanism.api.chemical.Chemical` — the unified chemical class
 *   - `mekanism.api.MekanismAPI.CHEMICAL_REGISTRY` — `DefaultedRegistry<Chemical>` STATIC FIELD (not a method)
 *   - `mekanism.client.render.MekanismRenderer#getChemicalTexture(Holder<Chemical>): TextureAtlasSprite`
 *   - `Chemical#getTint(): int` — ARGB
 *
 * Init runs once, is memoized, and logs success/failure so future drift is diagnosable.
 */
internal object MekanismChemicalBridge {
    private val LOGGER = LoggerFactory.getLogger("oc2/MekanismChemicalBridge")

    private data class Ref(
        val chemicalClass: Class<*>,
        val registry: Any,
        val getChemicalTextureFromHolder: Method,
        val getTint: Method,
    )

    data class Resolved(val sprite: TextureAtlasSprite, val tint: Int)

    @Volatile private var resolved = false
    @Volatile private var ref: Ref? = null

    private fun reflectInit(): Ref? {
        if (resolved) return ref
        synchronized(this) {
            if (resolved) return ref
            resolved = true
            ref = try {
                val chemicalClass = Class.forName("mekanism.api.chemical.Chemical")
                val apiClass = Class.forName("mekanism.api.MekanismAPI")
                val registry = apiClass.getField("CHEMICAL_REGISTRY").get(null)
                if (registry == null) {
                    LOGGER.warn("MekanismAPI.CHEMICAL_REGISTRY is null; chemical icons disabled")
                    null
                } else {
                    val mekRendererClass = Class.forName("mekanism.client.render.MekanismRenderer")
                    val getChemicalTextureFromHolder = mekRendererClass.getMethod(
                        "getChemicalTexture", Holder::class.java
                    )
                    val getTint = chemicalClass.getMethod("getTint")
                    LOGGER.info("Mekanism chemical bridge initialized (registry={})", registry.javaClass.simpleName)
                    Ref(chemicalClass, registry, getChemicalTextureFromHolder, getTint)
                }
            } catch (t: Throwable) {
                LOGGER.warn("Mekanism chemical bridge init failed: {} ({})", t.javaClass.simpleName, t.message)
                null
            }
            return ref
        }
    }

    fun isAvailable(): Boolean = reflectInit() != null

    fun resolve(id: String): Resolved? {
        val r = reflectInit() ?: return null
        return try {
            val rl = ResourceLocation.tryParse(id) ?: return null
            val registry = r.registry as Registry<*>
            val chemical = registry.get(rl) ?: return null
            if (!r.chemicalClass.isInstance(chemical)) return null
            @Suppress("UNCHECKED_CAST")
            val holder = (registry as Registry<Any>).wrapAsHolder(chemical)
            val sprite = r.getChemicalTextureFromHolder.invoke(null, holder) as? TextureAtlasSprite
                ?: return null
            val tint = (r.getTint.invoke(chemical) as? Int) ?: -1
            Resolved(sprite, tint)
        } catch (t: Throwable) {
            LOGGER.warn("Chemical resolve failed for '{}': {} ({})", id, t.javaClass.simpleName, t.message)
            null
        }
    }
}
