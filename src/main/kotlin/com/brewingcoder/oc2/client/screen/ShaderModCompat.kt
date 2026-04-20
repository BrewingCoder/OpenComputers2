package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import net.neoforged.fml.ModList
import java.lang.reflect.Method

/**
 * Detects whether the current frame is being rendered as part of a shader-pack
 * shadow / depth pass. Shader packs (Iris/Oculus) call BlockEntityRenderer.render
 * multiple times per visible frame — once per geometry pass (gbuffer color,
 * shadow map, deferred composites). Drawing our terminal text into the shadow
 * pass writes character outlines into the shadow map, producing wrong shadows
 * and (often) invisible terminal in the main pass.
 *
 * Pattern lifted from CC:Tweaked's `ShaderMod` abstraction. CC has a service-
 * loader-based architecture; we use single-class reflection because we only
 * support Iris/Oculus (same API class).
 *
 * Reflection is one-time at class init: looks up `IrisApi.getInstance()` and
 * its `isRenderingShadowPass` method. Per-call we just invoke. If Iris isn't
 * loaded → method returns false → no skipping.
 */
object ShaderModCompat {

    /** True if Iris or Oculus is loaded as a mod. Doesn't say whether shaders are active. */
    val shaderModLoaded: Boolean =
        ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus")

    private data class IrisHooks(
        val isShaderPackInUse: () -> Boolean,
        val isRenderingShadowPass: () -> Boolean,
    )

    private val hooks: IrisHooks = run {
        if (!shaderModLoaded) {
            OpenComputers2.LOGGER.info("ShaderModCompat: Iris/Oculus not loaded — shader detection disabled")
            return@run IrisHooks({ false }, { false })
        }
        try {
            // Iris API is at net.irisshaders.iris.api.v0.IrisApi (same on Oculus
            // since Oculus reuses Iris's API surface).
            val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val getInstance = api.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val isInUseMethod: Method = api.getMethod("isShaderPackInUse")
            val isShadowPassMethod: Method = api.getMethod("isRenderingShadowPass")
            OpenComputers2.LOGGER.info("ShaderModCompat: Iris API hooked — shader detection live")
            IrisHooks(
                isShaderPackInUse = { runCatching { isInUseMethod.invoke(instance) as Boolean }.getOrDefault(false) },
                isRenderingShadowPass = { runCatching { isShadowPassMethod.invoke(instance) as Boolean }.getOrDefault(false) },
            )
        } catch (t: Throwable) {
            OpenComputers2.LOGGER.warn("ShaderModCompat: failed to bind IrisApi reflectively", t)
            IrisHooks({ false }, { false })
        }
    }

    /**
     * Dynamic — true RIGHT NOW if a shader pack is actively in use. Iris's
     * shader-toggle hotkey flips this without world reload, so renderers MUST
     * check per-frame, not at startup.
     */
    fun isShaderPackActive(): Boolean = hooks.isShaderPackInUse()

    fun isRenderingShadowPass(): Boolean = hooks.isRenderingShadowPass()
}
