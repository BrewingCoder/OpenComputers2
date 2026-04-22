package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent

/**
 * Per-master-BE client-side cache of [DynamicTexture]s that hold the monitor's
 * HD pixel buffer. The server-side pixel buffer (ARGB ints) is the source of
 * truth; clients sync the buffer via the standard BE NBT path, and this cache
 * mirrors those bytes into a GPU texture so the renderer can sample it as an
 * in-world textured quad.
 *
 * Lifecycle:
 * - Allocated on first render for a master
 * - Reallocated when pixel dimensions change (group resize)
 * - Freed when player logs out (world-leave)
 *
 * Must be used on the render thread. Upload is skipped when the buffer hash
 * matches the last upload — 99% of frames are no-op.
 */
@EventBusSubscriber(modid = OpenComputers2.ID, value = [Dist.CLIENT])
object MonitorPixelTextureCache {

    private class Entry(
        val texture: DynamicTexture,
        val location: ResourceLocation,
        var pxW: Int,
        var pxH: Int,
        var lastBufferHash: Int,
    )

    private val entries: MutableMap<BlockPos, Entry> = mutableMapOf()

    /**
     * Stable per-master texture ID. Path encodes the BlockPos as a hex long so
     * the same master always maps to the same resource location across frames.
     */
    private fun locationFor(pos: BlockPos): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(
            "oc2",
            "dynamic/monitor_pixels/" + java.lang.Long.toHexString(pos.asLong())
        )

    /**
     * Get (or allocate/reallocate) a [ResourceLocation] pointing at this master's
     * live pixel texture, registered with [net.minecraft.client.renderer.texture.TextureManager]
     * so it can be sampled via managed render types (e.g. `RenderType.text(rl)`).
     * Uploads the current buffer if its hash differs from the last uploaded one.
     * Returns null on failure.
     */
    fun getOrUpload(masterPos: BlockPos, pxW: Int, pxH: Int, sourceArgb: IntArray): ResourceLocation? {
        RenderSystem.assertOnRenderThread()
        val existing = entries[masterPos]
        val needRealloc = existing == null || existing.pxW != pxW || existing.pxH != pxH
        val entry = if (needRealloc) {
            existing?.let {
                Minecraft.getInstance().textureManager.release(it.location)
                it.texture.close()
            }
            val img = NativeImage(NativeImage.Format.RGBA, pxW, pxH, false)
            val tex = DynamicTexture(img)
            val rl = locationFor(masterPos)
            Minecraft.getInstance().textureManager.register(rl, tex)
            val e = Entry(tex, rl, pxW, pxH, lastBufferHash = 0)
            entries[masterPos] = e
            OpenComputers2.LOGGER.debug("MonitorPixelTextureCache: allocate {} at {}×{} → {}", masterPos, pxW, pxH, rl)
            e
        } else existing!!

        val newHash = sourceArgb.contentHashCode()
        if (newHash != entry.lastBufferHash || needRealloc) {
            val img = entry.texture.pixels ?: return entry.location
            // NativeImage.setPixelRGBA despite its name takes ABGR ints (native
            // little-endian byte order when read back as RGBA). Swap R↔B here.
            for (y in 0 until pxH) {
                val rowBase = y * pxW
                for (x in 0 until pxW) {
                    val argb = sourceArgb[rowBase + x]
                    val a = (argb ushr 24) and 0xFF
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                    img.setPixelRGBA(x, y, abgr)
                }
            }
            entry.texture.upload()
            entry.lastBufferHash = newHash
        }
        return entry.location
    }

    fun release(masterPos: BlockPos) {
        RenderSystem.assertOnRenderThread()
        entries.remove(masterPos)?.let {
            Minecraft.getInstance().textureManager.release(it.location)
            it.texture.close()
        }
    }

    fun releaseAll() {
        RenderSystem.assertOnRenderThread()
        if (entries.isEmpty()) return
        OpenComputers2.LOGGER.debug("MonitorPixelTextureCache: releaseAll ({} entries)", entries.size)
        val mgr = Minecraft.getInstance().textureManager
        for ((_, e) in entries) {
            mgr.release(e.location)
            e.texture.close()
        }
        entries.clear()
    }

    @SubscribeEvent
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        RenderSystem.recordRenderCall { releaseAll() }
    }
}
